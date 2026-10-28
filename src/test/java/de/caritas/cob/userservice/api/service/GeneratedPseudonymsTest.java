package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class GeneratedPseudonymsTest {
  private final GeneratedPseudonyms vocabulary = new GeneratedPseudonyms();

  @Test
  void canonicalHandleSetMatchesTheActualFrontendFunctionExactly() throws Exception {
    var fixture =
        new ObjectMapper()
            .readTree(getClass().getResourceAsStream("/identity/frontend-generated-handles.json"));
    var bases = vocabulary.handleBasesSnapshot();
    assertThat(fixture.get("formatVersion").asInt()).isEqualTo(1);
    assertThat(bases.size()).isEqualTo(fixture.get("count").asInt());
    String canonical = String.join("\n", bases.stream().sorted().toList()) + "\n";
    byte[] digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(java.util.HexFormat.of().formatHex(digest))
        .isEqualTo(fixture.get("sha256").asText());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> bases.add("unapproved"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void germanUmlautsUseTheFrontendSpellingRatherThanDroppingDots() {
    assertThat(vocabulary.contains("loewe_mika_1234")).isTrue();
    assertThat(vocabulary.contains("baer_mika_1234")).isTrue();
  }

  @Test
  void supportsEveryCuratedLocaleAndBothGenderGroupsWithUnicode() throws Exception {
    var languages =
        new ObjectMapper()
            .readTree(getClass().getResourceAsStream("/identity/pseudonym-vocabulary.json"))
            .get("languages");
    assertThat(languages.size()).isEqualTo(6);
    for (var language : languages) {
      for (var group : language.get("groups")) {
        for (var animal : group.get("animals")) {
          String full =
              group.get("adjectives").get(0).asText()
                  + " "
                  + animal.asText()
                  + " "
                  + language.get("names").get(0).asText();
          assertThat(vocabulary.contains(full)).as(full).isTrue();
          assertThat(vocabulary.contains(Normalizer.normalize(full, Normalizer.Form.NFD))).isTrue();
        }
      }
    }
  }

  @Test
  void handlesMatchTheRegistrationUsernameFormatAndCannotCarryArbitraryText() {
    assertThat(vocabulary.contains("katze_mika_1234")).isTrue();
    assertThat(vocabulary.contains("oriso_9999")).isTrue();
    for (var invalid :
        new String[] {
          "Dr Alice Personal",
          "katze_mika",
          "katze_mika_0000",
          "katze_mika_12345",
          "katze_mika_1234 extra",
          "<script>",
          " ruhige Katze Mika",
          ""
        }) {
      assertThat(vocabulary.contains(invalid)).as(invalid).isFalse();
    }
  }
}
