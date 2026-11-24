package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.service.journey.PublicJourneyService;
import de.caritas.cob.userservice.api.service.journey.PublicJourneyService.PublicJourney;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ADR-036 decision 42 (ORISO-Admin#1660): the first reply built from the journey's on-board steps.
 */
class JourneyFirstReplyBuilderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EMERGENCY_NUMBER = "0800 111 0 111";

  private final PublicJourneyService journeys = mock(PublicJourneyService.class);
  private final JourneyFirstReplyBuilder builder =
      new JourneyFirstReplyBuilder(journeys, new ErstantwortPayloadBuilder());

  @BeforeEach
  void enable() {
    ReflectionTestUtils.setField(builder, "enabled", true);
  }

  private static ErstantwortContext context() {
    return ErstantwortContext.builder()
        .modality(ErstantwortModality.AGENCY_COUNSELLING)
        .responseDeadlineDays(2)
        .locale("de")
        .build();
  }

  private static Map<String, Object> journey(Map<String, Object>... flight) {
    return Map.of("phases", Map.of("flight", List.of(flight)));
  }

  private static Map<String, Object> greeting() {
    return Map.of("id", "j:agencyGreeting", "kind", "core", "core", "agencyGreeting");
  }

  private static Map<String, Object> panel(String id, String title, String body, boolean safety) {
    var aspect = new LinkedHashMap<String, Object>();
    aspect.put("id", id + ":aspect");
    aspect.put("title", Map.of("de", title, "en", title + " (en)"));
    aspect.put("body", Map.of("de", "<p>" + body + "</p>"));
    if (safety) {
      aspect.put("safety", true);
    }
    return Map.of("id", id, "kind", "panel", "display", "open", "aspects", List.of(aspect));
  }

  private static JsonNode sections(String body) throws Exception {
    assertThat(body).startsWith(ErstantwortPayloadBuilder.SYSTEM_NOTIFICATION_PREFIX);
    var payload =
        MAPPER.readTree(
            body.substring(ErstantwortPayloadBuilder.SYSTEM_NOTIFICATION_PREFIX.length()));
    assertThat(payload.path("type").asText()).isEqualTo("FIRST_RESPONSE");
    assertThat(payload.path("version").asInt()).isEqualTo(2);
    assertThat(payload.path("conversationType").asText()).isEqualTo("AGENCY_COUNSELLING");
    return payload.path("sections");
  }

  private static List<String> ids(JsonNode sections) {
    var ids = new ArrayList<String>();
    sections.forEach(section -> ids.add(section.path("id").asText()));
    return ids;
  }

  @Test
  void theGreetingStepSendsTodaysBausteineAsSections() throws Exception {
    var sections = sections(builder.build(journey(greeting()), context()));

    assertThat(ids(sections))
        .startsWith("j:agencyGreeting:greeting")
        .contains("j:agencyGreeting:emergencyNumbers", "j:agencyGreeting:closing");
    assertThat(sections.get(0).path("kind").asText()).isEqualTo("message");
    var emergency = sections.get(ids(sections).indexOf("j:agencyGreeting:emergencyNumbers"));
    assertThat(emergency.path("kind").asText()).isEqualTo("panel");
    assertThat(emergency.path("safety").asBoolean()).isTrue();
    assertThat(emergency.path("body").asText()).contains(EMERGENCY_NUMBER);
    // The platform panel is not added a second time when the greeting already carries it.
    assertThat(ids(sections)).doesNotContain("platform:emergencyNumbers");
  }

  @Test
  void theEMailBausteinBecomesTheFixedEMailModule() throws Exception {
    var sections = sections(builder.build(journey(greeting()), context()));

    var email = sections.get(ids(sections).indexOf("j:agencyGreeting:emailNotification"));
    assertThat(email.path("kind").asText()).isEqualTo("fixed");
    assertThat(email.path("module").asText()).isEqualTo("email");
  }

  /** Frank's demo: new panel in, old greeting out. */
  @Test
  void aPanelThatReplacesTheGreetingIsSentAsWritten() throws Exception {
    var sections =
        sections(
            builder.build(journey(panel("u25:info", "Neu bei U25", "Hallo!", false)), context()));

    var first = sections.get(0);
    assertThat(first.path("id").asText()).isEqualTo("u25:info:aspect");
    assertThat(first.path("kind").asText()).isEqualTo("panel");
    assertThat(first.path("title").asText()).isEqualTo("Neu bei U25");
    assertThat(first.path("body").asText()).isEqualTo("Hallo!");
    assertThat(first.path("display").asText()).isEqualTo("open");
    assertThat(ids(sections)).noneMatch(id -> id.startsWith("j:agencyGreeting"));
  }

  /**
   * Coordinator condition 1: this test fails if a first reply without emergency numbers is sent.
   */
  @Test
  void emergencyNumbersAreAlwaysInTheFirstReply() throws Exception {
    var withoutSafety =
        List.of(
            journey(),
            journey(panel("u25:info", "Neu bei U25", "Hallo!", false)),
            journey(Map.of("id", "m", "kind", "message", "body", Map.of("de", "Nur Text"))),
            journey(greeting()));

    for (var journey : withoutSafety) {
      var body = builder.build(journey, context());
      assertThat(body).contains(EMERGENCY_NUMBER);
      var sections = sections(body);
      assertThat(sections)
          .anySatisfy(
              section -> {
                assertThat(section.path("safety").asBoolean()).isTrue();
                assertThat(section.path("body").asText()).contains(EMERGENCY_NUMBER);
              });
    }
  }

  @Test
  void anOwnSafetyPanelReplacesThePlatformOne() throws Exception {
    var sections =
        sections(
            builder.build(
                journey(panel("u25:sos", "Krise", "Nummer gegen Kummer 116 111", true)),
                context()));

    assertThat(ids(sections)).containsExactly("u25:sos:aspect");
    assertThat(sections.get(0).path("safety").asBoolean()).isTrue();
  }

  @Test
  void onlyStepsSentWithTheGreetingBelongToTheFirstReply() throws Exception {
    var later =
        Map.<String, Object>of(
            "id",
            "later",
            "kind",
            "message",
            "trigger",
            "afterFirstReply",
            "body",
            Map.of("de", "Später"));
    var manual =
        Map.<String, Object>of(
            "id",
            "manual",
            "kind",
            "message",
            "trigger",
            "manual",
            "body",
            Map.of("de", "Manuell"));
    var now =
        Map.<String, Object>of(
            "id",
            "now",
            "kind",
            "message",
            "trigger",
            "withGreeting",
            "body",
            Map.of("de", "<p>Jetzt</p>"));

    var sections = sections(builder.build(journey(later, manual, now), context()));

    assertThat(ids(sections)).containsExactly("now", "platform:emergencyNumbers");
    assertThat(sections.get(0).path("body").asText()).isEqualTo("Jetzt");
  }

  @Test
  void textsAreSentInTheReadersLanguageWithFallback() throws Exception {
    var english =
        ErstantwortContext.builder()
            .modality(ErstantwortModality.AGENCY_COUNSELLING)
            .locale("en")
            .build();
    var turkish =
        ErstantwortContext.builder()
            .modality(ErstantwortModality.AGENCY_COUNSELLING)
            .locale("tr")
            .build();
    var journey = journey(panel("p", "Titel", "Text", false));

    assertThat(sections(builder.build(journey, english)).get(0).path("title").asText())
        .isEqualTo("Titel (en)");
    // No Turkish text: the reader gets English before German, as for the Träger's own texts.
    assertThat(sections(builder.build(journey, turkish)).get(0).path("title").asText())
        .isEqualTo("Titel (en)");
  }

  @Test
  void questionsAndOtherCoreStepsAreNotPartOfThisSlice() throws Exception {
    var enquiry = Map.<String, Object>of("id", "enquiry", "kind", "core", "core", "enquiry");
    var question = Map.<String, Object>of("id", "q", "kind", "question", "questions", List.of());

    var sections = sections(builder.build(journey(enquiry, question), context()));

    assertThat(ids(sections)).containsExactly("platform:emergencyNumbers");
  }

  @Test
  void noJourneyMeansVersionOne() {
    when(journeys.forRegistration(anyLong(), any())).thenReturn(Optional.empty());

    assertThat(builder.buildFor(5L, 1L, context())).isEmpty();
  }

  @Test
  void anUnreadableJourneyMeansVersionOne() {
    when(journeys.forRegistration(anyLong(), any())).thenThrow(new NoSuchElementException("gone"));

    assertThat(builder.buildFor(5L, 1L, context())).isEmpty();
  }

  @Test
  void switchedOffOrWithoutTopicNoJourneyIsRead() {
    assertThat(builder.buildFor(null, 1L, context())).isEmpty();
    ReflectionTestUtils.setField(builder, "enabled", false);
    assertThat(builder.buildFor(5L, 1L, context())).isEmpty();

    verify(journeys, never()).forRegistration(anyLong(), any());
  }

  @Test
  void aJourneyForTheTopicGivesVersionTwo() throws Exception {
    when(journeys.forRegistration(5L, 1L))
        .thenReturn(Optional.of(new PublicJourney("j", journey(greeting()), null)));

    var body = builder.buildFor(5L, 1L, context());

    assertThat(body).isPresent();
    assertThat(sections(body.get()).size()).isGreaterThan(3);
  }
}
