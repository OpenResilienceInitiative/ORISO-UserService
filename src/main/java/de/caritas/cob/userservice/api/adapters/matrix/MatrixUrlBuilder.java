package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

final class MatrixUrlBuilder {

  private MatrixUrlBuilder() {}

  static String buildUrl(MatrixConfig matrixConfig, String endpoint) {
    return buildUrl(matrixConfig, endpoint, Map.of(), Map.of());
  }

  static String buildUrl(MatrixConfig matrixConfig, String endpoint, Map<String, ?> uriVariables) {
    return buildUrl(matrixConfig, endpoint, uriVariables, Map.of());
  }

  static String buildUrl(
      MatrixConfig matrixConfig,
      String endpoint,
      Map<String, ?> uriVariables,
      Map<String, ?> queryParams) {
    // Encode the URI template FIRST so that template-variable values are percent-encoded when
    // they are expanded.  Calling encode() before buildAndExpand() makes Spring treat every
    // variable value as opaque data: characters such as '!' (%21), ':' (%3A) and '@' (%40) that
    // appear in Matrix room IDs / user IDs are fully percent-encoded in the resulting path, rather
    // than being left as raw sub-delimiters the way buildAndExpand().encode() would leave them.
    // No query params are present yet, so braces in JSON filter values cannot be mistaken for
    // URI-template variables by buildAndExpand.
    String expandedUrl =
        UriComponentsBuilder.fromUriString(matrixConfig.getApiUrl(endpoint))
            .encode()
            .buildAndExpand(uriVariables)
            .toUriString();
    var builder = UriComponentsBuilder.fromUriString(expandedUrl);
    queryParams.forEach(
        (name, value) -> {
          if (value != null) {
            builder.queryParam(
                name, UriUtils.encodeQueryParam(value.toString(), StandardCharsets.UTF_8));
          }
        });
    // Components are already encoded (path via .encode(), query values via encodeQueryParam), so
    // build with encoded=true and do not expand again.
    return builder.build(true).toUriString();
  }
}
