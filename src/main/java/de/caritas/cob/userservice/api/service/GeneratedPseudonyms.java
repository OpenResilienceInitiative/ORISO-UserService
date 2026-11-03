package de.caritas.cob.userservice.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Accepts the two documented generator formats, never a client-provided "generated" flag. */
@Component
public final class GeneratedPseudonyms {
  private final JsonNode languages;
  private final Set<String> handleBases;

  public GeneratedPseudonyms() {
    try (var input =
        GeneratedPseudonyms.class.getResourceAsStream("/identity/pseudonym-vocabulary.json")) {
      if (input == null) throw new IllegalStateException("Missing pseudonym vocabulary");
      languages = new ObjectMapper().readTree(input).get("languages");
      Set<String> bases = new HashSet<>();
      languages.forEach(
          language ->
              language
                  .get("groups")
                  .forEach(
                      group ->
                          group
                              .get("animals")
                              .forEach(
                                  animal ->
                                      language
                                          .get("names")
                                          .forEach(
                                              name -> {
                                                String base =
                                                    slug(animal.asText() + " " + name.asText());
                                                if (base.isEmpty()) base = "oriso";
                                                bases.add(
                                                    base.substring(0, Math.min(25, base.length()))
                                                        .replaceAll("_+$", ""));
                                              }))));
      handleBases = Set.copyOf(bases);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid pseudonym vocabulary", invalid);
    }
  }

  /** Package-local immutable view for the cross-repository exact-set contract. */
  Set<String> handleBasesSnapshot() {
    return handleBases;
  }

  public boolean contains(String candidate) {
    if (candidate == null || candidate.isEmpty() || candidate.length() > 255) return false;
    String value = Normalizer.normalize(candidate, Normalizer.Form.NFC);
    if (value.matches("[a-z0-9_]+_[1-9][0-9]{3}")) {
      String base = value.substring(0, value.length() - 5);
      if (handleBases.contains(base)) return true;
    }
    for (JsonNode language : languages) {
      for (JsonNode name : language.get("names")) {
        String suffix = " " + name.asText();
        if (!value.endsWith(suffix)) continue;
        String phrase = value.substring(0, value.length() - suffix.length());
        for (JsonNode group : language.get("groups")) {
          for (JsonNode animal : group.get("animals")) {
            String animalSuffix = " " + animal.asText();
            if (!phrase.endsWith(animalSuffix)) continue;
            String adjective = phrase.substring(0, phrase.length() - animalSuffix.length());
            for (JsonNode allowed : group.get("adjectives")) {
              if (allowed.asText().equals(adjective)) return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static String slug(String value) {
    String germanSpelling =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("Ä", "ae")
            .replace("Ö", "oe")
            .replace("Ü", "ue")
            .replace("ß", "ss");
    return Normalizer.normalize(germanSpelling, Normalizer.Form.NFD)
        .replaceAll("[\\u0300-\\u036f]", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("^_+|_+$", "")
        .replaceAll("_{2,}", "_");
  }
}
