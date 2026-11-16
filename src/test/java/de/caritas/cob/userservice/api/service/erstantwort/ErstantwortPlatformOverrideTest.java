package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The platform level of the resolution chain (ADR-018 §6): Beratungsstelle ?? Träger ?? platform
 * override ?? built-in default. The platform admin's text replaces the built-in default, never a
 * Träger's or a Beratungsstelle's own text.
 */
class ErstantwortPlatformOverrideTest {

  private final ErstantwortPayloadBuilder builder = new ErstantwortPayloadBuilder();

  @ParameterizedTest
  @CsvSource({"de", "de@informal", "en", "fr", "ru", "tr", "ti"})
  void platformGreetingReplacesTheBuiltInDefaultInEveryLocale(String locale) throws Exception {
    var context =
        ErstantwortContext.builder()
            .locale(locale)
            .platformTexts(Map.of("greeting", "Platform hello", "closing", "Platform bye"))
            .build();

    var payload = payload(context);

    assertThat(block(payload, "greeting").path("body").asText()).isEqualTo("Platform hello");
    assertThat(block(payload, "closing").path("body").asText()).isEqualTo("Platform bye");
  }

  @Test
  void platformGreetingAlsoAppliesWithoutAValidLocale() throws Exception {
    var context =
        ErstantwortContext.builder().platformTexts(Map.of("greeting", "Platform hello")).build();

    assertThat(block(payload(context), "greeting").path("body").asText())
        .isEqualTo("Platform hello");
  }

  @Test
  void tragerAndAgencyTextsStillWinOverThePlatformOverride() throws Exception {
    var tenant =
        ErstantwortContext.builder()
            .locale("en")
            .greetingByTenant("Träger hello")
            .closingByAgency("Centre bye")
            .platformTexts(Map.of("greeting", "Platform hello", "closing", "Platform bye"))
            .build();

    var payload = payload(tenant);

    assertThat(block(payload, "greeting").path("body").asText()).isEqualTo("Träger hello");
    assertThat(block(payload, "closing").path("body").asText()).isEqualTo("Centre bye");
  }

  @Test
  void platformEmergencyNumbersReplaceTheBuiltInSentenceAndKeepTheTragerAddition()
      throws Exception {
    var context =
        ErstantwortContext.builder()
            .locale("de")
            .emergencyAdditionByTenant("Krisendienst 089 12345")
            .platformTexts(Map.of("emergencyNumbers", "Notruf 144"))
            .build();

    var payload = payload(context);

    assertThat(block(payload, "emergencyNumbers").path("body").asText()).isEqualTo("Notruf 144");
    assertThat(block(payload, "emergencyNumbers").path("headline").asText())
        .isEqualTo("Wenn es nicht warten kann");
    assertThat(block(payload, "emergencyAddition").path("body").asText())
        .isEqualTo("Krisendienst 089 12345");
  }

  @Test
  void blankPlatformTextsCountAsAbsent() throws Exception {
    var context =
        ErstantwortContext.builder()
            .locale("en")
            .platformTexts(Map.of("greeting", "  ", "closing", "​"))
            .build();

    var payload = payload(context);

    assertThat(block(payload, "greeting").path("body").asText())
        .isEqualTo("Thank you for getting in touch. Your message has reached us.");
    assertThat(block(payload, "closing").path("body").asText())
        .isEqualTo("See you soon — we will get back to you.");
  }

  /** #1565: the platform must never speak about who reads along, not even via an override. */
  @Test
  void aPlatformTextForWhoReadsAlongIsIgnored() throws Exception {
    var context =
        ErstantwortContext.builder()
            .locale("en")
            .platformTexts(Map.of("whoReadsAlong", "Everyone reads along"))
            .build();

    for (var block : payload(context).path("bausteine")) {
      assertThat(block.path("id").asText()).isNotEqualTo("whoReadsAlong");
    }
  }

  private JsonNode payload(ErstantwortContext context) throws Exception {
    var body = builder.buildFirstResponseBody(context);
    return new ObjectMapper()
        .readTree(body.substring(ErstantwortPayloadBuilder.SYSTEM_NOTIFICATION_PREFIX.length()));
  }

  private static JsonNode block(JsonNode payload, String id) {
    for (var block : payload.path("bausteine")) {
      if (id.equals(block.path("id").asText())) {
        return block;
      }
    }
    throw new AssertionError("No block " + id);
  }
}
