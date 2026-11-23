package de.caritas.cob.userservice.api.service.erstantwort;

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

  /**
   * The platform's own plain texts for the Träger editor, so an admin sees what is sent when the
   * field stays empty. {@code de} with {@code informal} selects the {@code de@informal} variant.
   * {@code whoReadsAlong} is deliberately absent: it has no platform wording, because the platform
   * cannot know who reads along at a given centre (ORISO-UserService#1565).
   *
   * @throws IllegalArgumentException for a locale outside the whitelist
   */
  public static Map<String, Object> platformDefaults(String locale, boolean informal) {
    var valid = validLocale(locale == null || locale.isBlank() ? "de" : locale);
    if (valid == null) throw new IllegalArgumentException("Unsupported Erstantwort locale");
    if (valid.equals("de") && informal) valid = "de@informal";
    var catalogue = catalogue(valid);
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("locale", valid);
    for (var id : List.of("greeting", "closing", "emergencyNumbers")) {
      result.put(id, catalogue.path(id).path("body").asText(""));
    }
    result.put(
        "responseDeadline",
        catalogue
            .path("responseDeadline")
            .path("body")
            .asText("")
            .replace(
                "{{deadlineDays}}",
                String.valueOf(ErstantwortPayloadBuilder.DEFAULT_RESPONSE_DEADLINE_DAYS)));
    result.put("responseDeadlineDays", ErstantwortPayloadBuilder.DEFAULT_RESPONSE_DEADLINE_DAYS);
    return result;
  }

  /**
   * The built-in Caritas wording of every platform-editable Baustein in all seven language
   * versions, for the platform editor to show what applies while a field stays empty.
   */
  public static Map<String, Map<String, String>> builtInDefaults() {
    var result = new java.util.LinkedHashMap<String, Map<String, String>>();
    for (var id : PLATFORM_EDITABLE) {
      var perLanguage = new java.util.LinkedHashMap<String, String>();
      for (var locale : List.of("de", "de@informal", "en", "fr", "ru", "tr", "ti")) {
        perLanguage.put(locale, catalogue(locale).path(id).path("body").asText(""));
      }
      result.put(id, perLanguage);
    }
    return result;
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

  /** The Bausteine a platform admin may reword (ADR-018 §6, platform level). */
  public static final List<String> PLATFORM_EDITABLE =
      List.of("greeting", "closing", "emergencyNumbers");

  /**
   * Last step before freezing: the platform admin's wording replaces the built-in default of the
   * editable Bausteine, unless a more specific level authored the text. Headlines, actions and
   * links stay as localized above.
   */
  static void applyPlatformTexts(List<Map<String, Object>> blocks, ErstantwortContext context) {
    var platform = context.getPlatformTexts();
    if (platform == null || platform.isEmpty()) return;
    for (var block : blocks) {
      var id = String.valueOf(block.get("id"));
      if (!PLATFORM_EDITABLE.contains(id) || hasAuthoredBody(id, context)) continue;
      var text = platform.get(id);
      if (ErstantwortText.hasText(text)) block.put("body", text.trim());
    }
  }

  private static boolean hasAuthoredBody(String id, ErstantwortContext context) {
    return switch (id) {
      case "greeting" ->
          ErstantwortText.anyText(
              context.getGreetingByTopic(),
              context.getGreetingByAgency(),
              context.getGreetingByTenant());
      case "whoReadsAlong" ->
          ErstantwortText.anyText(
              context.getWhoReadsAlongByTopic(),
              context.getWhoReadsAlongByAgency(),
              context.getWhoReadsAlongByTenant());
      case "emergencyAddition" -> true;
      case "closing" ->
          ErstantwortText.anyText(
              context.getClosingByTopic(),
              context.getClosingByAgency(),
              context.getClosingByTenant());
      default -> false;
    };
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
