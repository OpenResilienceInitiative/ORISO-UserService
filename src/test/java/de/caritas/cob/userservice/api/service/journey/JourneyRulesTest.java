package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.link;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.links;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.mail;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.phase;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The server keeps the rules the Admin keeps (ADR-036 decisions 36–39). */
class JourneyRulesTest {

  @Test
  void acceptsTheJourneyTheAdminSends() {
    assertThatCode(() -> JourneyRules.checkJourney("j1", mail("j1"))).doesNotThrowAnyException();
  }

  @Test
  void refusesAJourneyWhoseIdDiffersFromThePath() {
    assertThatThrownBy(() -> JourneyRules.checkJourney("other", mail("j1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAMessageInTheCheckIn() {
    var journey = mail("j1");
    phase(journey, "departure").add(step("m", "message", null));
    assertThatThrownBy(() -> JourneyRules.checkJourney("j1", journey))
        .hasMessageContaining("check-in");
  }

  @Test
  void refusesThePostcodeAfterTheCentre() {
    var journey = mail("j1");
    var departure = phase(journey, "departure");
    var postcode = departure.remove(1);
    departure.add(2, postcode);
    assertThatThrownBy(() -> JourneyRules.checkJourney("j1", journey))
        .hasMessageContaining("postcode");
  }

  @Test
  void refusesAStepBeforeTheTopic() {
    var journey = mail("j1");
    phase(journey, "departure").add(0, step("age", "addon", null));
    assertThatThrownBy(() -> JourneyRules.checkJourney("j1", journey))
        .hasMessageContaining("topic");
  }

  @Test
  void refusesARegistrationRouteForLiveChat() {
    var journey = mail("j1");
    journey.put("conversationType", "LIVE_CHAT");
    assertThatThrownBy(() -> JourneyRules.checkJourney("j1", journey))
        .hasMessageContaining("registration");
  }

  @Test
  void refusesACheckInForAGroup() {
    var journey = mail("j1");
    journey.put("conversationType", "INTERNAL_GROUP");
    ((Map<String, Object>) journey.get("routes")).put("registration", List.of());
    assertThatThrownBy(() -> JourneyRules.checkJourney("j1", journey))
        .hasMessageContaining("no check-in");
  }

  @Test
  void refusesAStepIdUsedTwice() {
    var journey = mail("j1");
    phase(journey, "arrival").add(step("j1:enquiry", "question", null));
    assertThatThrownBy(() -> JourneyRules.checkJourney("j1", journey))
        .hasMessageContaining("used twice");
  }

  @Test
  void letsALowerLevelChangeOnBoardAndArrivalOnly() {
    assertThatCode(
            () -> JourneyRules.checkLayer(Map.of("flight", List.of(step("q", "question", null)))))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> JourneyRules.checkLayer(Map.of("departure", List.of())))
        .hasMessageContaining("on board and arrival only");
  }

  @Test
  void givesNewLinksAFreeSlugAndKeepsSavedOnes() {
    var stored = mail("j1", link("a", "Sucht"));
    JourneyRules.fixSlugs(stored, null, Set.of());
    assertThat(links(stored).get(0)).containsEntry("slug", "sucht");

    var renamed = mail("j1", link("a", "Glücksspiel"), link("b", "Sucht"));
    JourneyRules.fixSlugs(renamed, stored, Set.of("sucht-2"));
    assertThat(links(renamed).get(0)).containsEntry("slug", "sucht");
    assertThat(links(renamed).get(1)).containsEntry("slug", "sucht-3");
  }

  @Test
  void refusesToChangeASavedAddress() {
    var stored = mail("j1", link("a", "Sucht"));
    JourneyRules.fixSlugs(stored, null, Set.of());
    var changed = mail("j1", link("a", "Sucht"));
    links(changed).get(0).put("slug", "anders");
    assertThatThrownBy(() -> JourneyRules.fixSlugs(changed, stored, Set.of()))
        .hasMessageContaining("cannot change");
  }

  @Test
  void refusesAnAddressAnotherJourneyHas() {
    var journey = mail("j1", link("a", "Sucht"));
    links(journey).get(0).put("slug", "sucht");
    assertThatThrownBy(() -> JourneyRules.fixSlugs(journey, null, Set.of("sucht")))
        .hasMessageContaining("already used");
  }

  @Test
  void makesReadableSlugs() {
    assertThat(JourneyRules.slugOf("Über 25 – Sucht")).isEqualTo("ueber-25-sucht");
    assertThat(JourneyRules.slugOf("")).isEqualTo("link");
  }
}
