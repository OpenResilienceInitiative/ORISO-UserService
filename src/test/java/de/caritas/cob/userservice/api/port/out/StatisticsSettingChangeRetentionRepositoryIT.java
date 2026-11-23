package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes the retention query of {@link StatisticsSettingChangeRepository} against the H2 testing
 * schema. The protocol rows themselves must survive: the latest row per setting is its current
 * value, and the row count is the revision the settings screen saves against.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class StatisticsSettingChangeRetentionRepositoryIT {

  private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 1, 1, 0, 0);

  @Autowired private StatisticsSettingChangeRepository underTest;

  @Test
  void anonymizeActorBeforeShouldBlankOnlyTheActorOfRowsBeforeTheCutoff() {
    save("old", CUTOFF.minusDays(1), "admin-1", "Alice Admin");
    save("onCutoff", CUTOFF, "admin-2", "Bob Admin");
    save("recent", CUTOFF.plusDays(10), "admin-3", "Carol Admin");

    assertThat(underTest.anonymizeActorBefore(CUTOFF)).isEqualTo(1);

    assertThat(underTest.count()).isEqualTo(3);
    var old = byValue("old");
    assertThat(old.getChangedById()).isNull();
    assertThat(old.getChangedByName()).isNull();
    assertThat(old.getSettingKey()).isEqualTo("messageCountTracking");
    assertThat(old.getOldValue()).isEqualTo("ENABLED");
    assertThat(old.getNewValue()).isEqualTo("old");
    assertThat(old.getEffectiveFrom()).isEqualTo(CUTOFF.minusDays(1));
    assertThat(byValue("onCutoff").getChangedByName()).isEqualTo("Bob Admin");
    assertThat(byValue("recent").getChangedById()).isEqualTo("admin-3");
  }

  @Test
  void anonymizeActorBeforeShouldLeaveRowsWithoutActorAlone() {
    save("system", CUTOFF.minusDays(400), null, null);

    assertThat(underTest.anonymizeActorBefore(CUTOFF)).isZero();
  }

  private StatisticsSettingChange byValue(String value) {
    return underTest.findAll().stream()
        .filter(change -> value.equals(change.getNewValue()))
        .findFirst()
        .orElseThrow();
  }

  private void save(String value, LocalDateTime effectiveFrom, String id, String name) {
    underTest.save(
        StatisticsSettingChange.builder()
            .settingKey("messageCountTracking")
            .oldValue("ENABLED")
            .newValue(value)
            .changedById(id)
            .changedByName(name)
            .effectiveFrom(effectiveFrom)
            .build());
  }
}
