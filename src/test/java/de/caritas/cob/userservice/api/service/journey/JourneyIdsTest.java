package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.mail;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.phase;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JourneyIdsTest {

  @Test
  void issuesAnIdOfTheSameKindThatIsNotTaken() {
    var taken = new HashSet<>(Set.of("step-000000000000"));
    var id = JourneyIds.issue("new:step-lq3x-4", taken);
    assertThat(id).matches("step-[0-9a-z]{12}").isNotEqualTo("step-000000000000");
    assertThat(taken).contains(id);
    assertThat(JourneyRules.ID.matcher(id).matches()).isTrue();
    assertThat(JourneyIds.issue("new:%%%", new HashSet<>())).startsWith("id-");
  }

  @Test
  void replacesEveryDraftIdAndTheReferencesToIt() {
    var journey = mail("new:journey-1");
    var question = new LinkedHashMap<String, Object>();
    question.put("id", "new:question-1");
    question.put("prototypeId", "new:question-1");
    var asked = step("new:step-1", "question", null);
    asked.put("questions", new ArrayList<>(List.of(question)));
    phase(journey, "flight").add(asked);

    var issued = JourneyIds.issueAll(journey, new HashSet<>());

    assertThat(issued).containsKeys("new:journey-1", "new:step-1", "new:question-1");
    assertThat(journey.get("id")).isEqualTo(issued.get("new:journey-1"));
    assertThat(question.get("id")).isEqualTo(issued.get("new:question-1"));
    assertThat(question.get("prototypeId")).isEqualTo(question.get("id"));
    // The shipped steps of the new journey ("new:journey-1:topic") are drafts as well.
    assertThat(JourneyRules.stepIds(journey)).noneMatch(JourneyIds::isDraft);
  }

  @Test
  void leavesStoredIdsAlone() {
    var journey = mail("j1");
    var before = JourneyRules.stepIds(journey);
    assertThat(JourneyIds.issueAll(journey, new HashSet<>())).isEmpty();
    assertThat(JourneyRules.stepIds(journey)).isEqualTo(before);
  }

  @Test
  void issuesIdsInsideALayer() {
    Map<String, Object> phases = new LinkedHashMap<>();
    phases.put("flight", new ArrayList<>(List.of(step("new:step-9", "message", null))));
    JourneyIds.issueAll(phases, new HashSet<>());
    var ids = JourneyRules.stepIds(Map.of("phases", phases));
    assertThat(ids).singleElement().satisfies(id -> assertThat(id).matches("step-[0-9a-z]{12}"));
  }
}
