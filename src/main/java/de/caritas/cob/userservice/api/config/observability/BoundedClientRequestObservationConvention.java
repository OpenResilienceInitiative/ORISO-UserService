package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.common.KeyValue;
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
  private static final String UNTEMPLATED = "untemplated";

  @Override
  protected KeyValue uri(ClientRequestObservationContext context) {
    var template = context.getUriTemplate();
    if (template == null || template.isBlank()) {
      return KeyValue.of(URI_TAG, UNTEMPLATED);
    }

    return KeyValue.of(URI_TAG, pathWithoutQuery(template));
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
