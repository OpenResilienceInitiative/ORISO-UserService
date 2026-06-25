package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;

final class MatrixUrlBuilder {

  private static final String QUERY_VAR_PREFIX = "q__";

  private MatrixUrlBuilder() {}

  static String buildUrl(MatrixConfig matrixConfig, String endpoint) {
    return buildUrl(matrixConfig, endpoint, Map.of(), Map.of());
  }

  static String buildUrl(MatrixConfig matrixConfig, String endpoint, Map<String, ?> uriVariables) {
    return buildUrl(matrixConfig, endpoint, uriVariables, Map.of());
  }

  /**
   * Builds a fully-encoded Matrix API URL.
   *
   * <p>Query values are bound as URI variables (named {@code q__<paramName>}) instead of being
   * appended literally. This stops {@link UriComponentsBuilder#buildAndExpand} from treating braces
   * inside a value -- e.g. a JSON Matrix {@code filter} payload -- as URI-template placeholders,
   * which previously threw an {@link IllegalArgumentException} and left the long-poll sync silently
   * returning no messages.
   *
   * <p>Encoding runs after expansion, so each value is escaped per its URI component:
   * reserved-but-legal characters such as {@code !} and {@code :} stay literal, while structural
   * JSON characters are percent-encoded.
   */
  static String buildUrl(
      MatrixConfig matrixConfig,
      String endpoint,
      Map<String, ?> uriVariables,
      Map<String, ?> queryParams) {
    var builder = UriComponentsBuilder.fromUriString(matrixConfig.getApiUrl(endpoint));
    var allVariables = new LinkedHashMap<String, Object>(uriVariables);
    queryParams.forEach(
        (name, value) -> {
          if (value != null) {
            var varName = QUERY_VAR_PREFIX + name;
            builder.queryParam(name, "{" + varName + "}");
            allVariables.put(varName, value);
          }
        });
    return builder.buildAndExpand(allVariables).encode().toUriString();
  }
}
