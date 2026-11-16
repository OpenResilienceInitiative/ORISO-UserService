package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentKind;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarColleagueDTO.RelationEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.SessionAppointment.Kind;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Colleague;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Listing;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Relation;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
  private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-01T00:00:00Z");
  private static final OffsetDateTime TO = OffsetDateTime.parse("2026-12-01T00:00:00Z");
  private static final String ME = "me";
  private static final String COLLEAGUE = "colleague";

  @Mock CalendarEntryReader reader;
  @Mock CalendarColleagueScope colleagues;
  @Mock AgencyService agencyService;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private CalendarService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setTenantId(1L);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    service =
        new CalendarService(
            caller, reader, colleagues, agencyService, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void window_Should_BeRefused_When_NotPositiveOrLongerThan62Days() {
    assertThatThrownBy(() -> service.ownEntries(TO, FROM)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.ownEntries(FROM, FROM))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.ownEntries(FROM, FROM.plusDays(62).plusSeconds(1)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.colleagueEntries(COLLEAGUE, FROM, FROM.plusDays(63)))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(reader);

    assertThat(service.ownEntries(FROM, FROM.plusDays(62))).isEmpty();
  }

  @Test
  void ownEntries_Should_ShowEverything_And_SayWhatCanBeMoved() {
    stageCalendarOf(ME);

    var entries = service.ownEntries(FROM, TO);

    assertThat(entries)
        .extracting(CalendarEntryDTO::getId)
        .containsExactly("past", "busy", "blocker", "appointment", "chat-7-0");
    assertThat(entries).allMatch(entry -> entry.getOwnerId().equals(ME));
    var appointment = entries.get(3);
    assertThat(appointment.getType()).isEqualTo(CalendarEntryType.APPOINTMENT);
    assertThat(appointment.getTitle()).isEqualTo("asker-name");
    assertThat(appointment.getSessionId()).isEqualTo(55L);
    assertThat(appointment.getKind()).isEqualTo(AppointmentKind.PHONE);
    assertThat(appointment.getMovable()).isTrue();
    assertThat(entries.get(0).getMovable()).as("a past appointment").isFalse();
    assertThat(entries.get(1).getType()).isEqualTo(CalendarEntryType.BUSY);
    assertThat(entries.get(1).getTitle()).isNull();
    assertThat(entries.get(1).getMovable()).isFalse();
    assertThat(entries.get(2).getTitle()).isEqualTo("private note");
    assertThat(entries.get(2).getSeriesId()).isEqualTo("series-1");
    assertThat(entries.get(2).getMovable()).isTrue();
    assertThat(entries.get(4).getTitle()).isEqualTo("group topic");
    assertThat(entries.get(4).getMovable()).isFalse();
  }

  /** ORISO-Frontend#2252: an unopened direct invitation moves with its invitation, not here. */
  @Test
  void ownEntries_Should_NotOfferToMoveAnUnopenedInvitation() {
    var unopened =
        new CalendarEntry(
            "invitation",
            CalendarEntryType.APPOINTMENT,
            Instant.parse("2026-11-20T09:00:00Z"),
            Instant.parse("2026-11-20T10:00:00Z"),
            null,
            null,
            null,
            null);
    when(reader.appointmentsOfConsultant(eq(ME), any(), any())).thenReturn(List.of(unopened));

    var entries = service.ownEntries(FROM, TO);

    assertThat(entries).extracting(CalendarEntryDTO::getId).containsExactly("invitation");
    assertThat(entries.get(0).getMovable()).isFalse();
    assertThat(entries.get(0).getSessionId()).isNull();
  }

  @Test
  void askerEntries_Should_BeReadOnly_And_WithoutBlockers() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));
    when(reader.appointmentsOfAsker(eq(ME), any(), any()))
        .thenReturn(List.of(appointment("appointment", "2026-11-20T09:00:00Z", "Counsellor")));
    when(reader.groupOccurrencesOfAsker(eq(ME), any(), any())).thenReturn(List.of(group()));

    var entries = service.ownEntries(FROM, TO);

    assertThat(entries)
        .extracting(CalendarEntryDTO::getId)
        .containsExactly("appointment", "chat-7-0");
    assertThat(entries).noneMatch(CalendarEntryDTO::getMovable);
    assertThat(entries.get(0).getTitle()).isEqualTo("Counsellor");
    assertThat(entries.get(0).getOwnerId()).isEqualTo(ME);
  }

  @Test
  void asker_Should_NotReachColleagues() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(() -> service.colleagues(null)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.colleagueEntries(COLLEAGUE, FROM, TO))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(colleagues, reader);
  }

  @Test
  void colleagueEntries_Should_AnswerNotFound_When_OutsideTheScope() {
    when(colleagues.find(ME, COLLEAGUE)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.colleagueEntries(COLLEAGUE, FROM, TO))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(reader);
  }

  @Test
  void colleagueEntries_Should_ShowTimesTypeAndKindOnly() throws Exception {
    when(colleagues.find(ME, COLLEAGUE)).thenReturn(Optional.of(colleague(false)));
    stageCalendarOf(COLLEAGUE);

    var entries = service.colleagueEntries(COLLEAGUE, FROM, TO);

    assertThat(entries)
        .extracting(CalendarEntryDTO::getType)
        .containsExactly(
            CalendarEntryType.APPOINTMENT,
            CalendarEntryType.BLOCKER,
            CalendarEntryType.BLOCKER,
            CalendarEntryType.APPOINTMENT,
            CalendarEntryType.GROUP);
    assertThat(entries).allMatch(entry -> entry.getOwnerId().equals(COLLEAGUE));
    assertThat(entries).noneMatch(CalendarEntryDTO::getMovable);
    assertThat(entries.get(3).getKind()).isEqualTo(AppointmentKind.PHONE);
    var json = json(entries);
    assertThat(json)
        .doesNotContain("sessionId", "seriesId", "title", "series-1")
        .doesNotContain("asker-name", "private note", "group topic", "BUSY");
    for (Map<String, Object> entry : parsed(json)) {
      assertThat(entry.keySet())
          .isSubsetOf("id", "type", "start", "end", "ownerId", "kind", "movable");
    }
  }

  @Test
  void colleagueEntries_Should_NameTheAskerOnAppointmentsOnly_When_TheTraegerShowsUsernames()
      throws Exception {
    when(colleagues.find(ME, COLLEAGUE)).thenReturn(Optional.of(colleague(true)));
    stageCalendarOf(COLLEAGUE);

    var entries = service.colleagueEntries(COLLEAGUE, FROM, TO);

    assertThat(entries.get(3).getTitle()).isEqualTo("asker-name");
    assertThat(entries.get(1).getTitle()).isNull();
    assertThat(entries.get(2).getTitle()).isNull();
    assertThat(entries.get(4).getTitle()).isNull();
    assertThat(json(entries))
        .doesNotContain("sessionId", "seriesId", "private note", "group topic", "series-1");
  }

  @Test
  void colleagues_Should_CarryScopeRelationAndAgencyName() {
    var foreign = colleague(false);
    when(colleagues.list(ME, "an", CalendarService.COLLEAGUE_LIMIT))
        .thenReturn(
            new Listing(
                new CalendarPolicy(CalendarVisibilityScope.TENANT, true),
                List.of(
                    foreign,
                    new Colleague(
                        consultant("unnamed", 1L),
                        "No Centre",
                        Relation.SAME_TENANT,
                        null,
                        true))));
    when(agencyService.getAgencies(anyList()))
        .thenReturn(List.of(new AgencyDTO().id(10L).name("Centre North")));

    var result = service.colleagues("an");

    assertThat(result.getVisibilityScope().name()).isEqualTo("TENANT");
    assertThat(result.getShowAskerUsernames()).isTrue();
    assertThat(result.getColleagues()).hasSize(2);
    assertThat(result.getColleagues().get(0).getId()).isEqualTo(COLLEAGUE);
    assertThat(result.getColleagues().get(0).getDisplayName()).isEqualTo("Colleague Name");
    assertThat(result.getColleagues().get(0).getAgencyName()).isEqualTo("Centre North");
    assertThat(result.getColleagues().get(0).getRelation()).isEqualTo(RelationEnum.SAME_AGENCY);
    assertThat(result.getColleagues().get(1).getAgencyName()).isEmpty();
  }

  @Test
  void colleagues_Should_StillBeListed_When_TheAgencyServiceDoesNotAnswer() {
    when(colleagues.list(ME, null, CalendarService.COLLEAGUE_LIMIT))
        .thenReturn(new Listing(CalendarPolicy.CLOSED, List.of(colleague(false))));
    when(agencyService.getAgencies(anyList())).thenThrow(new IllegalStateException("down"));

    var result = service.colleagues(null);

    assertThat(result.getColleagues()).hasSize(1);
    assertThat(result.getColleagues().get(0).getAgencyName()).isEmpty();
  }

  private void stageCalendarOf(String consultantId) {
    lenient()
        .when(reader.appointmentsOfConsultant(eq(consultantId), any(), any()))
        .thenReturn(
            List.of(
                appointment("appointment", "2026-11-20T09:00:00Z", "asker-name"),
                appointment("past", "2026-11-02T09:00:00Z", "asker-name")));
    lenient()
        .when(reader.groupOccurrencesOfConsultant(eq(consultantId), any(), any()))
        .thenReturn(List.of(group()));
    lenient()
        .when(reader.blockersOf(eq(consultantId), any(), any()))
        .thenReturn(
            List.of(
                new CalendarEntry(
                    "blocker",
                    CalendarEntryType.BLOCKER,
                    Instant.parse("2026-11-10T08:00:00Z"),
                    Instant.parse("2026-11-10T09:00:00Z"),
                    null,
                    "private note",
                    null,
                    "series-1"),
                new CalendarEntry(
                    "busy",
                    CalendarEntryType.BUSY,
                    Instant.parse("2026-11-05T08:00:00Z"),
                    Instant.parse("2026-11-05T09:00:00Z"),
                    null,
                    null,
                    null,
                    null)));
  }

  private static CalendarEntry appointment(String id, String start, String title) {
    return new CalendarEntry(
        id,
        CalendarEntryType.APPOINTMENT,
        Instant.parse(start),
        Instant.parse(start).plusSeconds(3600),
        Kind.PHONE,
        title,
        55L,
        null);
  }

  private static CalendarEntry group() {
    return new CalendarEntry(
        "chat-7-0",
        CalendarEntryType.GROUP,
        Instant.parse("2026-11-25T17:00:00Z"),
        Instant.parse("2026-11-25T18:00:00Z"),
        null,
        "group topic",
        null,
        null);
  }

  private static Colleague colleague(boolean showAskerUsernames) {
    return new Colleague(
        consultant(COLLEAGUE, 1L), "Colleague Name", Relation.SAME_AGENCY, 10L, showAskerUsernames);
  }

  private static Consultant consultant(String id, Long tenantId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenantId);
    return consultant;
  }

  private static String json(List<CalendarEntryDTO> entries) throws Exception {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .writeValueAsString(entries);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> parsed(String json) throws Exception {
    return new ObjectMapper().readValue(json, List.class);
  }
}
