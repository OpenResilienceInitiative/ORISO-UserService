package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarConsultationHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursWindowDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarConsultationHours;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A counsellor's Sprechstunden and booking settings (ORISO-Frontend#2287): weekly windows advice
 * seekers can book, separate from the working hours, plus auto-accept, notice days and slot length.
 * Only the counsellor themself reads and writes them.
 */
@ExtendWith(MockitoExtension.class)
class CalendarConsultationHoursServiceTest {

  private static final String ME = "me";

  @Mock CalendarConsultationHoursRepository hours;
  @Mock CalendarBookingSettingRepository settings;
  @Mock ConsultantRepository consultants;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private CalendarConsultationHoursService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    lenient().when(consultants.lockConsultant(ME)).thenReturn(Optional.of(ME));
    service = new CalendarConsultationHoursService(caller, hours, settings, consultants);
  }

  @Test
  void consultationHours_Should_GiveTheDefaults_When_NothingIsSet() {
    when(hours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(ME)).thenReturn(List.of());
    when(settings.findById(ME)).thenReturn(Optional.empty());

    var result = service.consultationHours();

    assertThat(result.getWindows()).isEmpty();
    assertThat(result.getAutoAccept()).isTrue();
    assertThat(result.getNoticeDays()).isEqualTo(1);
    assertThat(result.getSlotMinutes()).isEqualTo(60);
  }

  @Test
  void consultationHours_Should_GiveTheStoredWindowsAndSettings() {
    when(hours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(ME))
        .thenReturn(List.of(row(2, "09:00", "12:00")));
    when(settings.findById(ME)).thenReturn(Optional.of(setting(false, 3, 30)));

    var result = service.consultationHours();

    assertThat(result.getWindows())
        .containsExactly(
            new CalendarWorkingHoursWindowDTO().weekday(2).start("09:00").end("12:00"));
    assertThat(result.getAutoAccept()).isFalse();
    assertThat(result.getNoticeDays()).isEqualTo(3);
    assertThat(result.getSlotMinutes()).isEqualTo(30);
  }

  @Test
  void replace_Should_StoreEveryWindowAndTheSettings_AfterLockingTheCounsellor() {
    when(settings.findById(ME)).thenReturn(Optional.empty());
    var request = request(false, 2, 30, window(4, "14:00", "16:00"), window(1, "09:00", "11:00"));

    var result = service.replace(request);

    var order = inOrder(consultants, hours);
    order.verify(consultants).lockConsultant(ME);
    order.verify(hours).deleteByConsultantId(ME);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<CalendarConsultationHours>> stored = ArgumentCaptor.forClass(List.class);
    verify(hours).saveAll(stored.capture());
    assertThat(stored.getValue())
        .extracting(
            CalendarConsultationHours::getConsultantId, CalendarConsultationHours::getWeekday)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(ME, 4), org.assertj.core.groups.Tuple.tuple(ME, 1));
    var setting = ArgumentCaptor.forClass(CalendarBookingSetting.class);
    verify(settings).save(setting.capture());
    assertThat(setting.getValue().getConsultantId()).isEqualTo(ME);
    assertThat(setting.getValue().isAutoAccept()).isFalse();
    assertThat(setting.getValue().getNoticeDays()).isEqualTo(2);
    assertThat(setting.getValue().getSlotMinutes()).isEqualTo(30);
    assertThat(result.getWindows())
        .extracting(CalendarWorkingHoursWindowDTO::getWeekday)
        .containsExactly(1, 4);
  }

  @Test
  void replace_Should_TakeTheDefaults_ForSettingsLeftOut() {
    when(settings.findById(ME)).thenReturn(Optional.empty());

    var result =
        service.replace(
            new CalendarConsultationHoursDTO().windows(List.of(window(1, "09:00", "10:00"))));

    assertThat(result.getAutoAccept()).isTrue();
    assertThat(result.getNoticeDays()).isEqualTo(1);
    assertThat(result.getSlotMinutes()).isEqualTo(60);
  }

  @Test
  void replace_Should_RefuseInvalidRequests_AndStoreNothing() {
    var invalid = new ArrayList<CalendarConsultationHoursDTO>();
    invalid.add(null);
    invalid.add(new CalendarConsultationHoursDTO().windows(null));
    invalid.add(request(true, 1, 45, window(1, "09:00", "10:00")));
    invalid.add(request(true, -1, 60, window(1, "09:00", "10:00")));
    invalid.add(request(true, 31, 60, window(1, "09:00", "10:00")));
    invalid.add(request(true, 1, 60, window(7, "09:00", "10:00")));
    invalid.add(request(true, 1, 60, window(1, "10:00", "09:00")));
    invalid.add(request(true, 1, 60, window(1, "9:00", "10:00")));
    invalid.add(request(true, 1, 60, window(1, "09:00", "11:00"), window(1, "10:30", "12:00")));
    var tooMany = new ArrayList<CalendarWorkingHoursWindowDTO>();
    for (int i = 0; i < 15; i++) {
      tooMany.add(window(i % 7, String.format("%02d:00", i), String.format("%02d:30", i)));
    }
    invalid.add(
        new CalendarConsultationHoursDTO()
            .windows(tooMany)
            .autoAccept(true)
            .noticeDays(1)
            .slotMinutes(60));

    for (var request : invalid) {
      assertThatThrownBy(() -> service.replace(request))
          .as(String.valueOf(request))
          .isInstanceOf(BadRequestException.class);
    }
    verify(hours, never()).deleteByConsultantId(any());
    verify(settings, never()).save(any());
  }

  @Test
  void everything_Should_BeForbidden_ForAnAdviceSeeker() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(() -> service.consultationHours()).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.replace(request(true, 1, 60)))
        .isInstanceOf(ForbiddenException.class);
    verify(hours, never()).deleteByConsultantId(any());
  }

  private static CalendarConsultationHoursDTO request(
      boolean autoAccept,
      int noticeDays,
      int slotMinutes,
      CalendarWorkingHoursWindowDTO... windows) {
    return new CalendarConsultationHoursDTO()
        .windows(List.of(windows))
        .autoAccept(autoAccept)
        .noticeDays(noticeDays)
        .slotMinutes(slotMinutes);
  }

  private static CalendarWorkingHoursWindowDTO window(int weekday, String start, String end) {
    return new CalendarWorkingHoursWindowDTO().weekday(weekday).start(start).end(end);
  }

  private static CalendarConsultationHours row(int weekday, String start, String end) {
    var row = new CalendarConsultationHours();
    row.setConsultantId(ME);
    row.setWeekday(weekday);
    row.setStartTime(LocalTime.parse(start));
    row.setEndTime(LocalTime.parse(end));
    return row;
  }

  private static CalendarBookingSetting setting(
      boolean autoAccept, int noticeDays, int slotMinutes) {
    var setting = new CalendarBookingSetting();
    setting.setConsultantId(ME);
    setting.setAutoAccept(autoAccept);
    setting.setNoticeDays(noticeDays);
    setting.setSlotMinutes(slotMinutes);
    return setting;
  }
}
