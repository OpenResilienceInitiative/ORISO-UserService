package de.caritas.cob.userservice.api.service.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Backend-owned catalogue copied from the deployed frontend's curated animal/name tables. The
 * visible identity is the actual login username, as in the current entry room. Frontend generators
 * stay available only for old clients until the consumer cutover.
 */
@Component
public class GuestIdentityCatalog {
  private final Map<String, Language> languages;
  private final SecureRandom random = new SecureRandom();

  public GuestIdentityCatalog() {
    try (var source = getClass().getResourceAsStream("/identity/guest-name-catalog.json")) {
      if (source == null) throw new IllegalStateException("Missing guest identity catalogue");
      languages = new ObjectMapper().readValue(source, new TypeReference<>() {});
    } catch (IOException e) {
      throw new IllegalStateException("Invalid guest identity catalogue", e);
    }
  }

  public GuestIdentitySuggestion next(String locale) {
    String normalized = locale == null ? "de" : locale.toLowerCase(Locale.ROOT).split("[-_@.]")[0];
    Language language = languages.getOrDefault(normalized, languages.get("en"));
    Animal animal = pick(language.animals());
    String name = pick(language.names());
    // Credentials use the same ASCII alphabet as registration. For scripts that do not
    // normalize to ASCII, use the animal asset's stable English stem and an English name.
    if (slug(name).isEmpty()) name = pick(languages.get("en").names());
    return identityFor(animal.label(), animal.svg(), name, 1000 + random.nextInt(9000));
  }

  static GuestIdentitySuggestion identityFor(
      String animalLabel, String avatarKey, String name, int suffix) {
    String animal = slug(animalLabel);
    if (animal.isEmpty()) animal = slug(avatarKey.replace(".svg", ""));
    String base = animal + "_" + slug(name);
    base = base.substring(0, Math.min(25, base.length())).replaceAll("_+$", "");
    String username = base + "_" + suffix;
    return new GuestIdentitySuggestion(username, username, avatarKey);
  }

  private static String slug(String value) {
    return Normalizer.normalize(
            value
                .toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss"),
            Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
  }

  private <T> T pick(List<T> values) {
    return values.get(random.nextInt(values.size()));
  }

  public record Animal(String label, String svg) {}

  public record Language(List<Animal> animals, List<String> names) {}
}
