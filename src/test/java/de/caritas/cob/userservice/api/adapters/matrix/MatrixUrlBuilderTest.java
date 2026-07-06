package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MatrixUrlBuilder}.
 *
 * <p>Key invariants under test:
 *
 * <ol>
 *   <li>Matrix room IDs ({@code !room:server}) and user IDs ({@code @user:server}) that appear as
 *       path-template variables are <em>fully</em> percent-encoded: {@code !} → {@code %21}, {@code
 *       :} → {@code %3A}, {@code @} → {@code %40}. This requires calling {@code encode()}
 *       <em>before</em> {@code buildAndExpand()} so Spring treats the values as opaque data rather
 *       than URI structural characters.
 *   <li>Query parameters that contain literal JSON braces (e.g. the Matrix {@code /sync} filter) do
 *       not cause {@link IllegalArgumentException} ("Map has no value for …"). The builder expands
 *       path variables first (with no query params present), then appends query params with {@link
 *       org.springframework.web.util.UriUtils#encodeQueryParam}.
 * </ol>
 */
class MatrixUrlBuilderTest {

  private static final String BASE_URL = "https://matrix.example.org";

  private MatrixConfig matrixConfig() {
    var config = new MatrixConfig();
    config.setApiUrl(BASE_URL);
    return config;
  }

  // ── path-variable encoding ─────────────────────────────────────────────────

  @Test
  void buildUrl_ShouldEncodeRoomIdInPath() {
    var url =
        MatrixUrlBuilder.buildUrl(
            matrixConfig(),
            "/_matrix/client/r0/rooms/{roomId}/messages",
            Map.of("roomId", "!room:example.org"));

    assertThat(url).isEqualTo(BASE_URL + "/_matrix/client/r0/rooms/%21room%3Aexample.org/messages");
  }

  @Test
  void buildUrl_ShouldEncodeUserIdInPath() {
    var url =
        MatrixUrlBuilder.buildUrl(
            matrixConfig(),
            "/_matrix/client/r0/presence/{userId}/status",
            Map.of("userId", "@user:example.org"));

    assertThat(url)
        .isEqualTo(BASE_URL + "/_matrix/client/r0/presence/%40user%3Aexample.org/status");
  }

  @Test
  void buildUrl_ShouldEncodeMultiplePathVariables() {
    var url =
        MatrixUrlBuilder.buildUrl(
            matrixConfig(),
            "/_matrix/client/r0/rooms/{roomId}/state/m.room.member/{userId}",
            Map.of("roomId", "!room:example.org", "userId", "@user:example.org"));

    assertThat(url)
        .isEqualTo(
            BASE_URL
                + "/_matrix/client/r0/rooms/%21room%3Aexample.org"
                + "/state/m.room.member/%40user%3Aexample.org");
  }

  @Test
  void buildUrl_ShouldEncodeRoomIdAndIncludeQueryParams() {
    var url =
        MatrixUrlBuilder.buildUrl(
            matrixConfig(),
            "/_matrix/client/r0/rooms/{roomId}/messages",
            Map.of("roomId", "!room:example.org"),
            Map.of("dir", "b", "limit", 100));

    assertThat(url)
        .startsWith(BASE_URL + "/_matrix/client/r0/rooms/%21room%3Aexample.org/messages?");
    assertThat(url).contains("dir=b");
    assertThat(url).contains("limit=100");
  }

  // ── JSON filter in query params must not throw ────────────────────────────

  @Test
  void buildUrl_ShouldNotThrow_WhenQueryParamContainsJsonBraces() {
    // The Matrix /sync filter is a literal JSON string with `{` and `}`.  When buildAndExpand is
    // called after the query params are present, Spring mistakenly treats the braces as URI-
    // template variables and throws.  The fixed implementation expands the path first and then
    // appends the query params, so no exception is thrown.
    String jsonFilter =
        "{\"room\":{\"timeline\":{\"limit\":50},\"rooms\":[\"!room:example.org\"]}}";

    assertThatCode(
            () ->
                MatrixUrlBuilder.buildUrl(
                    matrixConfig(),
                    "/_matrix/client/r0/sync",
                    Map.of(),
                    Map.of("filter", jsonFilter, "timeout", 30000)))
        .doesNotThrowAnyException();
  }

  @Test
  void buildUrl_ShouldUrlEncodeJsonBracesInQueryParam() {
    String jsonFilter = "{\"room\":{\"timeline\":{\"limit\":50}}}";

    var url =
        MatrixUrlBuilder.buildUrl(
            matrixConfig(), "/_matrix/client/r0/sync", Map.of(), Map.of("filter", jsonFilter));

    assertThat(url).contains("filter=");
    // Braces must be percent-encoded in the query string.
    assertThat(url).contains("%7B"); // { → %7B
    assertThat(url).doesNotContain("{");
    assertThat(url).doesNotContain("}");
  }
}
