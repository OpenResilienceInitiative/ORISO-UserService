package de.caritas.cob.userservice.api.service.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Backend-owned catalogue copied from the deployed frontend's curated animal/name tables. The
 * visible identity is the actual login username, as in the current entry room. Frontend generators
 * stay available only for old clients until the consumer cutover.
 */
@Component
public class GuestIdentityCatalog {
  private final Map<String, Language> languages;
  private final Map<String, Set<String>> allowedBases = new HashMap<>();
  private final SecureRandom random = new SecureRandom();

  public GuestIdentityCatalog() {
    try (var source = getClass().getResourceAsStream("/identity/guest-name-catalog.json")) {
      if (source == null) throw new IllegalStateException("Missing guest identity catalogue");
      languages = new ObjectMapper().readValue(source, new TypeReference<>() {});
      indexSelections();
    } catch (IOException e) {
      throw new IllegalStateException("Invalid guest identity catalogue", e);
    }
  }

  /** Validates the coupled selection without reserving names or accepting arbitrary asset paths. */
  /**
   * Another name for the same animal, for when the chosen one turns out to be taken.
   *
   * <p>The avatar is what the guest picked off the card, so it stays. Only the name behind it
   * changes, which is the smallest surprise we can hand someone who has already decided. Empty when
   * this avatar has nothing else to offer.
   */
  public Optional<String> nextForAvatar(String avatarKey, Set<String> alreadyTried) {
    List<String> bases =
        allowedBases.getOrDefault(avatarKey, Set.of()).stream()
            .sorted()
            .collect(Collectors.toList());
    Collections.shuffle(bases, random);
    return bases.stream()
        .map(base -> base + "_" + (1000 + random.nextInt(9000)))
        .filter(candidate -> !alreadyTried.contains(candidate))
        .findFirst();
  }

  public boolean isKnownSelection(String username, String avatarKey) {
    return username != null
        && username.matches("[a-z0-9_]{1,25}_[1-9][0-9]{3}")
        && avatarKey != null
        && avatarKey.length() <= 128
        && allowedBases
            .getOrDefault(avatarKey, Set.of())
            .contains(username.substring(0, username.length() - 5));
  }

  private void indexSelections() {
    // Per language, with that language's own spelling rule: the index decides which offered name
    // Join will later accept, so it has to be built exactly the way next() builds its offers.
    for (var entry : languages.entrySet()) {
      String locale = entry.getKey();
      Language language = entry.getValue();
      for (Animal animal : language.animals()) {
        for (String name : language.names()) {
          for (String actualName :
              slug(locale, name).isEmpty() ? languages.get("en").names() : List.of(name)) {
            String sample =
                identityFor(locale, animal.label(), animal.svg(), actualName, 1000).username();
            allowedBases
                .computeIfAbsent(animal.svg(), ignored -> new HashSet<>())
                .add(sample.substring(0, sample.length() - 5));
          }
        }
      }
    }
  }

  public GuestIdentitySuggestion next(String locale) {
    String normalized = locale == null ? "de" : locale.toLowerCase(Locale.ROOT).split("[-_@.]")[0];
    Language language = languages.getOrDefault(normalized, languages.get("en"));
    Animal animal = pick(language.animals());
    String name = pick(language.names());
    // Credentials use the same ASCII alphabet as registration. For scripts that do not
    // normalize to ASCII, use the animal asset's stable English stem and an English name.
    if (slug(normalized, name).isEmpty()) name = pick(languages.get("en").names());
    return identityFor(normalized, animal.label(), animal.svg(), name, 1000 + random.nextInt(9000));
  }

  static GuestIdentitySuggestion identityFor(
      String animalLabel, String avatarKey, String name, int suffix) {
    return identityFor("de", animalLabel, avatarKey, name, suffix);
  }

  static GuestIdentitySuggestion identityFor(
      String locale, String animalLabel, String avatarKey, String name, int suffix) {
    String animal = slug(locale, animalLabel);
    if (animal.isEmpty()) animal = slug(locale, avatarKey.replace(".svg", ""));
    String base = animal + "_" + slug(locale, name);
    base = base.substring(0, Math.min(25, base.length())).replaceAll("_+$", "");
    String username = base + "_" + suffix;
    return new GuestIdentitySuggestion(username, username, avatarKey);
  }

  private static String slug(String value) {
    return slug("de", value);
  }

  /**
   * German spells its umlauts out when it cannot draw the dots: Löwe becomes loewe, not lowe, and
   * stripping the diacritic turns a familiar animal into a typo.
   *
   * <p>No other language here does that. In Spanish the dots on Pingüino are a diaeresis marking a
   * spoken u; "pingueino" invents a syllable and hands the guest a name their own language does not
   * contain. So the spelling-out is German only, and every other locale simply loses the mark.
   */
  private static String slug(String locale, String value) {
    String lowered = value.toLowerCase(Locale.ROOT);
    if (isGerman(locale)) {
      lowered = lowered.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }
    return Normalizer.normalize(lowered, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
  }

  private static boolean isGerman(String locale) {
    return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("de");
  }

  private <T> T pick(List<T> values) {
    return values.get(random.nextInt(values.size()));
  }

  public record Animal(String label, String svg) {}

  public record Language(List<Animal> animals, List<String> names) {}
}
