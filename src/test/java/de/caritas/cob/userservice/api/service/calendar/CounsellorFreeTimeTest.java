package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarConsultationHours;
import de.caritas.cob.userservice.api.model.CalendarConsultationOnce;
import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationOnceRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.calendar.FreeSlot.BookingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The free time an advice seeker may book (ORISO-Frontend#2287): the counsellor's Sprechstunden,
 * not their working hours, cut into slots of the counsellor's length; each slot instant or a
 * request by the counsellor's auto-accept and notice period.
 */
class CounsellorFreeTimeTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final String COUNSELLOR = "synthetic-counsellor";

  /** Monday 23 Nov 2026, 08:00 in Berlin. */
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

  private static final Instant NOW = at(MONDAY, "08:00");

  private final CalendarConsultationHoursRepository consultationHours =
      mock(CalendarConsultationHoursRepository.class);
  private final CalendarConsultationOnceRepository consultationOnce =
      mock(CalendarConsultationOnceRepository.class);
  private final CalendarBookingSettingRepository bookingSettings =
      mock(CalendarBookingSettingRepository.class);
  private final CalendarWorkingHoursRepository workingHours =
      mock(CalendarWorkingHoursRepository.class);
  private final CalendarWorkingHoursSettingRepository workingHoursSettings =
      mock(CalendarWorkingHoursSettingRepository.class);
  private final CalendarWorkingDayExceptionRepository exceptions =
      mock(CalendarWorkingDayExceptionRepository.class);
  private final CalendarEntryReader entries = mock(CalendarEntryReader.class);
  private final SessionAppointmentRepository appointments =
      mock(SessionAppointmentRepository.class);

  private final List<CalendarConsultationHours> hours = new ArrayList<>();
  private final List<CalendarEntry> taken = new ArrayList<>();
  private final List<SessionAppointment> requested = new ArrayList<>();
  private final List<CalendarConsultationOnce> onces = new ArrayList<>();
  private final List<CalendarEntry> blocked = new ArrayList<>();

  private CounsellorFreeTime freeTime;

  @BeforeEach
  void setUp() {
    when(consultationHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(COUNSELLOR))
        .thenReturn(hours);
    when(bookingSettings.findById(COUNSELLOR)).thenReturn(Optional.empty());
    when(workingHoursSettings.findById(COUNSELLOR)).thenReturn(Optional.empty());
    when(exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(eq(COUNSELLOR), any()))
        .thenReturn(List.of());
    when(entries.appointmentsOfConsultant(anyString(), any(), any())).thenReturn(taken);
    when(entries.groupOccurrencesOfConsultant(anyString(), any(), any())).thenReturn(List.of());
    when(entries.blockersOf(anyString(), any(), any())).thenReturn(blocked);
    when(consultationOnce.findOverlapping(eq(COUNSELLOR), any(), any()))
        .thenAnswer(
            call -> {
              Instant from = call.getArgument(1);
              Instant to = call.getArgument(2);
              return onces.stream()
                  .filter(o -> o.getStartAt().isBefore(to) && o.getEndAt().isAfter(from))
                  .toList();
            });
    when(appointments.findRequestedOfConsultantBetween(anyString(), any(), any()))
        .thenReturn(requested);
    freeTime = freeTimeAt(NOW);
  }

  @Test
  void construction_Should_Fail_When_TheSlotStepIsNotPositive() {
    for (var step : List.of(Duration.ZERO, Duration.ofMinutes(-30))) {
      assertThatThrownBy(
              () ->
                  new CounsellorFreeTime(
                      consultationHours,
                      consultationOnce,
                      bookingSettings,
                      workingHoursSettings,
                      exceptions,
                      entries,
                      appointments,
                      Clock.fixed(NOW, BERLIN),
                      "Europe/Berlin",
                      Duration.ofHours(2),
                      step,
                      Duration.ofDays(14)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("appointment.self-booking.step");
    }
  }

  private CounsellorFreeTime freeTimeAt(Instant now) {
    return new CounsellorFreeTime(
        consultationHours,
        consultationOnce,
        bookingSettings,
        workingHoursSettings,
        exceptions,
        entries,
        appointments,
        Clock.fixed(now, BERLIN),
        "Europe/Berlin",
        Duration.ofHours(2),
        Duration.ofMinutes(30),
        Duration.ofDays(14));
  }

  @Test
  void slots_Should_ComeFromTheSprechstunden_NotFromTheWorkingHours() {
    var working = new CalendarWorkingHours();
    working.setConsultantId(COUNSELLOR);
    working.setWeekday(2);
    working.setStartTime(LocalTime.of(8, 0));
    working.setEndTime(LocalTime.of(18, 0));
    when(workingHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(COUNSELLOR))
        .thenReturn(List.of(working));
    sprechstunde(2, "10:00", "12:00");

    var slots = freeTime.slots(COUNSELLOR, NOW, at(MONDAY.plusDays(2), "00:00"));

    assertThat(starts(slots)).containsExactly("10:00", "10:30", "11:00");
  }

  @Test
  void slots_Should_BeEmpty_WithoutSprechstunden() {
    assertThat(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(14)))).isEmpty();
  }

  @Test
  void slots_Should_LastTheCounsellorsSlotLength() {
    setting(true, 1, 30);
    sprechstunde(2, "10:00", "11:00");

    var slots = freeTime.slots(COUNSELLOR, NOW, at(MONDAY.plusDays(2), "00:00"));

    assertThat(starts(slots)).containsExactly("10:00", "10:30");
    assertThat(slots)
        .allSatisfy(
            slot ->
                assertThat(Duration.between(slot.start(), slot.end()))
                    .isEqualTo(Duration.ofMinutes(30)));
  }

  @Test
  void slots_Should_BeInstant_OutsideTheNoticePeriod_And_RequestsInsideIt() {
    setting(true, 2, 60);
    // Monday 10:00 is exactly the minimum lead time (2 h) after 08:00, so it is offered.
    sprechstunde(1, "10:00", "11:00");
    sprechstunde(3, "10:00", "11:00");
    sprechstunde(4, "10:00", "11:00");

    var slots = freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(4)));

    assertThat(slots)
        .extracting(slot -> slot.start().atZone(BERLIN).toLocalDate(), FreeSlot::mode)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(MONDAY, BookingMode.REQUEST),
            // Wednesday 10:00 is 50 hours ahead: two days have passed.
            org.assertj.core.groups.Tuple.tuple(MONDAY.plusDays(2), BookingMode.INSTANT),
            org.assertj.core.groups.Tuple.tuple(MONDAY.plusDays(3), BookingMode.INSTANT));
  }

  @Test
  void slots_Should_AllBeRequests_WhenAutoAcceptIsOff() {
    setting(false, 1, 60);
    sprechstunde(4, "10:00", "11:00");

    assertThat(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(5))))
        .extracting(FreeSlot::mode)
        .containsExactly(BookingMode.REQUEST);
  }

  @Test
  void slots_Should_DefaultToInstantHourLongSlots_AndOneDayNotice() {
    sprechstunde(1, "13:00", "14:00");
    sprechstunde(2, "13:00", "14:00");

    var slots = freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(2)));

    assertThat(slots)
        .extracting(FreeSlot::mode)
        .containsExactly(BookingMode.REQUEST, BookingMode.INSTANT);
    assertThat(Duration.between(slots.get(0).start(), slots.get(0).end()))
        .isEqualTo(Duration.ofHours(1));
  }

  @Test
  void slots_Should_StartNoEarlierThanTheMinimumLeadTime() {
    sprechstunde(1, "08:00", "12:00");

    assertThat(starts(freeTime.slots(COUNSELLOR, NOW, at(MONDAY.plusDays(1), "00:00"))))
        .containsExactly("10:00", "10:30", "11:00");
  }

  @Test
  void slots_Should_LeaveOutDaysOff() {
    // Thursday 26 Nov is a day off.
    var dayOff = new CalendarWorkingDayException();
    dayOff.setConsultantId(COUNSELLOR);
    dayOff.setExceptionDate(MONDAY.plusDays(3));
    when(exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(eq(COUNSELLOR), any()))
        .thenReturn(List.of(dayOff));
    sprechstunde(3, "10:00", "11:00");
    sprechstunde(4, "10:00", "11:00");

    assertThat(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(5))))
        .extracting(slot -> slot.start().atZone(BERLIN).toLocalDate())
        .containsExactly(MONDAY.plusDays(2));
  }

  @Test
  void slots_Should_LeaveOutThePublicHolidaysOfTheRegion() {
    // Friday 25 Dec 2026 is a public holiday everywhere; Thursday 24 Dec is not.
    var region = new CalendarWorkingHoursSetting();
    region.setHolidayRegion("BY");
    when(workingHoursSettings.findById(COUNSELLOR)).thenReturn(Optional.of(region));
    sprechstunde(4, "10:00", "11:00");
    sprechstunde(5, "10:00", "11:00");
    var now = at(LocalDate.of(2026, 12, 21), "08:00");

    assertThat(freeTimeAt(now).slots(COUNSELLOR, now, now.plus(Duration.ofDays(5))))
        .extracting(slot -> slot.start().atZone(BERLIN).toLocalDate())
        .containsExactly(LocalDate.of(2026, 12, 24));
  }

  @Test
  void slots_Should_LeaveOutTakenAndRequestedTime() {
    sprechstunde(2, "10:00", "13:00");
    var tuesday = MONDAY.plusDays(1);
    taken.add(entry(at(tuesday, "10:00"), at(tuesday, "11:00")));
    requested.add(request(at(tuesday, "12:00"), at(tuesday, "13:00")));

    assertThat(starts(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(2)))))
        .containsExactly("11:00");
  }

  @Test
  void offeredSlot_Should_NameTheModeOfExactlyThatSlot() {
    setting(true, 1, 60);
    sprechstunde(3, "10:00", "12:00");
    var wednesday = MONDAY.plusDays(2);

    assertThat(freeTime.offeredSlot(COUNSELLOR, at(wednesday, "10:30"), at(wednesday, "11:30")))
        .map(FreeSlot::mode)
        .contains(BookingMode.INSTANT);
    assertThat(freeTime.offeredSlot(COUNSELLOR, at(wednesday, "10:30"), at(wednesday, "12:30")))
        .isEmpty();
  }

  @Test
  void isFreeBesides_Should_IgnoreTheAppointmentItself_ButNotOtherTakenTime() {
    var tuesday = MONDAY.plusDays(1);
    var own = request(at(tuesday, "10:00"), at(tuesday, "11:00"));
    requested.add(own);

    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isTrue();

    taken.add(entry(at(tuesday, "10:30"), at(tuesday, "11:30")));
    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isFalse();
  }

  // One-off Sprechstunden (ORISO-Frontend#2494).

  @Test
  void isFreeBesides_Should_IgnoreOwnBlockers_InsideAOneOffReleasedOverThem() {
    var tuesday = MONDAY.plusDays(1);
    var own = request(at(tuesday, "14:00"), at(tuesday, "15:00"));
    requested.add(own);
    blocked.add(blocker(at(tuesday, "13:00"), at(tuesday, "16:00"), CalendarEntryType.BLOCKER));
    once(at(tuesday, "14:00"), at(tuesday, "16:00"), 60, true);

    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isTrue();
  }

  @Test
  void isFreeBesides_Should_StillRejectOtherTakenTime_InsideAOneOffReleasedOverBlockers() {
    var tuesday = MONDAY.plusDays(1);
    var own = request(at(tuesday, "14:00"), at(tuesday, "15:00"));
    requested.add(own);
    blocked.add(blocker(at(tuesday, "14:00"), at(tuesday, "15:00"), CalendarEntryType.BLOCKER));
    once(at(tuesday, "14:00"), at(tuesday, "16:00"), 60, true);
    taken.add(entry(at(tuesday, "14:30"), at(tuesday, "15:00")));

    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isFalse();

    taken.clear();
    blocked.add(blocker(at(tuesday, "14:00"), at(tuesday, "14:30"), CalendarEntryType.BUSY));
    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isFalse();
  }

  @Test
  void isFreeBesides_Should_RejectAnOwnBlocker_WithoutAOneOffReleasedOverIt() {
    var tuesday = MONDAY.plusDays(1);
    var own = request(at(tuesday, "14:00"), at(tuesday, "15:00"));
    requested.add(own);
    blocked.add(blocker(at(tuesday, "14:00"), at(tuesday, "15:00"), CalendarEntryType.BLOCKER));

    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isFalse();

    // A one-off not released over blockers does not exempt them.
    once(at(tuesday, "14:00"), at(tuesday, "16:00"), 60, false);
    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isFalse();

    // Released over blockers, but covering only part of the blocked request time.
    onces.clear();
    once(at(tuesday, "14:30"), at(tuesday, "16:00"), 60, true);
    assertThat(
            freeTime.isFreeBesides(COUNSELLOR, own.getStartTime(), own.getEndTime(), own.getId()))
        .isFalse();
  }

  @Test
  void slots_Should_OfferAOneOff_WithoutAnyWeeklySprechstunde() {
    var tuesday = MONDAY.plusDays(1);
    once(at(tuesday, "14:00"), at(tuesday, "16:00"), 60, false);

    assertThat(starts(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(3)))))
        .containsExactly("14:00", "14:30", "15:00");
  }

  @Test
  void slots_Should_StartAOneOffAfterItsOwnNotice_NotTheMinimumLeadTime() {
    // Now is 08:00; the minimum lead time would start at 10:00, the 30-minute notice at 08:30.
    once(at(MONDAY, "08:00"), at(MONDAY, "10:30"), 30, false);

    assertThat(starts(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(1)))))
        .containsExactly("08:30", "09:00", "09:30");
  }

  @Test
  void slots_Should_OfferAOneOffShorterThanTheSlotLength_AsOneSlotOfItsLength() {
    var tuesday = MONDAY.plusDays(1);
    once(at(tuesday, "14:00"), at(tuesday, "14:45"), 0, false);

    var slots = freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(3)));

    assertThat(slots).hasSize(1);
    assertThat(slots.get(0).start()).isEqualTo(at(tuesday, "14:00"));
    assertThat(slots.get(0).end()).isEqualTo(at(tuesday, "14:45"));
  }

  @Test
  void slots_Should_LeaveOutAOneOffsTime_UnderABlocker_UnlessReleasedOverIt() {
    var tuesday = MONDAY.plusDays(1);
    blocked.add(blocker(at(tuesday, "14:00"), at(tuesday, "15:00"), CalendarEntryType.BLOCKER));
    once(at(tuesday, "14:00"), at(tuesday, "16:00"), 60, false);

    assertThat(starts(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(3)))))
        .containsExactly("15:00");

    onces.get(0).setOverBlocker(true);
    assertThat(starts(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(3)))))
        .containsExactly("14:00", "14:30", "15:00");
  }

  @Test
  void slots_Should_LeaveOutAOneOffsTime_UnderImportedBusyTimeAndAppointments_EvenOverABlocker() {
    var tuesday = MONDAY.plusDays(1);
    blocked.add(blocker(at(tuesday, "14:00"), at(tuesday, "15:00"), CalendarEntryType.BUSY));
    taken.add(entry(at(tuesday, "16:30"), at(tuesday, "17:00")));
    once(at(tuesday, "14:00"), at(tuesday, "17:00"), 60, true);

    assertThat(starts(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(3)))))
        .containsExactly("15:00", "15:30");
  }

  @Test
  void slots_Should_KeepAOneOff_OnADayOff_And_APublicHoliday() {
    // Friday 25 Dec 2026 is a public holiday; Thursday 24 Dec is a day off.
    var region = new CalendarWorkingHoursSetting();
    region.setHolidayRegion("BY");
    when(workingHoursSettings.findById(COUNSELLOR)).thenReturn(Optional.of(region));
    var dayOff = new CalendarWorkingDayException();
    dayOff.setConsultantId(COUNSELLOR);
    dayOff.setExceptionDate(LocalDate.of(2026, 12, 24));
    when(exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(eq(COUNSELLOR), any()))
        .thenReturn(List.of(dayOff));
    sprechstunde(4, "10:00", "11:00");
    sprechstunde(5, "10:00", "11:00");
    once(
        at(LocalDate.of(2026, 12, 24), "12:00"), at(LocalDate.of(2026, 12, 24), "13:00"), 0, false);
    once(
        at(LocalDate.of(2026, 12, 25), "12:00"), at(LocalDate.of(2026, 12, 25), "13:00"), 0, false);
    var now = at(LocalDate.of(2026, 12, 21), "08:00");

    assertThat(freeTimeAt(now).slots(COUNSELLOR, now, now.plus(Duration.ofDays(5))))
        .extracting(slot -> slot.start().atZone(BERLIN).toLocalDateTime().toString())
        .containsExactly("2026-12-24T12:00", "2026-12-25T12:00");
  }

  @Test
  void slots_Should_OfferAWeeklyAndAOneOffSlotOfTheSameTime_Once() {
    setting(true, 2, 60);
    var tuesday = MONDAY.plusDays(1);
    sprechstunde(2, "10:00", "11:00");
    once(at(tuesday, "10:00"), at(tuesday, "12:00"), 0, false);

    var slots = freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(3)));

    assertThat(starts(slots)).containsExactly("10:00", "10:30", "11:00");
    // Inside the two-day notice the weekly slot is a request; the one-off's own mode wins.
    assertThat(slots).extracting(FreeSlot::mode).containsOnly(BookingMode.INSTANT);
  }

  @Test
  void slots_Should_BookAOneOffAtOnce_When_TheCounsellorAcceptsAutomatically_ElseAsARequest() {
    setting(true, 7, 60);
    once(at(MONDAY, "09:00"), at(MONDAY, "10:00"), 30, false);

    assertThat(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(1))))
        .extracting(FreeSlot::mode)
        .containsExactly(BookingMode.INSTANT);

    setting(false, 0, 60);
    assertThat(freeTime.slots(COUNSELLOR, NOW, NOW.plus(Duration.ofDays(1))))
        .extracting(FreeSlot::mode)
        .containsExactly(BookingMode.REQUEST);
  }

  @Test
  void offeredSlot_Should_LookUpOneOffs_OverTheWholeSlot_NotASubMicrosecondWindow() {
    // MariaDB truncates timestamp parameters to microseconds; a window [start, start+1ns)
    // collapses to an empty one and finds no one-off for the first slot of a window.
    when(bookingSettings.lockById(COUNSELLOR)).thenReturn(Optional.empty());
    once(at(MONDAY, "09:00"), at(MONDAY, "11:00"), 0, false);

    assertThat(freeTime.offeredSlot(COUNSELLOR, at(MONDAY, "09:00"), at(MONDAY, "10:00")))
        .isPresent();
    verify(consultationOnce).findOverlapping(COUNSELLOR, at(MONDAY, "09:00"), at(MONDAY, "10:00"));
  }

  @Test
  void offeredSlot_Should_AcceptAOneOffSlot_InsideTheMinimumLeadTime_ButNotInsideItsNotice() {
    when(bookingSettings.lockById(COUNSELLOR)).thenReturn(Optional.empty());
    once(at(MONDAY, "08:00"), at(MONDAY, "10:00"), 60, false);

    assertThat(freeTime.offeredSlot(COUNSELLOR, at(MONDAY, "09:00"), at(MONDAY, "10:00")))
        .isPresent();
    assertThat(freeTime.offeredSlot(COUNSELLOR, at(MONDAY, "08:30"), at(MONDAY, "09:30")))
        .isEmpty();
    assertThat(freeTime.isInsideBookingWindow(COUNSELLOR, at(MONDAY, "09:00"), at(MONDAY, "10:00")))
        .isTrue();
    assertThat(freeTime.isInsideBookingWindow(COUNSELLOR, at(MONDAY, "08:30"), at(MONDAY, "09:30")))
        .isFalse();
    // Outside any one-off the minimum lead time still holds.
    assertThat(freeTime.isInsideBookingWindow(COUNSELLOR, at(MONDAY, "09:00"), at(MONDAY, "10:30")))
        .isFalse();
    assertThat(freeTime.isInsideBookingWindow(COUNSELLOR, at(MONDAY, "10:00"), at(MONDAY, "11:00")))
        .isTrue();
  }

  private void once(Instant start, Instant end, int noticeMinutes, boolean overBlocker) {
    var row = new CalendarConsultationOnce();
    row.setId(UUID.randomUUID());
    row.setConsultantId(COUNSELLOR);
    row.setStartAt(start);
    row.setEndAt(end);
    row.setNoticeMinutes(noticeMinutes);
    row.setOverBlocker(overBlocker);
    onces.add(row);
  }

  private static CalendarEntry blocker(Instant start, Instant end, CalendarEntryType type) {
    return new CalendarEntry(
        UUID.randomUUID().toString(), type, start, end, null, null, null, null);
  }

  private void sprechstunde(int weekday, String start, String end) {
    var row = new CalendarConsultationHours();
    row.setConsultantId(COUNSELLOR);
    row.setWeekday(weekday);
    row.setStartTime(LocalTime.parse(start));
    row.setEndTime(LocalTime.parse(end));
    hours.add(row);
  }

  @Test
  void offeredSlot_Should_FollowTheSettingReadWithALock() {
    // The plain read says the defaults; the booking's locking read says requests of 30 minutes.
    setting(true, 1, 60);
    var locked = new CalendarBookingSetting();
    locked.setConsultantId(COUNSELLOR);
    locked.setAutoAccept(false);
    locked.setNoticeDays(1);
    locked.setSlotMinutes(30);
    when(bookingSettings.lockById(COUNSELLOR)).thenReturn(Optional.of(locked));
    sprechstunde(3, "10:00", "12:00");
    var wednesday = MONDAY.plusDays(2);

    assertThat(freeTime.offeredSlot(COUNSELLOR, at(wednesday, "10:30"), at(wednesday, "11:00")))
        .map(FreeSlot::mode)
        .contains(BookingMode.REQUEST);
  }

  private void setting(boolean autoAccept, int noticeDays, int slotMinutes) {
    var setting = new CalendarBookingSetting();
    setting.setConsultantId(COUNSELLOR);
    setting.setAutoAccept(autoAccept);
    setting.setNoticeDays(noticeDays);
    setting.setSlotMinutes(slotMinutes);
    when(bookingSettings.findById(COUNSELLOR)).thenReturn(Optional.of(setting));
  }

  private static CalendarEntry entry(Instant start, Instant end) {
    return new CalendarEntry(
        UUID.randomUUID().toString(),
        CalendarEntryType.APPOINTMENT,
        start,
        end,
        SessionAppointment.Kind.VIDEO,
        "asker",
        1L,
        null);
  }

  private static SessionAppointment request(Instant start, Instant end) {
    var appointment = new SessionAppointment();
    appointment.setId(UUID.randomUUID());
    appointment.setStatus(SessionAppointment.Status.REQUESTED);
    appointment.setStartTime(start);
    appointment.setEndTime(end);
    return appointment;
  }

  private static Instant at(LocalDate date, String time) {
    return LocalDateTime.of(date, LocalTime.parse(time)).atZone(BERLIN).toInstant();
  }

  private static List<String> starts(List<FreeSlot> slots) {
    return slots.stream()
        .map(slot -> slot.start().atZone(BERLIN).toLocalTime().toString())
        .toList();
  }
}
