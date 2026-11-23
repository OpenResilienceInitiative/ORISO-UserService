package de.caritas.cob.userservice.api.service.draft;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Mirrors the frontend's {@code hasDraftContent} (ORISO-Frontend {@code
 * src/services/draftStore.ts}), the reference for what counts as draft content (#983 / frontend
 * #976): markup and whitespace entities do not count, opaque E2EE ciphertext always does.
 */
class DraftContentTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "\n",
        "\t \r\n",
        "<p></p>",
        "<p><br></p>",
        "<p><br/></p>",
        "<p></p><p></p>",
        "&nbsp;",
        "&#160;",
        "&NBSP;",
        // Same two characters, hexadecimal and decimal numeric forms — an editor or a paste picks
        // whichever it likes, so none of them may slip through as content.
        "&#xA0;",
        "&#X00a0;",
        "&#8203;",
        "&#x200B;",
        "<p>&#xA0;</p><p>&#x200b;</p>",
        "<p>&nbsp;</p>",
        "<p> </p>",
        " ",
        "​",
        "<div><span> </span></div>"
      })
  void hasContent_returnsFalse_forEmptyOrMarkupOnlyDrafts(String text) {
    assertThat(DraftContent.hasContent(text)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Hello",
        "<p>Hello</p>",
        "<p>&nbsp;Hello&nbsp;</p>",
        "a",
        // Opaque E2EE ciphertext carries no markup and must never be treated as empty.
        "AwgBmE3yLpFhZ0uK+base64ciphertext==",
        "{\"ciphertext\":\"AwgB\",\"ephemeral\":\"key\"}"
      })
  void hasContent_returnsTrue_forRealTextAndCiphertext(String text) {
    assertThat(DraftContent.hasContent(text)).isTrue();
  }
}
