package de.caritas.cob.userservice.api.admin.service.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminSearchQueryTest {
  @Test
  void normalizesUnicodeWordsAndAccents() {
    assertThat(AdminSearchQuery.parse("  MAX\u00a0Mu\u0308ller\tCaritás ").tokens())
        .containsExactly("max", "muller", "caritas");
  }

  @Test
  void preservesBlankAndLegacySentinel() {
    for (String raw : java.util.Arrays.asList(null, "", " \t", " * ", "\u00a0*\u00a0", "\u00a0"))
      assertThat(AdminSearchQuery.parse(raw).tokens()).isEmpty();
    assertThat(AdminSearchQuery.parse("max *").tokens()).containsExactly("max", "*");
  }

  @Test
  void escapesLiteralLikeMetacharacters() {
    assertThat(AdminSearchQuery.pattern("a!b%c_d")).isEqualTo("%a!!b!%c!_d%");
  }
}
