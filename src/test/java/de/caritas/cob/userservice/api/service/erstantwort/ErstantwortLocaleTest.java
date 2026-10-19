package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErstantwortLocaleTest {
  private final ErstantwortPayloadBuilder builder = new ErstantwortPayloadBuilder();

  @ParameterizedTest
  @CsvSource({
    "de,Wer Ihre Nachricht liest",
    "de@informal,Wer Deine Nachricht liest",
    "en,Who reads your message",
    "fr,Qui lit votre message",
    "ru,Кто читает ваше сообщение",
    "tr,Mesajınızı kim okuyor",
    "ti,መልእኽትኹም መን ከም ዘንብቦ"
  })
  void freezesNewPlatformDefaultsInTheRequestedLanguage(String locale, String heading)
      throws Exception {
    var context = ErstantwortContext.builder().locale(locale).responseDeadlineDays(7).build();
    var payload = payload(context);
    var readers = block(payload, "whoReadsAlong");
    assertThat(readers.path("headline").asText()).isEqualTo(heading);
    assertThat(block(payload, "responseDeadline").path("body").asText()).contains("7");
    assertThat(payload.toString()).doesNotContain("{{", "erstantwort.");
  }

  @Test
  void germanLocaleWithInformalPreferenceFreezesInformalHeadlineAndBody() throws Exception {
    var context = ErstantwortContext.builder().locale("de").informal(true).build();
    var readers = block(payload(context), "whoReadsAlong");
    assertThat(readers.path("headline").asText()).isEqualTo("Wer Deine Nachricht liest");
    assertThat(readers.path("body").asText())
        .isEqualTo(
            "Deine Nachricht lesen ausschließlich die Fachkräfte der zuständigen Beratungsstelle."
                + " Alle sind zur Verschwiegenheit verpflichtet.");
  }

  @ParameterizedTest
  @CsvSource({"en", "fr", "ru", "tr", "ti", "de@informal"})
  void freezesAuthoredTextVerbatimWithoutTranslatingIt(String locale) throws Exception {
    var context =
        ErstantwortContext.builder()
            .locale(locale)
            .greetingByTenant("FROZEN AUTHOR TEXT")
            .whoReadsAlongByAgency("FROZEN AGENCY TEXT")
            .closingByTopic("FROZEN TOPIC TEXT")
            .freeNoticeByTenant("FROZEN FREE TEXT")
            .build();
    var payload = payload(context);
    assertThat(block(payload, "greeting").path("body").asText()).isEqualTo("FROZEN AUTHOR TEXT");
    assertThat(block(payload, "whoReadsAlong").path("body").asText())
        .isEqualTo("FROZEN AGENCY TEXT");
    assertThat(block(payload, "closing").path("body").asText()).isEqualTo("FROZEN TOPIC TEXT");
    assertThat(block(payload, "freeNotice").path("body").asText()).isEqualTo("FROZEN FREE TEXT");
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void missingOrInvalidLocaleKeepsTheExistingGermanVariant(boolean informal) {
    var legacy =
        builder.buildFirstResponseBody(ErstantwortContext.builder().informal(informal).build());
    for (var locale : new String[] {"../emails", "es", "en-US", ""}) {
      assertThat(
              builder.buildFirstResponseBody(
                  ErstantwortContext.builder().locale(locale).informal(informal).build()))
          .isEqualTo(legacy);
    }
  }

  private JsonNode payload(ErstantwortContext context) throws Exception {
    var body = builder.buildFirstResponseBody(context);
    return new ObjectMapper()
        .readTree(body.substring(ErstantwortPayloadBuilder.SYSTEM_NOTIFICATION_PREFIX.length()));
  }

  private JsonNode block(JsonNode payload, String id) {
    for (var block : payload.path("bausteine")) {
      if (block.path("id").asText().equals(id)) return block;
    }
    throw new AssertionError("Missing block: " + id);
  }
}
