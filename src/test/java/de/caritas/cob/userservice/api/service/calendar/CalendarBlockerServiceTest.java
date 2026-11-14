package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.BlockerCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.BlockerRepeatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.BlockerTimeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.service.calendar.CalendarBlockerService.Slot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarBlockerServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final String ME = "me";

  @Mock CalendarBlockerRepository blockers;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private final List<CalendarBlocker> saved = new ArrayList<>();
  private CalendarBlockerService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setTenantId(3L);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    service =
        new CalendarBlockerService(
            caller, blockers, Clock.fixed(NOW, ZoneOffset.UTC), "Europe/Berlin");
    lenient()
        .when(blockers.save(any()))
        .thenAnswer(
            call -> {
              CalendarBlocker blocker = call.getArgument(0);
              if (blocker.getId() == null) {
                blocker.setId(UUID.randomUUID());
              }
              saved.add(blocker);
              return blocker;
            });
  }

  @Test
  void create_Should_StoreOneBlockerOfTheCaller() {
    var created =
        service.create(
            new BlockerCreateDTO()
                .start(OffsetDateTime.parse("2026-11-16T09:00:00+01:00"))
                .end(OffsetDateTime.parse("2026-11-16T10:00:00+01:00"))
                .allDay(true)
                .note("  Team meeting "));

    assertThat(created).hasSize(1);
    assertThat(created.get(0).getType()).isEqualTo(CalendarEntryType.BLOCKER);
    assertThat(created.get(0).getTitle()).isEqualTo("Team meeting");
    assertThat(created.get(0).getOwnerId()).isEqualTo(ME);
    assertThat(created.get(0).getMovable()).isTrue();
    assertThat(created.get(0).getSeriesId()).isNull();
    var stored = saved.get(0);
    assertThat(stored.getConsultantId()).isEqualTo(ME);
    assertThat(stored.getTenantId()).isEqualTo(3L);
    assertThat(stored.getSource()).isEqualTo(Source.ORISO);
    assertThat(stored.isAllDay()).isTrue();
    assertThat(stored.getStartTime()).isEqualTo(Instant.parse("2026-11-16T08:00:00Z"));
    assertThat(stored.getCreateDate()).isEqualTo(NOW);
  }

  @Test
  void create_Should_Refuse_When_TheTimeIsInvalid() {
    assertThatThrownBy(
            () -> service.create(blocker("2026-11-16T10:00:00Z", "2026-11-16T10:00:00Z")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () -> service.create(blocker("2026-11-16T10:00:00Z", "2026-11-16T09:00:00Z")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () -> service.create(blocker("2026-11-13T09:59:59Z", "2026-11-13T11:00:00Z")))
        .as("more than one day in the past")
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    blocker("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z").note("x".repeat(256))))
        .isInstanceOf(BadRequestException.class);
    verify(blockers, never()).save(any());

    assertThat(service.create(blocker("2026-11-13T10:00:00Z", "2026-11-13T11:00:00Z"))).hasSize(1);
  }

  @Test
  void create_Should_ExpandARepeatIntoOneSeries() {
    // Monday 2026-11-16, repeated on Mondays and Wednesdays through Wednesday 2026-11-25.
    var created =
        service.create(
            blocker("2026-11-16T09:00:00+01:00", "2026-11-16T10:30:00+01:00")
                .repeat(
                    new BlockerRepeatDTO()
                        .weekdays(List.of(1, 3))
                        .until(LocalDate.parse("2026-11-25"))));

    assertThat(created)
        .extracting(entry -> entry.getStart().toInstant())
        .containsExactly(
            Instant.parse("2026-11-16T08:00:00Z"),
            Instant.parse("2026-11-18T08:00:00Z"),
            Instant.parse("2026-11-23T08:00:00Z"),
            Instant.parse("2026-11-25T08:00:00Z"));
    assertThat(created).extracting(entry -> entry.getSeriesId()).doesNotContainNull();
    assertThat(created.stream().map(entry -> entry.getSeriesId()).distinct()).hasSize(1);
    assertThat(saved).allMatch(blocker -> blocker.getSeriesId() != null);
  }

  @Test
  void expand_Should_KeepTheClockTimeAcrossTheChangeToSummerTime() {
    // Friday 2027-03-26 09:00 Berlin (CET, UTC+1); the clocks change on Sunday 2027-03-28.
    var slots =
        CalendarBlockerService.expand(
            Instant.parse("2027-03-26T08:00:00Z"),
            Instant.parse("2027-03-26T09:00:00Z"),
            new BlockerRepeatDTO()
                .weekdays(List.of(0, 1, 5, 6))
                .until(LocalDate.parse("2027-03-29")),
            BERLIN);

    assertThat(slots)
        .containsExactly(
            new Slot(Instant.parse("2027-03-26T08:00:00Z"), Instant.parse("2027-03-26T09:00:00Z")),
            new Slot(Instant.parse("2027-03-27T08:00:00Z"), Instant.parse("2027-03-27T09:00:00Z")),
            new Slot(Instant.parse("2027-03-28T07:00:00Z"), Instant.parse("2027-03-28T08:00:00Z")),
            new Slot(Instant.parse("2027-03-29T07:00:00Z"), Instant.parse("2027-03-29T08:00:00Z")));
  }

  @Test
  void expand_Should_KeepTheClockTimeAcrossTheChangeToWinterTime() {
    // Saturday 2026-10-24 09:00 Berlin (CEST, UTC+2); the clocks change on Sunday 2026-10-25.
    var slots =
        CalendarBlockerService.expand(
            Instant.parse("2026-10-24T07:00:00Z"),
            Instant.parse("2026-10-24T08:00:00Z"),
            new BlockerRepeatDTO().weekdays(List.of(0)).until(LocalDate.parse("2026-10-25")),
            BERLIN);

    assertThat(slots)
        .containsExactly(
            new Slot(Instant.parse("2026-10-24T07:00:00Z"), Instant.parse("2026-10-24T08:00:00Z")),
            new Slot(Instant.parse("2026-10-25T08:00:00Z"), Instant.parse("2026-10-25T09:00:00Z")));
  }

  @Test
  void expand_Should_KeepTheLength_When_TheClockTimeIsSkipped() {
    // 02:00-03:00 does not exist on 2027-03-28 in Berlin.
    var slots =
        CalendarBlockerService.expand(
            Instant.parse("2027-03-27T01:00:00Z"),
            Instant.parse("2027-03-27T02:00:00Z"),
            new BlockerRepeatDTO().weekdays(List.of(0)).until(LocalDate.parse("2027-03-28")),
            BERLIN);

    assertThat(slots).hasSize(2);
    assertThat(slots.get(1).end()).isEqualTo(slots.get(1).start().plusSeconds(3600));
  }

  @Test
  void expand_Should_AllowExactly120_And_RefuseMore() {
    var start = Instant.parse("2026-11-16T08:00:00Z");
    var end = Instant.parse("2026-11-16T09:00:00Z");
    var everyDay = List.of(0, 1, 2, 3, 4, 5, 6);
    var firstDay = LocalDate.parse("2026-11-16");

    assertThat(
            CalendarBlockerService.expand(
                start,
                end,
                new BlockerRepeatDTO().weekdays(everyDay).until(firstDay.plusDays(119)),
                BERLIN))
        .hasSize(120);
    assertThatThrownBy(
            () ->
                CalendarBlockerService.expand(
                    start,
                    end,
                    new BlockerRepeatDTO().weekdays(everyDay).until(firstDay.plusDays(120)),
                    BERLIN))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                CalendarBlockerService.expand(
                    start,
                    end,
                    new BlockerRepeatDTO()
                        .weekdays(List.of(1))
                        .until(LocalDate.parse("9999-12-31")),
                    BERLIN))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void expand_Should_RefuseAnInvalidRepeat() {
    var start = Instant.parse("2026-11-16T08:00:00Z");
    var end = Instant.parse("2026-11-16T09:00:00Z");

    for (var repeat :
        List.of(
            new BlockerRepeatDTO().weekdays(List.of()).until(LocalDate.parse("2026-11-30")),
            new BlockerRepeatDTO().weekdays(List.of(7)).until(LocalDate.parse("2026-11-30")),
            new BlockerRepeatDTO().weekdays(List.of(-1)).until(LocalDate.parse("2026-11-30")),
            new BlockerRepeatDTO().weekdays(List.of(1)).until(LocalDate.parse("2026-11-15")),
            new BlockerRepeatDTO().weekdays(List.of(1)))) {
      assertThatThrownBy(() -> CalendarBlockerService.expand(start, end, repeat, BERLIN))
          .isInstanceOf(BadRequestException.class);
    }
  }

  @Test
  void expand_Should_KeepTheFirstDay_EvenWhen_ItsWeekdayIsNotChosen() {
    // Monday start, repeated on Tuesdays only.
    var slots =
        CalendarBlockerService.expand(
            Instant.parse("2026-11-16T08:00:00Z"),
            Instant.parse("2026-11-16T09:00:00Z"),
            new BlockerRepeatDTO().weekdays(List.of(2)).until(LocalDate.parse("2026-11-24")),
            BERLIN);

    assertThat(slots)
        .extracting(Slot::start)
        .containsExactly(
            Instant.parse("2026-11-16T08:00:00Z"),
            Instant.parse("2026-11-17T08:00:00Z"),
            Instant.parse("2026-11-24T08:00:00Z"));
  }

  @Test
  void moveAndDelete_Should_ReachOnlyTheCallersOwnBlockers() {
    var id = UUID.randomUUID();
    var own = new CalendarBlocker();
    own.setId(id);
    own.setConsultantId(ME);
    own.setSource(Source.ORISO);
    own.setStartTime(Instant.parse("2026-11-16T08:00:00Z"));
    own.setEndTime(Instant.parse("2026-11-16T09:00:00Z"));
    when(blockers.findByIdAndConsultantIdAndSource(id, ME, Source.ORISO))
        .thenReturn(Optional.of(own));
    var time =
        new BlockerTimeDTO()
            .start(OffsetDateTime.parse("2026-11-17T08:00:00Z"))
            .end(OffsetDateTime.parse("2026-11-17T09:00:00Z"));

    var moved = service.move(id, time);

    assertThat(moved.getId()).isEqualTo(id.toString());
    assertThat(moved.getStart().toInstant()).isEqualTo(Instant.parse("2026-11-17T08:00:00Z"));
    assertThatThrownBy(
            () -> service.move(id, new BlockerTimeDTO().start(time.getEnd()).end(time.getStart())))
        .isInstanceOf(BadRequestException.class);
    service.delete(id);
    verify(blockers).delete(own);

    // Somebody else's blocker, an imported busy time and an unknown id all read as not found.
    var foreign = UUID.randomUUID();
    when(blockers.findByIdAndConsultantIdAndSource(foreign, ME, Source.ORISO))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.move(foreign, time)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.delete(foreign)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void asker_Should_NotBlockTime() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(
            () -> service.create(blocker("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z")))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.delete(UUID.randomUUID()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.move(UUID.randomUUID(), new BlockerTimeDTO()))
        .isInstanceOf(ForbiddenException.class);
  }

  private static BlockerCreateDTO blocker(String start, String end) {
    return new BlockerCreateDTO().start(OffsetDateTime.parse(start)).end(OffsetDateTime.parse(end));
  }
}
