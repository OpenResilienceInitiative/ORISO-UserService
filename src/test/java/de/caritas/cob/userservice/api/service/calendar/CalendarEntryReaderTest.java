package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.SessionAppointment.Kind;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class CalendarEntryReaderTest {

  private static final String ME = "me";
  private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-12-01T00:00:00Z");

  @Mock SessionAppointmentRepository appointments;
  @Mock CaseHandoverService caseHandovers;
  @Mock ChatRepository chats;
  @Mock GroupChatParticipantRepository participants;
  @Mock ChatOccurrenceExceptionRepository occurrenceExceptions;
  @Mock CalendarBlockerRepository blockers;

  private CalendarEntryReader reader;

  @BeforeEach
  void setUp() {
    reader =
        new CalendarEntryReader(
            appointments, caseHandovers, chats, participants, occurrenceExceptions, blockers);
    lenient().when(occurrenceExceptions.findBySeries_IdIn(any())).thenReturn(List.of());
  }

  @Test
  void groupOccurrences_Should_ProjectOwnedAndJoinedSeries_WithStableIds() {
    var owned = weekly(7L, "Owned group", "2026-10-28T17:00:00", 4);
    var joined = weekly(8L, "Joined group", "2026-11-12T10:00:00", 1);
    var internal = weekly(9L, "Me, Colleague", "2026-11-12T10:00:00", 1);
    internal.setConversationType(ConversationType.INTERNAL_GROUP);
    when(chats.findByChatOwnerId(ME)).thenReturn(List.of(owned, internal));
    when(participants.findByConsultantId(ME))
        .thenReturn(
            List.of(
                participant(7L),
                participant(8L),
                GroupChatParticipant.builder().chatId(1L).build()));
    when(chats.findAllById(Set.of(7L, 8L))).thenReturn(List.of(owned, joined));
    var skipped = new ChatOccurrenceException();
    skipped.setSeries(owned);
    skipped.setOriginalOccurrenceStartUtc(LocalDateTime.parse("2026-11-11T17:00:00"));
    skipped.setExceptionType(ExceptionType.SKIP);
    when(occurrenceExceptions.findBySeries_IdIn(List.of(7L, 8L))).thenReturn(List.of(skipped));

    var entries = reader.groupOccurrencesOfConsultant(ME, FROM, TO);

    // Index 0 (28 October) lies before the window, index 2 (11 November) is skipped.
    assertThat(entries)
        .extracting(CalendarEntry::id)
        .containsExactly("chat-7-1", "chat-7-3", "chat-8-0");
    var first = entries.get(0);
    assertThat(first.type()).isEqualTo(CalendarEntryType.GROUP);
    assertThat(first.title()).isEqualTo("Owned group");
    assertThat(first.start()).isEqualTo(Instant.parse("2026-11-04T17:00:00Z"));
    assertThat(first.end()).isEqualTo(Instant.parse("2026-11-04T18:30:00Z"));
    assertThat(first.sessionId()).isNull();
  }

  @Test
  void groupOccurrences_Should_ReadTheExceptionsOfEverySeriesInOneQuery() {
    var first = weekly(7L, "First", "2026-11-04T17:00:00", 2);
    var second = weekly(8L, "Second", "2026-11-05T10:00:00", 2);
    var third = weekly(9L, "Third", "2026-11-06T10:00:00", 1);
    when(chats.findByChatOwnerId(ME)).thenReturn(List.of(first, second, third));
    var skipped = new ChatOccurrenceException();
    skipped.setSeries(second);
    skipped.setOriginalOccurrenceStartUtc(LocalDateTime.parse("2026-11-12T10:00:00"));
    skipped.setExceptionType(ExceptionType.SKIP);
    when(occurrenceExceptions.findBySeries_IdIn(List.of(7L, 8L, 9L))).thenReturn(List.of(skipped));

    var entries = reader.groupOccurrencesOfConsultant(ME, FROM, TO);

    // The exception skips the second occurrence of its own series only.
    assertThat(entries)
        .extracting(CalendarEntry::id)
        .containsExactlyInAnyOrder("chat-7-0", "chat-7-1", "chat-8-0", "chat-9-0");
    verify(occurrenceExceptions).findBySeries_IdIn(List.of(7L, 8L, 9L));
    verifyNoMoreInteractions(occurrenceExceptions);
  }

  @Test
  void appointments_Should_Fail_When_TheWindowHoldsMoreThanTheLimit() {
    var limit = CalendarEntryReader.APPOINTMENT_LIMIT;
    var consultantPages = ArgumentCaptor.forClass(Pageable.class);
    var askerPages = ArgumentCaptor.forClass(Pageable.class);
    when(appointments.findScheduledOfConsultantBetween(
            eq(ME), any(), eq(FROM), eq(TO), consultantPages.capture()))
        .thenReturn(Collections.nCopies(limit, appointment()))
        .thenReturn(Collections.nCopies(limit + 1, appointment()));
    when(appointments.findScheduledOfAskerBetween(
            eq("asker"), eq(FROM), eq(TO), askerPages.capture()))
        .thenReturn(Collections.nCopies(limit + 1, appointment()));

    assertThat(reader.appointmentsOfConsultant(ME, FROM, TO)).hasSize(limit);
    // A cut window would show free time where an appointment is.
    assertThatThrownBy(() -> reader.appointmentsOfConsultant(ME, FROM, TO))
        .isInstanceOf(InternalServerErrorException.class);
    assertThatThrownBy(() -> reader.appointmentsOfAsker("asker", FROM, TO))
        .isInstanceOf(InternalServerErrorException.class);
    // Each query asks for one row more than the limit, from the first row: that row shows overflow.
    for (var pages : List.of(consultantPages.getAllValues(), askerPages.getAllValues())) {
      assertThat(pages)
          .isNotEmpty()
          .allSatisfy(
              pageable -> {
                assertThat(pageable.getPageNumber()).isZero();
                assertThat(pageable.getPageSize()).isEqualTo(limit + 1);
              });
    }
  }

  @Test
  void groupOccurrences_Should_IncludeOneStillRunningAtTheWindowStart() {
    var running = weekly(7L, "Running", "2026-10-31T23:30:00", 1);
    when(chats.findByChatOwnerId(ME)).thenReturn(List.of(running));

    assertThat(reader.groupOccurrencesOfConsultant(ME, FROM, TO))
        .extracting(CalendarEntry::id)
        .containsExactly("chat-7-0");
  }

  @Test
  void groupOccurrences_Should_CoverAWindowLongerThanOneProjection() {
    var daily = weekly(7L, "Daily", "2026-11-01T08:00:00", 300);
    daily.setChatInterval(ChatInterval.DAILY);
    when(chats.findByChatOwnerId(ME)).thenReturn(List.of(daily));

    var entries =
        reader.groupOccurrencesOfConsultant(ME, FROM, Instant.parse("2027-05-30T00:00:00Z"));

    assertThat(entries).hasSize(210);
    assertThat(entries.stream().map(CalendarEntry::id).distinct()).hasSize(210);
  }

  @Test
  void groupOccurrencesOfAsker_Should_UseTheChatsTheAskerJoined() {
    when(chats.findJoinedByUserId("asker"))
        .thenReturn(List.of(weekly(8L, "Joined group", "2026-11-12T10:00:00", 1)));

    assertThat(reader.groupOccurrencesOfAsker("asker", FROM, TO))
        .extracting(CalendarEntry::id)
        .containsExactly("chat-8-0");
  }

  @Test
  void appointments_Should_CarryTheCounterpartOfTheView() {
    var appointment = appointment();
    when(caseHandovers.sessionIdsWithActiveCoAccess(ME)).thenReturn(Set.of(99L));
    when(appointments.findScheduledOfConsultantBetween(
            eq(ME), eq(Set.of(99L)), eq(FROM), eq(TO), any()))
        .thenReturn(List.of(appointment));
    when(appointments.findScheduledOfAskerBetween(eq("asker"), eq(FROM), eq(TO), any()))
        .thenReturn(List.of(appointment));

    var mine = reader.appointmentsOfConsultant(ME, FROM, TO).get(0);
    var askers = reader.appointmentsOfAsker("asker", FROM, TO).get(0);

    assertThat(mine.id()).isEqualTo(appointment.getId().toString());
    assertThat(mine.type()).isEqualTo(CalendarEntryType.APPOINTMENT);
    assertThat(mine.title()).isEqualTo("asker-name");
    assertThat(mine.sessionId()).isEqualTo(55L);
    assertThat(mine.kind()).isEqualTo(Kind.ONSITE);
    assertThat(askers.title()).isEqualTo("Public Name");
    assertThat(askers.sessionId()).isEqualTo(55L);
  }

  @Test
  void blockers_Should_KeepNoteAndSeriesOnlyForOwnBlockers() {
    var own = blocker(Source.ORISO);
    var imported = blocker(Source.EXTERNAL);
    when(blockers.findOverlapping(ME, FROM, TO)).thenReturn(List.of(own, imported));

    var entries = reader.blockersOf(ME, FROM, TO);

    assertThat(entries.get(0).type()).isEqualTo(CalendarEntryType.BLOCKER);
    assertThat(entries.get(0).title()).isEqualTo("note");
    assertThat(entries.get(0).seriesId()).isEqualTo(own.getSeriesId().toString());
    assertThat(entries.get(1).type()).isEqualTo(CalendarEntryType.BUSY);
    assertThat(entries.get(1).title()).isNull();
    assertThat(entries.get(1).seriesId()).isNull();
  }

  private static Chat weekly(long id, String topic, String firstStartUtc, int repeatCount) {
    return Chat.builder()
        .id(id)
        .topic(topic)
        .initialStartDate(LocalDateTime.parse(firstStartUtc))
        .startDate(LocalDateTime.parse(firstStartUtc))
        .duration(90)
        .repeatCount(repeatCount)
        .repetitive(repeatCount > 1)
        .chatInterval(ChatInterval.WEEKLY)
        .timezone("UTC")
        .conversationType(ConversationType.SELF_HELP)
        .build();
  }

  private static GroupChatParticipant participant(long seriesId) {
    return GroupChatParticipant.builder().chatId(1L).seriesId(seriesId).consultantId(ME).build();
  }

  /**
   * ORISO-Frontend#2252: a direct invitation nobody opened yet still takes the counsellor's time.
   */
  @Test
  void appointmentsOfConsultant_Should_IncludeUnopenedInvitations_WithoutAskerOrSession() {
    var opened = appointment();
    var unopened = appointment();
    unopened.setSession(null);
    unopened.setKind(null);
    when(appointments.findScheduledOfConsultantBetween(eq(ME), any(), eq(FROM), eq(TO), any()))
        .thenReturn(List.of(opened));
    when(appointments.findUnopenedInvitationsOfConsultantBetween(eq(ME), eq(FROM), eq(TO), any()))
        .thenReturn(List.of(unopened));

    var entries = reader.appointmentsOfConsultant(ME, FROM, TO);

    assertThat(entries)
        .extracting(CalendarEntry::id)
        .containsExactly(opened.getId().toString(), unopened.getId().toString());
    var invitation = entries.get(1);
    assertThat(invitation.type()).isEqualTo(CalendarEntryType.APPOINTMENT);
    assertThat(invitation.title()).isNull();
    assertThat(invitation.sessionId()).isNull();
  }

  @Test
  void appointmentsOfConsultant_Should_Fail_When_UnopenedInvitationsTakeTheWindowOverTheLimit() {
    var limit = CalendarEntryReader.APPOINTMENT_LIMIT;
    var unopened = appointment();
    unopened.setSession(null);
    when(appointments.findScheduledOfConsultantBetween(eq(ME), any(), eq(FROM), eq(TO), any()))
        .thenReturn(Collections.nCopies(limit, appointment()));
    when(appointments.findUnopenedInvitationsOfConsultantBetween(eq(ME), eq(FROM), eq(TO), any()))
        .thenReturn(List.of(unopened));

    assertThatThrownBy(() -> reader.appointmentsOfConsultant(ME, FROM, TO))
        .isInstanceOf(InternalServerErrorException.class);
  }

  private static SessionAppointment appointment() {
    var consultant = new Consultant();
    consultant.setId(ME);
    consultant.setUsername("counsellor");
    consultant.setDisplayName("Public Name");
    var session = new Session();
    session.setId(55L);
    session.setUser(new User("asker", null, "asker-name", "asker@synthetic.oriso.test", false));
    session.setConsultant(consultant);
    var appointment = new SessionAppointment();
    appointment.setId(UUID.randomUUID());
    appointment.setSession(session);
    appointment.setStartTime(Instant.parse("2026-11-20T09:00:00Z"));
    appointment.setEndTime(Instant.parse("2026-11-20T10:00:00Z"));
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    appointment.setKind(Kind.ONSITE);
    return appointment;
  }

  private static CalendarBlocker blocker(Source source) {
    var blocker = new CalendarBlocker();
    blocker.setId(UUID.randomUUID());
    blocker.setConsultantId(ME);
    blocker.setSource(source);
    blocker.setNote("note");
    blocker.setSeriesId(UUID.randomUUID());
    blocker.setStartTime(Instant.parse("2026-11-10T08:00:00Z"));
    blocker.setEndTime(Instant.parse("2026-11-10T09:00:00Z"));
    return blocker;
  }
}
