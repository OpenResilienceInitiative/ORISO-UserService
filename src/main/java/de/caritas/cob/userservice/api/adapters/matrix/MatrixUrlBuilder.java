package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;

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
    String url =
        UriComponentsBuilder.fromUriString(matrixConfig.getApiUrl(endpoint))
            .buildAndExpand(uriVariables)
            .encode()
            .toUriString();
    var builder = UriComponentsBuilder.fromUriString(url);
    queryParams.forEach(
        (name, value) -> {
          if (value != null) {
            builder.queryParam(name, value);
          }
        });
    return builder.build().encode().toUriString();
  }
}
