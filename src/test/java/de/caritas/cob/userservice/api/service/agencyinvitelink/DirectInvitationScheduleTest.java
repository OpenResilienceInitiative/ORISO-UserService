package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectInvitationScheduleTest {

  private static final LocalDateTime START = LocalDateTime.of(2030, 3, 4, 14, 30);
  private static final LocalDateTime END = LocalDateTime.of(2030, 3, 4, 15, 15);

  @Test
  void expiresOneDayAfterTheEnd_When_NoValidityIsGiven() {
    var schedule = DirectInvitationSchedule.of(START, END, null);

    assertThat(schedule.validityAfterEndMinutes()).isEqualTo(1440);
    assertThat(schedule.expiresAt()).isEqualTo(LocalDateTime.of(2030, 3, 5, 15, 15));
  }

  @Test
  void expiresAtTheEnd_When_ValidityIsZero() {
    assertThat(DirectInvitationSchedule.of(START, END, 0).expiresAt()).isEqualTo(END);
  }

  @Test
  void expiresTwoDaysAfterTheEnd_AtTheLongestValidity() {
    assertThat(DirectInvitationSchedule.of(START, END, 2880).expiresAt())
        .isEqualTo(LocalDateTime.of(2030, 3, 6, 15, 15));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2881})
  void refusesAValidityOutsideZeroToFortyEightHours(int minutes) {
    assertThatThrownBy(() -> DirectInvitationSchedule.of(START, END, minutes))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void refusesAnEndThatIsNotAfterTheStart() {
    assertThatThrownBy(() -> DirectInvitationSchedule.of(START, START, 60))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void opensTheCallFifteenMinutesBeforeTheStart() {
    assertThat(DirectInvitationSchedule.of(START, END, 60).callOpensAt())
        .isEqualTo(LocalDateTime.of(2030, 3, 4, 14, 15));
  }

  @Test
  void callIsClosed_Until_FifteenMinutesBeforeTheStart() {
    var schedule = DirectInvitationSchedule.of(START, END, 60);

    assertThat(schedule.callOpen(LocalDateTime.of(2030, 3, 4, 14, 10))).isFalse();
    assertThat(schedule.callOpen(LocalDateTime.of(2030, 3, 4, 14, 15))).isTrue();
    assertThat(schedule.callOpen(LocalDateTime.of(2030, 3, 4, 16, 0))).isTrue();
  }

  @Test
  void renewalCountsTheValidityFromNow_When_TheEndHasPassed() {
    var schedule = DirectInvitationSchedule.of(START, END, 60);

    assertThat(schedule.renewedExpiry(LocalDateTime.of(2030, 3, 4, 15, 0)))
        .isEqualTo(LocalDateTime.of(2030, 3, 4, 16, 15));
    assertThat(schedule.renewedExpiry(LocalDateTime.of(2030, 3, 7, 9, 0)))
        .isEqualTo(LocalDateTime.of(2030, 3, 7, 10, 0));
  }
}
