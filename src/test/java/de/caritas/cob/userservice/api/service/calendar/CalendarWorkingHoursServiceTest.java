package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingDayExceptionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingDayExceptionWindowsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO.HolidayRegionEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursWindowDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingTimeWindowDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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

  /** 23:30 UTC is already the next day in Berlin: "today" is 15 November there. */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-14T23:30:00Z"), ZoneOffset.UTC);

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

  @Mock CalendarWorkingHoursRepository repository;
  @Mock CalendarWorkingHoursSettingRepository settings;
  @Mock CalendarWorkingDayExceptionRepository exceptions;
  @Mock ConsultantRepository consultants;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private CalendarWorkingHoursService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    lenient().when(consultants.lockConsultant(ME)).thenReturn(Optional.of(ME));
    service =
        new CalendarWorkingHoursService(
            caller, repository, settings, exceptions, consultants, CLOCK, "Europe/Berlin");
  }

  @Test
  void workingHours_Should_BeEmpty_When_NoneAreSet() {
    when(repository.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(ME))
        .thenReturn(List.of());

    var result = service.workingHours();

    assertThat(result.getWindows()).isEmpty();
    assertThat(result.getHolidayRegion()).isNull();
    assertThat(result.getExceptions()).isEmpty();
  }

  @Test
  void workingHours_Should_GiveTheStoredWindowsAsHoursAndMinutes() {
    when(repository.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(ME))
        .thenReturn(List.of(row(1, "07:30", "12:00"), row(1, "13:00", "20:00")));

    assertThat(service.workingHours().getWindows())
        .containsExactly(window(1, "07:30", "12:00"), window(1, "13:00", "20:00"));
  }

  @Test
  void workingHours_Should_GiveTheRegion_AndTheExceptionsFrom62DaysAgoByDateAndStart() {
    when(settings.findById(ME)).thenReturn(Optional.of(setting("BY")));
    var later = TODAY.plusDays(3);
    var earlier = TODAY.minusDays(62);
    when(exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(ME, earlier))
        .thenReturn(
            List.of(
                exceptionRow(later, "13:00", "17:00"),
                exceptionRow(earlier, null, null),
                exceptionRow(later, "08:00", "12:00")));

    var result = service.workingHours();

    assertThat(result.getHolidayRegion()).isEqualTo(HolidayRegionEnum.BY);
    assertThat(result.getExceptions())
        .containsExactly(
            exception(earlier),
            exception(later, dayWindow("08:00", "12:00"), dayWindow("13:00", "17:00")));
  }

  @Test
  void workingHours_Should_GiveAtMost400Exceptions() {
    var from = TODAY.minusDays(62);
    var rows = new ArrayList<CalendarWorkingDayException>();
    for (var i = 0; i <= CalendarWorkingHoursService.MAX_EXCEPTIONS; i++) {
      rows.add(exceptionRow(from.plusDays(i), "08:00", "09:00"));
    }
    when(exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(ME, from)).thenReturn(rows);

    var result = service.workingHours().getExceptions();

    assertThat(result).hasSize(CalendarWorkingHoursService.MAX_EXCEPTIONS);
    assertThat(result.getFirst().getDate()).isEqualTo(from);
    assertThat(result.getLast().getDate()).isEqualTo(from.plusDays(399));
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
  void replace_Should_StoreTheRegion_AndGiveItBack() {
    var saved = ArgumentCaptor.forClass(CalendarWorkingHoursSetting.class);

    var result =
        service.replace(request(window(1, "08:00", "17:00")).holidayRegion(HolidayRegionEnum.BY));

    verify(settings).save(saved.capture());
    assertThat(saved.getValue().getConsultantId()).isEqualTo(ME);
    assertThat(saved.getValue().getHolidayRegion()).isEqualTo("BY");
    assertThat(result.getHolidayRegion()).isEqualTo(HolidayRegionEnum.BY);
  }

  @Test
  void replace_Should_ClearAStoredRegion_When_TheRequestHasNone() {
    var stored = setting("NW");
    when(settings.findById(ME)).thenReturn(Optional.of(stored));

    var result = service.replace(request(window(1, "08:00", "17:00")));

    verify(settings).save(stored);
    assertThat(stored.getHolidayRegion()).isNull();
    assertThat(result.getHolidayRegion()).isNull();
  }

  @Test
  void replace_Should_StoreNoSettingRow_When_ThereIsNeitherAStoredNorANewRegion() {
    service.replace(request(window(1, "08:00", "17:00")));

    verify(settings, never()).save(any());
  }

  @Test
  void replace_Should_LockTheCounsellor_BeforeReadingOrWritingAnything() {
    service.replace(request(window(1, "08:00", "17:00")).holidayRegion(HolidayRegionEnum.BY));

    var order = inOrder(consultants, repository, settings);
    order.verify(consultants).lockConsultant(ME);
    order.verify(repository).deleteByConsultantId(ME);
    order.verify(settings).findById(ME);
    order.verify(settings).save(any());
  }

  @Test
  void replace_Should_Refuse_When_TheCounsellorHasNoRow() {
    when(consultants.lockConsultant(ME)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replace(request(window(1, "08:00", "17:00"))))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(repository, settings);
  }

  @Test
  void replace_Should_IgnoreTheExceptionsInTheRequest_AndGiveTheStoredOnes() {
    var stored = TODAY.plusDays(1);
    when(exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(ME, TODAY.minusDays(62)))
        .thenReturn(List.of(exceptionRow(stored, null, null)));
    var sent = new ArrayList<CalendarWorkingDayExceptionDTO>();
    sent.add(exception(TODAY.plusDays(2), dayWindow("08:00", "09:00")));

    var result = service.replace(request(window(1, "08:00", "17:00")).exceptions(sent));

    verify(exceptions, never()).saveAll(any());
    verify(exceptions, never()).deleteByConsultantIdAndExceptionDate(any(), any());
    assertThat(result.getExceptions()).containsExactly(exception(stored));
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
    verifyNoInteractions(settings);
  }

  @Test
  @SuppressWarnings("unchecked")
  void replaceException_Should_DropDatesBeforeTheWindow_ReplaceTheDate_AndStoreWindowsByStart() {
    var date = TODAY.plusDays(1);
    var saved = ArgumentCaptor.forClass(Iterable.class);

    var result =
        service.replaceException(
            date, dayRequest(dayWindow("13:00", "17:00"), dayWindow("08:00", "12:00")));

    var order = inOrder(exceptions);
    order.verify(exceptions).deleteByConsultantIdBefore(ME, TODAY.minusDays(62));
    order.verify(exceptions).deleteByConsultantIdAndExceptionDate(ME, date);
    order.verify(exceptions).saveAll(saved.capture());
    var rows = new ArrayList<CalendarWorkingDayException>();
    saved.getValue().forEach(row -> rows.add((CalendarWorkingDayException) row));
    assertThat(rows).hasSize(2);
    assertThat(rows).allMatch(row -> ME.equals(row.getConsultantId()));
    assertThat(rows).allMatch(row -> date.equals(row.getExceptionDate()));
    assertThat(rows)
        .extracting(CalendarWorkingDayException::getStartTime)
        .containsExactly(LocalTime.of(8, 0), LocalTime.of(13, 0));
    assertThat(result)
        .isEqualTo(exception(date, dayWindow("08:00", "12:00"), dayWindow("13:00", "17:00")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void replaceException_Should_StoreOneRowWithoutTimes_ForADayOff() {
    var saved = ArgumentCaptor.forClass(Iterable.class);

    var result = service.replaceException(TODAY, dayRequest());

    verify(exceptions).saveAll(saved.capture());
    var rows = new ArrayList<CalendarWorkingDayException>();
    saved.getValue().forEach(row -> rows.add((CalendarWorkingDayException) row));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getStartTime()).isNull();
    assertThat(rows.getFirst().getEndTime()).isNull();
    assertThat(rows.getFirst().isDayOff()).isTrue();
    assertThat(result).isEqualTo(exception(TODAY));
  }

  @Test
  void replaceException_Should_AcceptFourWindowsThatTouch_AndTheWholeDay() {
    var four =
        dayRequest(
            dayWindow("00:00", "06:00"),
            dayWindow("06:00", "12:00"),
            dayWindow("12:00", "18:00"),
            dayWindow("18:00", "23:59"));

    assertThat(service.replaceException(TODAY, four).getWindows()).hasSize(4);
  }

  @Test
  void replaceException_Should_AcceptDatesFrom62DaysAgoTo400DaysAhead_InThePlatformsZone() {
    for (var date : List.of(TODAY.minusDays(62), TODAY.plusDays(400))) {
      assertThat(service.replaceException(date, dayRequest()).getDate()).isEqualTo(date);
    }
  }

  @Test
  void replaceException_Should_RefuseInvalidWindowsAndDates_WithoutTouchingTheStoredOnes() {
    var date = TODAY.plusDays(1);
    var five = new CalendarWorkingTimeWindowDTO[CalendarWorkingHoursService.MAX_DAY_WINDOWS + 1];
    for (var i = 0; i < five.length; i++) {
      five[i] = dayWindow("0" + i + ":00", "0" + i + ":30");
    }
    for (var invalid :
        List.of(
            dayRequest(five),
            dayRequest(dayWindow("09:00", "09:00")),
            dayRequest(dayWindow("17:00", "08:00")),
            dayRequest(dayWindow("08:00", "12:00"), dayWindow("11:59", "13:00")),
            dayRequest(dayWindow("13:00", "17:00"), dayWindow("08:00", "13:01")),
            dayRequest(dayWindow("08:00", "17:00"), dayWindow("09:00", "10:00")),
            dayRequest(dayWindow("8:00", "12:00")),
            dayRequest(dayWindow("08:00", "24:00")),
            dayRequest(dayWindow(null, "12:00")),
            dayRequest(dayWindow("08:00", null)),
            dayRequest(dayWindow("08:00", "12:00"), null),
            new CalendarWorkingDayExceptionWindowsDTO().windows(null))) {
      assertThatThrownBy(() -> service.replaceException(date, invalid))
          .as(String.valueOf(invalid))
          .isInstanceOf(BadRequestException.class);
    }
    assertThatThrownBy(() -> service.replaceException(date, null))
        .isInstanceOf(BadRequestException.class);
    for (var unreachable :
        new LocalDate[] {TODAY.minusDays(63), TODAY.minusDays(400), TODAY.plusDays(401), null}) {
      assertThatThrownBy(() -> service.replaceException(unreachable, dayRequest()))
          .as(String.valueOf(unreachable))
          .isInstanceOf(BadRequestException.class);
    }
    verifyNoInteractions(exceptions, consultants);
  }

  @Test
  void replaceException_Should_RefuseA401stDate() {
    var date = TODAY.plusDays(1);
    when(exceptions.existsByConsultantIdAndExceptionDate(ME, date)).thenReturn(false);
    when(exceptions.countDatesByConsultantId(ME))
        .thenReturn((long) CalendarWorkingHoursService.MAX_EXCEPTIONS);

    assertThatThrownBy(() -> service.replaceException(date, dayRequest()))
        .isInstanceOf(ConflictException.class);
    verify(exceptions, never()).deleteByConsultantIdAndExceptionDate(any(), any());
    verify(exceptions, never()).saveAll(any());
  }

  @Test
  void replaceException_Should_LockTheCounsellor_BeforeCheckingTheLimit() {
    var date = TODAY.plusDays(1);

    service.replaceException(date, dayRequest());

    var order = inOrder(consultants, exceptions);
    order.verify(consultants).lockConsultant(ME);
    order.verify(exceptions).deleteByConsultantIdBefore(any(), any());
    order.verify(exceptions).countDatesByConsultantId(ME);
    order.verify(exceptions).saveAll(any());
  }

  @Test
  void removeException_Should_LockTheCounsellor_BeforeDeleting() {
    var date = TODAY.plusDays(1);

    service.removeException(date);

    var order = inOrder(consultants, exceptions);
    order.verify(consultants).lockConsultant(ME);
    order.verify(exceptions).deleteByConsultantIdAndExceptionDate(ME, date);
  }

  @Test
  void replaceException_Should_Refuse_When_TheCounsellorHasNoRow() {
    when(consultants.lockConsultant(ME)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replaceException(TODAY.plusDays(1), dayRequest()))
        .isInstanceOf(ForbiddenException.class);
    verify(exceptions, never()).saveAll(any());
  }

  @Test
  void replaceException_Should_ReplaceAKeptDate_EvenAtTheLimit() {
    var date = TODAY.plusDays(1);
    when(exceptions.existsByConsultantIdAndExceptionDate(ME, date)).thenReturn(true);

    service.replaceException(date, dayRequest(dayWindow("08:00", "12:00")));

    verify(exceptions, never()).countDatesByConsultantId(any());
    verify(exceptions).deleteByConsultantIdAndExceptionDate(ME, date);
    verify(exceptions).saveAll(any());
  }

  @Test
  void replaceException_Should_AcceptThe400thDate() {
    var date = TODAY.plusDays(1);
    when(exceptions.countDatesByConsultantId(ME))
        .thenReturn((long) CalendarWorkingHoursService.MAX_EXCEPTIONS - 1);

    service.replaceException(date, dayRequest());

    verify(exceptions).saveAll(any());
  }

  @Test
  void removeException_Should_RemoveTheCallersExceptionOnThatDate() {
    service.removeException(TODAY.plusDays(500));

    verify(exceptions).deleteByConsultantIdAndExceptionDate(ME, TODAY.plusDays(500));
  }

  @Test
  void removeException_Should_RefuseAMissingDate() {
    assertThatThrownBy(() -> service.removeException(null)).isInstanceOf(BadRequestException.class);
    verifyNoInteractions(exceptions);
  }

  @Test
  void anAsker_Should_NeitherReadNorSetWorkingHours() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(() -> service.workingHours()).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.replace(request(window(1, "08:00", "17:00"))))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.replaceException(TODAY, dayRequest()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.removeException(TODAY)).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(repository, settings, exceptions);
  }

  private static CalendarWorkingHoursDTO request(CalendarWorkingHoursWindowDTO... windows) {
    return new CalendarWorkingHoursDTO().windows(new ArrayList<>(Arrays.asList(windows)));
  }

  private static CalendarWorkingHoursWindowDTO window(Integer weekday, String start, String end) {
    return new CalendarWorkingHoursWindowDTO().weekday(weekday).start(start).end(end);
  }

  private static CalendarWorkingDayExceptionWindowsDTO dayRequest(
      CalendarWorkingTimeWindowDTO... windows) {
    return new CalendarWorkingDayExceptionWindowsDTO()
        .windows(new ArrayList<>(Arrays.asList(windows)));
  }

  private static CalendarWorkingTimeWindowDTO dayWindow(String start, String end) {
    return new CalendarWorkingTimeWindowDTO().start(start).end(end);
  }

  private static CalendarWorkingDayExceptionDTO exception(
      LocalDate date, CalendarWorkingTimeWindowDTO... windows) {
    return new CalendarWorkingDayExceptionDTO()
        .date(date)
        .windows(new ArrayList<>(Arrays.asList(windows)));
  }

  private static CalendarWorkingHours row(int weekday, String start, String end) {
    var row = new CalendarWorkingHours();
    row.setConsultantId(ME);
    row.setWeekday(weekday);
    row.setStartTime(LocalTime.parse(start));
    row.setEndTime(LocalTime.parse(end));
    return row;
  }

  private static CalendarWorkingDayException exceptionRow(
      LocalDate date, String start, String end) {
    var row = new CalendarWorkingDayException();
    row.setConsultantId(ME);
    row.setExceptionDate(date);
    row.setStartTime(start == null ? null : LocalTime.parse(start));
    row.setEndTime(end == null ? null : LocalTime.parse(end));
    return row;
  }

  private static CalendarWorkingHoursSetting setting(String region) {
    var setting = new CalendarWorkingHoursSetting();
    setting.setConsultantId(ME);
    setting.setHolidayRegion(region);
    return setting;
  }
}
