package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarConsultationOnceCreateDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarConsultationOnce;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationOnceRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * One-off Sprechstunden (ORISO-Frontend#2494): one dated window a counsellor releases for booking
 * once, with its own notice; over their own blockers only when they confirmed it. Only the
 * counsellor themself reads, creates and removes them.
 */
@ExtendWith(MockitoExtension.class)
class CalendarConsultationOnceServiceTest {

  private static final String ME = "me";
  private static final Instant NOW = Instant.parse("2026-11-24T08:00:00Z");

  @Mock CalendarConsultationOnceRepository onces;
  @Mock CalendarBlockerRepository blockers;
  @Mock ConsultantRepository consultants;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private CalendarConsultationOnceService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    lenient().when(consultants.lockConsultant(ME)).thenReturn(Optional.of(ME));
    lenient().when(onces.findOverlapping(any(), any(), any())).thenReturn(List.of());
    lenient()
        .when(onces.findByConsultantIdAndEndAtAfterOrderByStartAtAsc(any(), any()))
        .thenReturn(List.of());
    lenient().when(blockers.findOverlapping(any(), any(), any())).thenReturn(List.of());
    lenient()
        .when(onces.save(any()))
        .thenAnswer(
            call -> {
              CalendarConsultationOnce saved = call.getArgument(0);
              saved.setId(UUID.randomUUID());
              return saved;
            });
    service =
        new CalendarConsultationOnceService(
            caller, onces, blockers, consultants, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void create_Should_StoreTheWindow_AfterLockingTheCounsellor() {
    var result = service.create(request("09:00", "11:00", 30, null));

    var stored = ArgumentCaptor.forClass(CalendarConsultationOnce.class);
    var order = inOrder(consultants, onces);
    order.verify(consultants).lockConsultant(ME);
    order.verify(onces).save(stored.capture());
    assertThat(stored.getValue().getConsultantId()).isEqualTo(ME);
    assertThat(stored.getValue().getStartAt()).isEqualTo(at("09:00"));
    assertThat(stored.getValue().getEndAt()).isEqualTo(at("11:00"));
    assertThat(stored.getValue().getNoticeMinutes()).isEqualTo(30);
    assertThat(stored.getValue().isOverBlocker()).isFalse();
    assertThat(result.getId()).isNotNull();
    assertThat(result.getStart().toInstant()).isEqualTo(at("09:00"));
    assertThat(result.getEnd().toInstant()).isEqualTo(at("11:00"));
    assertThat(result.getNoticeMinutes()).isEqualTo(30);
    assertThat(result.getOverBlocker()).isFalse();
  }

  @Test
  void create_Should_Refuse_When_TheEndIsNotAfterTheStart() {
    assertBadRequest(request("10:00", "10:00", 0, false));
    assertBadRequest(request("10:00", "09:00", 0, false));
  }

  @Test
  void create_Should_Refuse_When_TheEndIsNotInTheFuture() {
    assertBadRequest(request("06:00", "08:00", 0, false));
  }

  @Test
  void create_Should_AcceptAWindowThatAlreadyStarted_WhileItsEndLiesAhead() {
    assertThat(service.create(request("07:00", "09:00", 0, false)).getId()).isNotNull();
  }

  @Test
  void create_Should_Refuse_When_TheWindowIsLongerThanTwelveHours() {
    var start = at("09:00");
    assertBadRequest(
        new CalendarConsultationOnceCreateDTO()
            .start(start.atOffset(ZoneOffset.UTC))
            .end(start.plus(Duration.ofHours(12)).plusSeconds(60).atOffset(ZoneOffset.UTC))
            .noticeMinutes(0));
    assertThat(
            service
                .create(
                    new CalendarConsultationOnceCreateDTO()
                        .start(start.atOffset(ZoneOffset.UTC))
                        .end(start.plus(Duration.ofHours(12)).atOffset(ZoneOffset.UTC))
                        .noticeMinutes(0))
                .getId())
        .isNotNull();
  }

  @Test
  void create_Should_Refuse_ANoticeThatIsNotOffered() {
    for (var notice : new Integer[] {null, -30, 15, 45, 90, 1441, 2880}) {
      assertBadRequest(request("09:00", "10:00", notice, false));
    }
    for (var notice : List.of(0, 30, 60, 120, 720, 1440)) {
      assertThat(service.create(request("09:00", "10:00", notice, false)).getNoticeMinutes())
          .isEqualTo(notice);
    }
  }

  @Test
  void create_Should_Refuse_When_ItOverlapsAnotherOneOffOfTheCaller() {
    when(onces.findOverlapping(ME, at("09:00"), at("10:00")))
        .thenReturn(List.of(new CalendarConsultationOnce()));

    assertBadRequest(request("09:00", "10:00", 0, false));
  }

  @Test
  void create_Should_Refuse_TheFiftyFirstUpcomingOneOff() {
    when(onces.findByConsultantIdAndEndAtAfterOrderByStartAtAsc(ME, NOW))
        .thenReturn(new ArrayList<>(Collections.nCopies(50, new CalendarConsultationOnce())));

    assertBadRequest(request("09:00", "10:00", 0, false));
  }

  @Test
  void create_Should_Refuse_OverAnOwnBlocker_WithTheReason_UnlessConfirmed() {
    when(blockers.findOverlapping(ME, at("09:00"), at("10:00")))
        .thenReturn(List.of(blocker(CalendarBlocker.Source.ORISO)));

    assertThatThrownBy(() -> service.create(request("09:00", "10:00", 0, false)))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            e -> {
              assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo(HttpStatusExceptionReason.CONSULTATION_ONCE_OVER_BLOCKER.name());
            });
    verify(onces, never()).save(any());

    var result = service.create(request("09:00", "10:00", 0, true));
    assertThat(result.getOverBlocker()).isTrue();
  }

  @Test
  void create_Should_NotAskAboutImportedBusyTimes() {
    when(blockers.findOverlapping(ME, at("09:00"), at("10:00")))
        .thenReturn(List.of(blocker(CalendarBlocker.Source.EXTERNAL)));

    assertThat(service.create(request("09:00", "10:00", 0, false)).getOverBlocker()).isFalse();
  }

  @Test
  void create_Should_BeForCounsellorsOnly() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(() -> service.create(request("09:00", "10:00", 0, false)))
        .isInstanceOf(ForbiddenException.class);
    verify(onces, never()).save(any());
  }

  @Test
  void upcoming_Should_ListTheCallersOneOffsWhoseEndLiesAhead() {
    var once = new CalendarConsultationOnce();
    once.setId(UUID.randomUUID());
    once.setConsultantId(ME);
    once.setStartAt(at("09:00"));
    once.setEndAt(at("10:00"));
    once.setNoticeMinutes(60);
    once.setOverBlocker(true);
    when(onces.findByConsultantIdAndEndAtAfterOrderByStartAtAsc(ME, NOW)).thenReturn(List.of(once));

    var result = service.upcoming();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(once.getId());
    assertThat(result.get(0).getNoticeMinutes()).isEqualTo(60);
    assertThat(result.get(0).getOverBlocker()).isTrue();
  }

  @Test
  void delete_Should_RemoveTheCallersOneOff() {
    var id = UUID.randomUUID();
    var once = new CalendarConsultationOnce();
    when(onces.findByIdAndConsultantId(id, ME)).thenReturn(Optional.of(once));

    service.delete(id);

    verify(onces).delete(once);
  }

  @Test
  void delete_Should_GiveNotFound_ForAnotherCounsellorsOneOff() {
    var id = UUID.randomUUID();
    when(onces.findByIdAndConsultantId(id, ME)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    verify(onces, never()).delete(any());
  }

  private void assertBadRequest(CalendarConsultationOnceCreateDTO request) {
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(BadRequestException.class);
    verify(onces, never()).save(any());
  }

  private static CalendarConsultationOnceCreateDTO request(
      String start, String end, Integer noticeMinutes, Boolean overBlocker) {
    return new CalendarConsultationOnceCreateDTO()
        .start(at(start).atOffset(ZoneOffset.UTC))
        .end(at(end).atOffset(ZoneOffset.UTC))
        .noticeMinutes(noticeMinutes)
        .overBlocker(overBlocker);
  }

  private static CalendarBlocker blocker(CalendarBlocker.Source source) {
    var blocker = new CalendarBlocker();
    blocker.setConsultantId(ME);
    blocker.setStartTime(at("09:30"));
    blocker.setEndTime(at("10:30"));
    blocker.setSource(source);
    return blocker;
  }

  private static Instant at(String time) {
    return Instant.parse("2026-11-24T" + time + ":00Z");
  }
}
