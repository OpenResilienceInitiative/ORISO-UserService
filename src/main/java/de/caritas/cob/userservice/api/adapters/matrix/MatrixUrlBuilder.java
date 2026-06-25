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
    // Expand path template variables on the endpoint FIRST, while no query params are present, so
    // that query values containing literal braces (e.g. a JSON `filter`) are never mistaken for
    // URI-template variables by buildAndExpand (which previously threw IllegalArgumentException and
    // silently disabled the room long-poll sync).
    String expandedUrl =
        UriComponentsBuilder.fromUriString(matrixConfig.getApiUrl(endpoint))
            .buildAndExpand(uriVariables)
            .encode()
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
