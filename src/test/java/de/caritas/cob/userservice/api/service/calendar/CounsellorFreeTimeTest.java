package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarConsultationHours;
import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
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
    when(entries.blockersOf(anyString(), any(), any())).thenReturn(List.of());
    when(appointments.findRequestedOfConsultantBetween(anyString(), any(), any()))
        .thenReturn(requested);
    freeTime = freeTimeAt(NOW);
  }

  private CounsellorFreeTime freeTimeAt(Instant now) {
    return new CounsellorFreeTime(
        consultationHours,
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

  private void sprechstunde(int weekday, String start, String end) {
    var row = new CalendarConsultationHours();
    row.setConsultantId(COUNSELLOR);
    row.setWeekday(weekday);
    row.setStartTime(LocalTime.parse(start));
    row.setEndTime(LocalTime.parse(end));
    hours.add(row);
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
