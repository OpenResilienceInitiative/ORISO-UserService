package de.caritas.cob.userservice.api.service.erstantwort;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform default wording is localized once at creation, then frozen as ordinary event text. */
public final class ErstantwortTranslations {
  private static final Set<String> LOCALES =
      Set.of("de", "de@informal", "en", "fr", "ru", "tr", "ti");

  private ErstantwortTranslations() {}

  /**
   * A strict whitelist protects classpath lookup; unknown/missing values preserve legacy wording.
   */
  public static String validLocale(String locale) {
    return locale != null && LOCALES.contains(locale) ? locale : null;
  }

  static void localizePlatformDefaults(
      List<Map<String, Object>> blocks, ErstantwortContext context) {
    var locale = validLocale(context.getLocale());
    if (locale == null) return;
    if (locale.equals("de") && context.isInformal()) locale = "de@informal";
    var catalogue = catalogue(locale);
    for (var block : blocks) {
      var id = String.valueOf(block.get("id"));
      if (id.equals("freeNotice")) continue;
      var translation = catalogue.path(id);
      if (!translation.isObject())
        throw new IllegalStateException("Missing Erstantwort block: " + id);
      if (!hasAuthoredBody(id, context)) {
        var body = translation.path("body").asText(null);
        if (body == null) throw new IllegalStateException("Missing Erstantwort body: " + id);
        var days =
            context.getResponseDeadlineDays() == null
                ? ErstantwortPayloadBuilder.DEFAULT_RESPONSE_DEADLINE_DAYS
                : context.getResponseDeadlineDays();
        block.put("body", body.replace("{{deadlineDays}}", String.valueOf(days)));
      }
      if (block.containsKey("headline"))
        block.put("headline", translation.path("headline").asText());
      if (block.get("action") instanceof Map<?, ?> action) {
        block.put(
            "action",
            Map.of("kind", action.get("kind"), "label", translation.path("action").asText()));
      }
      if (block.get("links") instanceof List<?> links) {
        block.put(
            "links",
            links.stream()
                .map(
                    link -> {
                      var value = (Map<?, ?>) link;
                      var label = String.valueOf(value.get("label"));
                      var key = label.equals("Datenschutzerklärung") ? "privacy" : "imprint";
                      return Map.of(
                          "url",
                          value.get("url"),
                          "label",
                          catalogue.path("links").path(key).asText());
                    })
                .toList());
      }
    }
  }

  private static boolean hasAuthoredBody(String id, ErstantwortContext context) {
    return switch (id) {
      case "greeting" ->
          any(
              context.getGreetingByTopic(),
              context.getGreetingByAgency(),
              context.getGreetingByTenant());
      case "whoReadsAlong" ->
          any(
              context.getWhoReadsAlongByTopic(),
              context.getWhoReadsAlongByAgency(),
              context.getWhoReadsAlongByTenant());
      case "closing" ->
          any(
              context.getClosingByTopic(),
              context.getClosingByAgency(),
              context.getClosingByTenant());
      default -> false;
    };
  }

  private static boolean any(String... values) {
    for (var value : values) if (isNotBlank(value)) return true;
    return false;
  }

  private static JsonNode catalogue(String locale) {
    try (var resource =
        ErstantwortTranslations.class.getResourceAsStream("/erstantwort/" + locale + ".json")) {
      if (resource == null)
        throw new IllegalStateException("Missing Erstantwort locale: " + locale);
      return new ObjectMapper().readTree(resource);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read Erstantwort locale: " + locale, exception);
    }
  }
}
