package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-018 / ORISO-UserService#926.
 *
 * <p>Two properties carry all the weight here and each has its own reason:
 *
 * <ul>
 *   <li><b>Frozen words.</b> The resolved wording goes into the event, so what was said to a person
 *       stays provable and a later configuration change cannot rewrite history.
 *   <li><b>No completion state.</b> An action carries only its kind. Whether it is already done is
 *       read live by the client — storing it would be exactly the new state ADR-018 §4 forbids.
 * </ul>
 */
class ErstantwortPayloadBuilderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ErstantwortPayloadBuilder builder = new ErstantwortPayloadBuilder();

  private JsonNode buildFor(ErstantwortContext context) throws Exception {
    String body = builder.buildFirstResponseBody(context);
    assertThat(body).startsWith("[SYSTEM_NOTIFICATION]");
    return MAPPER.readTree(body.substring("[SYSTEM_NOTIFICATION]".length()));
  }

  private ErstantwortContext.ErstantwortContextBuilder agencyCounselling() {
    return ErstantwortContext.builder()
        .modality(ErstantwortModality.AGENCY_COUNSELLING)
        .responseDeadlineDays(2);
  }

  @Test
  void buildsAVersionedFirstResponseEnvelope() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().build());

    assertThat(payload.get("type").asText()).isEqualTo("FIRST_RESPONSE");
    assertThat(payload.get("version").asInt()).isEqualTo(1);
    assertThat(payload.get("bausteine").isArray()).isTrue();
  }

  @Test
  void freezesTheResolvedWordingRatherThanAKey() throws Exception {
    JsonNode bausteine = buildFor(agencyCounselling().build()).get("bausteine");

    for (JsonNode baustein : bausteine) {
      assertThat(baustein.get("id").asText()).isNotBlank();
      assertThat(baustein.get("body").asText()).isNotBlank();
      // A key would defeat the point: the wording has to be readable years later
      // without the frontend that produced it.
      assertThat(baustein.get("body").asText()).doesNotContain("erstantwort.");
    }
  }

  @Test
  void neverStoresCompletionState() throws Exception {
    JsonNode bausteine = buildFor(agencyCounselling().build()).get("bausteine");

    for (JsonNode baustein : bausteine) {
      assertThat(baustein.has("done")).isFalse();
      assertThat(baustein.has("completed")).isFalse();
      JsonNode action = baustein.get("action");
      if (action != null && !action.isNull()) {
        assertThat(action.has("done")).isFalse();
        assertThat(action.get("kind").asText()).isNotBlank();
        assertThat(action.get("label").asText()).isNotBlank();
      }
    }
  }

  @Test
  void rendersTheDeadlineNumberIntoTheDerivedWording() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().responseDeadlineDays(5).build());

    assertThat(bodyOf(payload, "responseDeadline")).contains("5 Werktagen");
  }

  @Test
  void fallsBackToThePlatformDeadlineWhenNoneIsConfigured() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().responseDeadlineDays(null).build());

    assertThat(bodyOf(payload, "responseDeadline")).contains("2 Werktagen");
  }

  @Test
  void omitsTeamAndDeadlineBausteineInLiveChat() throws Exception {
    JsonNode payload =
        buildFor(ErstantwortContext.builder().modality(ErstantwortModality.LIVE_CHAT).build());

    assertThat(idsOf(payload)).doesNotContain("whoReadsAlong", "responseDeadline");
    // The two safety-bearing Bausteine apply everywhere, Live Chat included.
    assertThat(idsOf(payload)).contains("noPersonalData", "emergencyNumbers");
  }

  @Test
  void resolvesAuthoredTextThroughTheChainWithTheMostSpecificLevelWinning() throws Exception {
    JsonNode payload =
        buildFor(
            agencyCounselling()
                .greetingByTenant("Träger-Gruß")
                .greetingByAgency("Beratungsstellen-Gruß")
                .greetingByTopic("Fachbereichs-Gruß")
                .build());

    assertThat(bodyOf(payload, "greeting")).isEqualTo("Fachbereichs-Gruß");
  }

  @Test
  void fallsBackDownTheChainWhenTheSpecificLevelIsEmpty() throws Exception {
    assertThat(
            bodyOf(
                buildFor(agencyCounselling().greetingByTenant("Träger-Gruß").build()), "greeting"))
        .isEqualTo("Träger-Gruß");
    assertThat(
            bodyOf(
                buildFor(
                    agencyCounselling()
                        .greetingByAgency("Stelle")
                        .greetingByTenant("Träger")
                        .build()),
                "greeting"))
        .isEqualTo("Stelle");
    // Nothing authored at any level: the platform text is the last link.
    assertThat(bodyOf(buildFor(agencyCounselling().build()), "greeting"))
        .contains("Ihre Nachricht ist bei uns angekommen");
  }

  @Test
  void treatsABlankAuthoredTextAsAbsentRatherThanAsAnEmptyBaustein() throws Exception {
    JsonNode payload =
        buildFor(agencyCounselling().greetingByTopic("   ").greetingByTenant("Träger").build());

    assertThat(bodyOf(payload, "greeting")).isEqualTo("Träger");
  }

  @Test
  void omitsTheFreeNoticeUntilAgencyAuthorsIt() throws Exception {
    assertThat(idsOf(buildFor(agencyCounselling().build()))).doesNotContain("freeNotice");

    JsonNode withNotice =
        buildFor(agencyCounselling().freeNoticeByTenant("Wir beraten im Peer-Modell.").build());
    assertThat(bodyOf(withNotice, "freeNotice")).isEqualTo("Wir beraten im Peer-Modell.");
  }

  @Test
  void rendersDerivedLinkTargetsFromConfigurationRatherThanFromText() throws Exception {
    JsonNode payload =
        buildFor(
            agencyCounselling()
                .dataPrivacyUrl("https://u25.test/datenschutz")
                .imprintUrl("https://u25.test/impressum")
                .build());

    JsonNode links = bausteinOf(payload, "dataProtection").get("links");
    assertThat(links).hasSize(2);
    assertThat(links.get(0).get("url").asText()).isEqualTo("https://u25.test/datenschutz");
  }

  @Test
  void omitsLinksEntirelyWhenTheDepartmentHasNoneConfigured() throws Exception {
    assertThat(bausteinOf(buildFor(agencyCounselling().build()), "dataProtection").has("links"))
        .isFalse();
  }

  @Test
  void keepsTheSafetyBausteineAheadOfEveryOptionalAction() throws Exception {
    List<String> ids = idsOf(buildFor(agencyCounselling().build()));

    assertThat(ids.indexOf("noPersonalData")).isLessThan(ids.indexOf("emailNotification"));
    assertThat(ids.indexOf("emergencyNumbers")).isLessThan(ids.indexOf("emailNotification"));
  }

  @Test
  void speaksWithoutGenderedTerms() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().build());

    for (JsonNode baustein : payload.get("bausteine")) {
      String text = baustein.toString();
      assertThat(text).doesNotContainIgnoringCase("Berater");
      assertThat(text).doesNotContain("_innen").doesNotContain(":innen").doesNotContain("*in ");
    }
  }

  @Test
  void writesTheInformalGermanVariantWhenTheTenantIsInformal() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().informal(true).build());

    assertThat(bodyOf(payload, "greeting")).contains("Du");
    assertThat(bodyOf(payload, "greeting")).doesNotContain("Sie sich");
  }

  private static JsonNode bausteinOf(JsonNode payload, String id) {
    for (JsonNode baustein : payload.get("bausteine")) {
      if (id.equals(baustein.get("id").asText())) {
        return baustein;
      }
    }
    throw new AssertionError("no Baustein with id " + id + " in " + payload);
  }

  private static String bodyOf(JsonNode payload, String id) {
    return bausteinOf(payload, id).get("body").asText();
  }

  private static List<String> idsOf(JsonNode payload) {
    return payload.get("bausteine").findValuesAsText("id");
  }
}
