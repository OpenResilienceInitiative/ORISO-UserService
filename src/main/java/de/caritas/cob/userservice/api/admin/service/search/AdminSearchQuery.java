package de.caritas.cob.userservice.api.admin.service.search;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Words are ANDed; every word may match a different searchable field. */
public record AdminSearchQuery(List<String> tokens) {
  private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  public AdminSearchQuery {
    tokens = List.copyOf(tokens);
  }

  public static AdminSearchQuery parse(String raw) {
    if (raw == null) return new AdminSearchQuery(List.of());
    var words =
        SPACE
            .splitAsStream(raw)
            .filter(s -> !s.isBlank())
            .map(AdminSearchQuery::normalize)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    return new AdminSearchQuery(words.equals(List.of("*")) ? List.of() : words);
  }

  public static String normalize(String value) {
    return value == null
        ? ""
        : Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
  }

  public static String pattern(String token) {
    return "%" + token.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }
}
