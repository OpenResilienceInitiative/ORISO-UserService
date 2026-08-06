package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.common.KeyValue;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Prevents dynamic outbound URLs from creating an unbounded standard HTTP-client metric surface.
 *
 * <p>Spring can only safely identify an endpoint when the caller supplied a URI template. Query
 * parameters are dynamic even on templates and are therefore removed. Calls made with a concrete
 * URI are grouped as {@code untemplated}; dependency, method, outcome and status remain available
 * on the standard meter, while {@link OutboundHttpMetrics} provides the bounded dependency view.
 */
final class BoundedClientRequestObservationConvention
    extends DefaultClientRequestObservationConvention {

  private static final String URI_TAG = "uri";
  private static final String HTTP_URL_TAG = "http.url";
  private static final String UNTEMPLATED = "untemplated";
  private static final String RELATIVE_URI = "relative-uri";

  @Override
  protected KeyValue uri(ClientRequestObservationContext context) {
    var template = context.getUriTemplate();
    if (template == null || template.isBlank()) {
      return KeyValue.of(URI_TAG, UNTEMPLATED);
    }

    return KeyValue.of(URI_TAG, pathWithoutQuery(template));
  }

  @Override
  protected KeyValue requestUri(ClientRequestObservationContext context) {
    return KeyValue.of(HTTP_URL_TAG, dependencyOrigin(context.getCarrier().getURI()));
  }

  private String dependencyOrigin(URI uri) {
    var scheme = uri.getScheme();
    var host = uri.getHost();
    if (scheme == null || host == null || scheme.isBlank() || host.isBlank()) {
      return RELATIVE_URI;
    }

    var normalizedHost = host.toLowerCase(Locale.ROOT);
    if (normalizedHost.contains(":")) {
      normalizedHost = "[" + normalizedHost + "]";
    }
    var port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
    return scheme.toLowerCase(Locale.ROOT) + "://" + normalizedHost + port;
  }

  private String pathWithoutQuery(String template) {
    var queryStart = template.indexOf('?');
    var queryless = queryStart >= 0 ? template.substring(0, queryStart) : template;
    var schemeEnd = queryless.indexOf("://");
    if (schemeEnd < 0) {
      return queryless.isBlank() ? "/" : queryless;
    }

    var pathStart = queryless.indexOf('/', schemeEnd + 3);
    return pathStart < 0 ? "/" : queryless.substring(pathStart);
  }
}
