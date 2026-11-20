package de.caritas.cob.userservice.api.service.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.service.GeneratedPseudonyms;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One naming scheme for every guest (ORISO-Frontend#2346). The canonical vocabulary is {@code
 * identity/pseudonym-vocabulary.json}, generated from the Frontend dice engine and checked against
 * it in CI ({@code guest-identity-catalog.yml}). The server's own roll table, {@code
 * identity/guest-name-catalog.json}, adds the avatar file per animal and must say exactly the same
 * thing — otherwise a server-rolled guest gets a name the Frontend could never roll.
 */
class GuestIdentityCatalogParityTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"de", "en", "fr", "es", "ru", "uk"})
  void rollTableHasExactlyTheAnimalsAndNamesOfTheCanonicalVocabulary(String locale)
      throws Exception {
    JsonNode canonical = read("/identity/pseudonym-vocabulary.json").get("languages").get(locale);
    JsonNode rollTable = read("/identity/guest-name-catalog.json").get(locale);

    Set<String> canonicalAnimals = new TreeSet<>();
    canonical
        .get("groups")
        .forEach(g -> g.get("animals").forEach(a -> canonicalAnimals.add(a.asText())));
    Set<String> rolledAnimals = new TreeSet<>();
    rollTable.get("animals").forEach(a -> rolledAnimals.add(a.get("label").asText()));

    assertThat(rolledAnimals).as("animals (%s)", locale).isEqualTo(canonicalAnimals);
    assertThat(texts(rollTable.get("names")))
        .as("names (%s)", locale)
        .isEqualTo(texts(canonical.get("names")));
  }

  @Test
  void bothTablesCoverTheSameLanguages() throws Exception {
    assertThat(fieldNames(read("/identity/guest-name-catalog.json")))
        .isEqualTo(fieldNames(read("/identity/pseudonym-vocabulary.json").get("languages")));
  }

  /**
   * Latin-script languages only. For Russian and Ukrainian the Frontend drops the Cyrillic animal
   * and builds {@code alex_1234}; the server uses the avatar stem and builds {@code owl_alex_1234}.
   * Both are valid logins, but they are not one scheme — tracked separately, see the PR.
   */
  @ParameterizedTest
  @ValueSource(strings = {"de", "en", "fr", "es"})
  void everyHandleTheServerRollsIsOneTheFrontendCanRoll(String locale) throws Exception {
    var vocabulary = new GeneratedPseudonyms();
    JsonNode rollTable = read("/identity/guest-name-catalog.json").get(locale);

    List<String> foreign = new ArrayList<>();
    for (JsonNode animal : rollTable.get("animals")) {
      for (JsonNode name : rollTable.get("names")) {
        String username =
            GuestIdentityCatalog.identityFor(
                    animal.get("label").asText(), animal.get("svg").asText(), name.asText(), 1234)
                .username();
        if (!vocabulary.containsHandle(username)) foreign.add(username);
      }
    }

    assertThat(foreign).as("server-rolled handles the Frontend cannot roll (%s)", locale).isEmpty();
  }

  private static JsonNode read(String resource) throws Exception {
    try (var in = GuestIdentityCatalogParityTest.class.getResourceAsStream(resource)) {
      return JSON.readTree(in);
    }
  }

  private static List<String> texts(JsonNode array) {
    var out = new ArrayList<String>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  private static Set<String> fieldNames(JsonNode node) {
    var out = new TreeSet<String>();
    node.fieldNames().forEachRemaining(out::add);
    return out;
  }
}
