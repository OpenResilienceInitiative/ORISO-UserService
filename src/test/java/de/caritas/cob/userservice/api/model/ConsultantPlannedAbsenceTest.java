package de.caritas.cob.userservice.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** ORISO-Frontend#2303: a planned absence applies on the days of its range only. */
class ConsultantPlannedAbsenceTest {

  private static final LocalDate START = LocalDate.of(2026, 12, 21);
  private static final LocalDate END = LocalDate.of(2027, 1, 3);

  private static Consultant consultant(boolean switchedOn, LocalDate start, LocalDate end) {
    var consultant = new Consultant();
    consultant.setAbsent(switchedOn);
    consultant.setAbsenceStart(start);
    consultant.setAbsenceEnd(end);
    return consultant;
  }

  @Test
  void anAbsenceWithoutDatesLastsUntilSwitchedOff() {
    var consultant = consultant(true, null, null);

    assertThat(consultant.isAbsentOn(LocalDate.of(2020, 1, 1))).isTrue();
    assertThat(consultant.isAbsentOn(LocalDate.of(2099, 1, 1))).isTrue();
  }

  @Test
  void aSwitchedOffAbsenceNeverApplies() {
    var consultant = consultant(false, START, END);

    assertThat(consultant.isAbsentOn(START)).isFalse();
  }

  @Test
  void aRangeAppliesFromItsFirstToItsLastDayInclusive() {
    var consultant = consultant(true, START, END);

    assertThat(consultant.isAbsentOn(START.minusDays(1))).isFalse();
    assertThat(consultant.isAbsentOn(START)).isTrue();
    assertThat(consultant.isAbsentOn(END)).isTrue();
    assertThat(consultant.isAbsentOn(END.plusDays(1))).isFalse();
  }

  @Test
  void anOpenStartAppliesNowUntilTheEnd() {
    var consultant = consultant(true, null, END);

    assertThat(consultant.isAbsentOn(START.minusYears(1))).isTrue();
    assertThat(consultant.isAbsentOn(END.plusDays(1))).isFalse();
  }

  @Test
  void anOpenEndAppliesFromTheStartUntilSwitchedOff() {
    var consultant = consultant(true, START, null);

    assertThat(consultant.isAbsentOn(START.minusDays(1))).isFalse();
    assertThat(consultant.isAbsentOn(START.plusYears(5))).isTrue();
  }

  @Test
  void anAbsenceIsOverOnlyAfterItsEnd() {
    assertThat(consultant(true, START, END).isAbsenceOverOn(END)).isFalse();
    assertThat(consultant(true, START, END).isAbsenceOverOn(END.plusDays(1))).isTrue();
    assertThat(consultant(true, START, null).isAbsenceOverOn(END.plusYears(1))).isFalse();
    assertThat(consultant(false, START, END).isAbsenceOverOn(START)).isTrue();
  }
}
