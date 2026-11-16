package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursWindowDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarWorkingHoursServiceTest {

  private static final String ME = "me";

  @Mock CalendarWorkingHoursRepository repository;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private CalendarWorkingHoursService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    service = new CalendarWorkingHoursService(caller, repository);
  }

  @Test
  void workingHours_Should_BeEmpty_When_NoneAreSet() {
    when(repository.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(ME))
        .thenReturn(List.of());

    assertThat(service.workingHours().getWindows()).isEmpty();
  }

  @Test
  void workingHours_Should_GiveTheStoredWindowsAsHoursAndMinutes() {
    when(repository.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(ME))
        .thenReturn(List.of(row(1, "07:30", "12:00"), row(1, "13:00", "20:00")));

    assertThat(service.workingHours().getWindows())
        .containsExactly(window(1, "07:30", "12:00"), window(1, "13:00", "20:00"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void replace_Should_RemoveTheOldWindowsFirst_AndStoreTheNewOnesInOrder() {
    var saved = ArgumentCaptor.forClass(Iterable.class);

    var result =
        service.replace(
            request(
                window(5, "08:00", "14:00"),
                window(1, "13:00", "20:00"),
                window(1, "07:00", "12:00")));

    var order = inOrder(repository);
    order.verify(repository).deleteByConsultantId(ME);
    order.verify(repository).saveAll(saved.capture());
    var rows = new ArrayList<CalendarWorkingHours>();
    saved.getValue().forEach(row -> rows.add((CalendarWorkingHours) row));
    assertThat(rows).extracting(CalendarWorkingHours::getConsultantId).containsOnly(ME).hasSize(3);
    assertThat(rows)
        .extracting(CalendarWorkingHours::getStartTime)
        .contains(LocalTime.of(8, 0), LocalTime.of(13, 0), LocalTime.of(7, 0));
    assertThat(result.getWindows())
        .containsExactly(
            window(1, "07:00", "12:00"), window(1, "13:00", "20:00"), window(5, "08:00", "14:00"));
  }

  @Test
  void replace_Should_RemoveEverything_When_TheListIsEmpty() {
    var result = service.replace(request());

    verify(repository).deleteByConsultantId(ME);
    assertThat(result.getWindows()).isEmpty();
  }

  @Test
  void replace_Should_AcceptFourteenWindows_AndTheWholeDay() {
    var windows = new CalendarWorkingHoursWindowDTO[CalendarWorkingHoursService.MAX_WINDOWS];
    for (var i = 0; i < windows.length; i++) {
      windows[i] = window(i % 7, i < 7 ? "00:00" : "12:00", i < 7 ? "11:59" : "23:59");
    }

    assertThat(service.replace(request(windows)).getWindows()).hasSize(14);
  }

  @Test
  void replace_Should_RefuseInvalidWindows_WithoutTouchingTheStoredOnes() {
    var fifteen = new CalendarWorkingHoursWindowDTO[CalendarWorkingHoursService.MAX_WINDOWS + 1];
    Arrays.fill(fifteen, window(1, "08:00", "09:00"));
    for (var invalid :
        List.of(
            request(fifteen),
            request(window(1, "09:00", "09:00")),
            request(window(1, "17:00", "08:00")),
            request(window(7, "08:00", "17:00")),
            request(window(-1, "08:00", "17:00")),
            request(window(null, "08:00", "17:00")),
            request(window(1, "8:00", "17:00")),
            request(window(1, "08:00", "24:00")),
            request(window(1, "08:00", "17:60")),
            request(window(1, null, "17:00")),
            request(window(1, "08:00:00", "17:00")),
            request(window(2, "08:00", "12:00"), null),
            new CalendarWorkingHoursDTO().windows(null))) {
      assertThatThrownBy(() -> service.replace(invalid))
          .as(String.valueOf(invalid))
          .isInstanceOf(BadRequestException.class);
    }
    assertThatThrownBy(() -> service.replace(null)).isInstanceOf(BadRequestException.class);
    verify(repository, never()).deleteByConsultantId(any());
    verify(repository, never()).saveAll(any());
  }

  @Test
  void anAsker_Should_NeitherReadNorSetWorkingHours() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(() -> service.workingHours()).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.replace(request(window(1, "08:00", "17:00"))))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(repository);
  }

  private static CalendarWorkingHoursDTO request(CalendarWorkingHoursWindowDTO... windows) {
    return new CalendarWorkingHoursDTO().windows(new ArrayList<>(Arrays.asList(windows)));
  }

  private static CalendarWorkingHoursWindowDTO window(Integer weekday, String start, String end) {
    return new CalendarWorkingHoursWindowDTO().weekday(weekday).start(start).end(end);
  }

  private static CalendarWorkingHours row(int weekday, String start, String end) {
    var row = new CalendarWorkingHours();
    row.setConsultantId(ME);
    row.setWeekday(weekday);
    row.setStartTime(LocalTime.parse(start));
    row.setEndTime(LocalTime.parse(end));
    return row;
  }
}
