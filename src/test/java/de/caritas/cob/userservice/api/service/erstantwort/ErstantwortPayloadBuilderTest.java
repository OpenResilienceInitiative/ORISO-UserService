package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  /**
   * #1565: the platform must not claim who reads along when nobody has said so. A centre where a
   * supervisor reads along would otherwise tell an advice seeker something untrue, and the person
   * decides what to write based on it. Without a stated text the Baustein is left out, and nothing
   * else about the first reply changes.
   */
  @Test
  void leavesOutWhoReadsAlongWhenNoLevelStatedIt() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().build());

    assertThat(idsOf(payload)).doesNotContain("whoReadsAlong");
    assertNoClaimAboutWhoReadsAlong(payload);
    assertThat(idsOf(payload))
        .containsExactly(
            "greeting",
            "responseDeadline",
            "modalityNote",
            "noPersonalData",
            "emergencyNumbers",
            "dataProtection",
            "emailNotification",
            "accountProtection",
            "closing");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   ", "\n", "\t \n ", "\u00a0"})
  void blankTextAtEveryLevelCountsAsNotStated(String blank) throws Exception {
    JsonNode payload =
        buildFor(
            agencyCounselling()
                .whoReadsAlongByTopic(blank)
                .whoReadsAlongByAgency(blank)
                .whoReadsAlongByTenant(blank)
                .build());

    assertThat(idsOf(payload)).doesNotContain("whoReadsAlong");
    assertNoClaimAboutWhoReadsAlong(payload);
  }

  @Test
  void leavesNoBlankBodyHeadlineOrPlaceholderBehindWhenTheSentenceIsDropped() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().whoReadsAlongByTenant("  ").build());

    for (JsonNode baustein : payload.get("bausteine")) {
      assertThat(baustein.get("body").asText()).isNotBlank();
      assertThat(baustein.get("body").isNull()).isFalse();
      if (baustein.has("headline")) {
        assertThat(baustein.get("headline").asText()).isNotBlank();
      }
    }
    assertThat(payload.toString())
        .doesNotContain("Wer Ihre Nachricht liest", "Wer Deine Nachricht liest", "null", "{{");
  }

  @Test
  void stillSendsTheRestOfTheFirstReplyWithoutIt() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().responseDeadlineDays(5).build());

    assertThat(bodyOf(payload, "responseDeadline")).contains("5 Werktagen");
    assertThat(idsOf(payload))
        .contains("greeting", "noPersonalData", "emergencyNumbers", "closing");
  }

  @Test
  void aTenantTextIsShownWhenNoMoreSpecificLevelHasOne() throws Exception {
    JsonNode payload =
        buildFor(
            agencyCounselling()
                .whoReadsAlongByTenant("  Unser Team und die Supervision.  ")
                .build());

    assertThat(bodyOf(payload, "whoReadsAlong")).isEqualTo("Unser Team und die Supervision.");
    assertThat(bausteinOf(payload, "whoReadsAlong").get("headline").asText())
        .isEqualTo("Wer Ihre Nachricht liest");
  }

  @Test
  void anAgencyTextIsShownWhenNoTopicTextExistsAndBeatsTheTenant() throws Exception {
    JsonNode payload =
        buildFor(
            agencyCounselling()
                .whoReadsAlongByTopic(" ")
                .whoReadsAlongByAgency("Nur unser Team.")
                .whoReadsAlongByTenant("Träger-Text")
                .build());

    assertThat(bodyOf(payload, "whoReadsAlong")).isEqualTo("Nur unser Team.");
  }

  @Test
  void aTopicTextBeatsAgencyAndTenant() throws Exception {
    JsonNode payload =
        buildFor(
            agencyCounselling()
                .whoReadsAlongByTopic("Fachbereich")
                .whoReadsAlongByAgency("Beratungsstelle")
                .whoReadsAlongByTenant("Träger")
                .build());

    assertThat(bodyOf(payload, "whoReadsAlong")).isEqualTo("Fachbereich");
  }

  @Test
  void anAuthoredTextStaysAfterTheGreetingAndBeforeTheDeadline() throws Exception {
    JsonNode payload = buildFor(agencyCounselling().whoReadsAlongByTenant("Team").build());

    assertThat(idsOf(payload).subList(0, 3))
        .containsExactly("greeting", "whoReadsAlong", "responseDeadline");
  }

  private static void assertNoClaimAboutWhoReadsAlong(JsonNode payload) {
    assertThat(payload.toString())
        .doesNotContain("ausschließlich die Fachkräfte", "Nachricht lesen", "Wer Ihre Nachricht");
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

  @Test
  void tenantTextsReplaceDefaultsAndEmergencyAdditionIsItsOwnBaustein() throws Exception {
    JsonNode payload =
        buildFor(
            agencyCounselling()
                .locale("de")
                .greetingByTenant("Moin!")
                .closingByTenant("Tschüss")
                .freeNoticeByTenant("Wir sind keine Chat-Beratung.")
                .emergencyAdditionByTenant("Hausnotruf 0123")
                .responseDeadlineDays(5)
                .build());
    String all = payload.get("bausteine").toString();
    assertThat(all).contains("Moin!").contains("Tschüss").contains("keine Chat-Beratung");
    assertThat(all).contains("innerhalb von 5 Werktagen");
    // The fixed bubble stays exactly as the platform wrote it ...
    assertThat(bodyOf(payload, "emergencyNumbers"))
        .contains("0800 111 0 111")
        .doesNotContain("Hausnotruf");
    // ... and the addition travels in its own Baustein, with a question label for the FAQ group.
    var addition = bausteinOf(payload, "emergencyAddition");
    assertThat(addition.get("body").asText()).isEqualTo("Hausnotruf 0123");
    assertThat(addition.get("headline").asText()).isNotBlank();
    // Placed with the FAQ group (after dataProtection), before the free notice.
    var ids = idsOf(payload);
    assertThat(ids.indexOf("emergencyAddition")).isGreaterThan(ids.indexOf("dataProtection"));
    assertThat(ids.indexOf("emergencyAddition")).isLessThan(ids.indexOf("freeNotice"));
    for (JsonNode b : payload.get("bausteine")) {
      assertThat(b.get("body").asText()).doesNotContain("<p>");
    }
  }

  @Test
  void emergencyAdditionStaysVerbatimAndGetsALocalizedHeadlineInEveryLocale() throws Exception {
    for (var locale : List.of("de", "de@informal", "en", "fr", "ru", "ti", "tr")) {
      var payload =
          buildFor(agencyCounselling().locale(locale).emergencyAdditionByTenant("XYZ 42").build());
      var addition = bausteinOf(payload, "emergencyAddition");
      assertThat(addition.get("body").asText()).as(locale).isEqualTo("XYZ 42");
      assertThat(addition.get("headline").asText()).as(locale).isNotBlank();
    }
  }

  @Test
  void blankEmergencyAdditionChangesNothing() throws Exception {
    String with = buildFor(agencyCounselling().emergencyAdditionByTenant("  ").build()).toString();
    String without = buildFor(agencyCounselling().build()).toString();
    assertThat(with).isEqualTo(without);
  }
}
