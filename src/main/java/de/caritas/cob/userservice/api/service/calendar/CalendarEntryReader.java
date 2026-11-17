package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.chat.ChatOccurrenceProjector;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Reads what is in a calendar (ADR-032) from the tables that own it: 1:1 appointments, the
 * projected occurrences of group chats, blockers and imported busy times. It decides nothing about
 * who may see what; the callers do.
 */
@Component
@RequiredArgsConstructor
class CalendarEntryReader {

  /**
   * The most appointments one window returns: far more than a counsellor has in the longest window
   * (the feed's seven months). A window with more fails instead of returning part of it.
   */
  static final int APPOINTMENT_LIMIT = 5_000;

  // The projector hands out at most 100 occurrences per call; 63 days of a daily series fit.
  private static final Duration SLICE = Duration.ofDays(62);
  private static final int SLICE_LIMIT = 100;
  // An occurrence that began before the window may still run into it.
  private static final Duration LOOK_BACK = Duration.ofDays(1);

  private final SessionAppointmentRepository appointments;
  private final CaseHandoverService caseHandovers;
  private final ChatRepository chats;
  private final GroupChatParticipantRepository participants;
  private final ChatOccurrenceExceptionRepository occurrenceExceptions;
  private final CalendarBlockerRepository blockers;
  private final ConsultantRepository consultants;
  private final ChatOccurrenceProjector projector = new ChatOccurrenceProjector();
  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  /**
   * Scheduled appointments of the counsellor's own and co-access sessions (title = asker), and of
   * the counsellor's direct invitations nobody opened yet (ORISO-Frontend#2252: no asker and no
   * conversation yet, so neither title nor session).
   */
  List<CalendarEntry> appointmentsOfConsultant(String consultantId, Instant from, Instant to) {
    var page = PageRequest.of(0, APPOINTMENT_LIMIT + 1);
    var found =
        new ArrayList<CalendarEntry>(
            appointments
                .findScheduledOfConsultantBetween(
                    consultantId,
                    caseHandovers.sessionIdsWithActiveCoAccess(consultantId),
                    from,
                    to,
                    page)
                .stream()
                .map(
                    appointment ->
                        appointment(
                            appointment,
                            usernameTranscoder.decodeUsername(
                                appointment.getSession().getUser().getUsername())))
                .toList());
    appointments
        .findUnopenedInvitationsOfConsultantBetween(consultantId, from, to, page)
        .forEach(appointment -> found.add(appointment(appointment, null)));
    return complete(found);
  }

  /**
   * The advice seekers' requests waiting for this counsellor in their own conversations
   * (ORISO-Frontend#2287), marked requested; title = asker. Only the counsellor's own view reads
   * them: not a colleague's view, not co-access, not the subscription feed.
   */
  List<CalendarEntry> requestsOfConsultant(String consultantId, Instant from, Instant to) {
    return complete(appointments.findRequestedOfConsultantBetween(consultantId, from, to)).stream()
        .map(
            appointment ->
                new CalendarEntry(
                    appointment.getId().toString(),
                    CalendarEntryType.APPOINTMENT,
                    appointment.getStartTime(),
                    appointment.getEndTime(),
                    appointment.getKind(),
                    usernameTranscoder.decodeUsername(
                        appointment.getSession().getUser().getUsername()),
                    appointment.getSession().getId(),
                    null,
                    false,
                    true))
        .toList();
  }

  /** One appointment as a counsellor's own calendar shows it; title = asker. */
  CalendarEntry appointmentOfConsultant(SessionAppointment appointment) {
    return appointment(
        appointment,
        usernameTranscoder.decodeUsername(appointment.getSession().getUser().getUsername()));
  }

  /** Scheduled appointments of the asker's sessions; title = the counsellor's public name. */
  List<CalendarEntry> appointmentsOfAsker(String userId, Instant from, Instant to) {
    return complete(
            appointments.findScheduledOfAskerBetween(
                userId, from, to, PageRequest.of(0, APPOINTMENT_LIMIT + 1)))
        .stream()
        .map(
            appointment ->
                appointment(appointment, publicNameOf(appointment.getSession().getConsultant())))
        .toList();
  }

  /**
   * One more than the limit is read to tell a full window from a cut one: a calendar that shows
   * free time where an appointment was left out is worse than none.
   */
  private static <T> List<T> complete(List<T> found) {
    if (found.size() > APPOINTMENT_LIMIT) {
      throw new InternalServerErrorException(
          "A calendar window holds more than " + APPOINTMENT_LIMIT + " appointments.");
    }
    return found;
  }

  /** Occurrences of the group chats the counsellor owns or takes part in; title = topic. */
  List<CalendarEntry> groupOccurrencesOfConsultant(String consultantId, Instant from, Instant to) {
    var seriesIds =
        participants.findByConsultantId(consultantId).stream()
            .map(GroupChatParticipant::getSeriesId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    // Membership is settled by the counsellor's id; the chat itself may belong to another Träger.
    List<Chat> found =
        TenantContext.supplyAcrossTenants(
            () -> {
              var all = new ArrayList<>(chats.findByChatOwnerId(consultantId));
              if (!seriesIds.isEmpty()) {
                chats.findAllById(seriesIds).forEach(all::add);
              }
              return all;
            });
    return occurrencesOf(found, from, to);
  }

  /** Occurrences of the group chats the asker has joined; title = topic. */
  List<CalendarEntry> groupOccurrencesOfAsker(String userId, Instant from, Instant to) {
    List<Chat> found = TenantContext.supplyAcrossTenants(() -> chats.findJoinedByUserId(userId));
    return occurrencesOf(found, from, to);
  }

  /** The counsellor's blockers (title = note, with series) and imported busy times (bare). */
  List<CalendarEntry> blockersOf(String consultantId, Instant from, Instant to) {
    return blockers.findOverlapping(consultantId, from, to).stream()
        .map(CalendarEntryReader::blocker)
        .toList();
  }

  /**
   * The counsellor's planned absence (ORISO-Frontend#2303) as one all-day entry over its days,
   * clipped to the window. An absence without dates has no days to show; one switched off has none.
   */
  List<CalendarEntry> absenceOf(String consultantId, Instant from, Instant to) {
    return consultants
        .findById(consultantId)
        .filter(Consultant::isAbsenceSwitchedOn)
        .filter(c -> c.getAbsenceStart() != null || c.getAbsenceEnd() != null)
        .map(
            c -> {
              var start =
                  c.getAbsenceStart() == null
                      ? from
                      : later(from, c.getAbsenceStart().atStartOfDay(Consultant.ABSENCE_ZONE));
              var end =
                  c.getAbsenceEnd() == null
                      ? to
                      : earlier(
                          to, c.getAbsenceEnd().plusDays(1).atStartOfDay(Consultant.ABSENCE_ZONE));
              return start.isBefore(end)
                  ? List.of(
                      new CalendarEntry(
                          "absence-" + consultantId,
                          CalendarEntryType.ABSENCE,
                          start,
                          end,
                          null,
                          null,
                          null,
                          null,
                          true))
                  : List.<CalendarEntry>of();
            })
        .orElse(List.of());
  }

  private static Instant later(Instant a, java.time.ZonedDateTime b) {
    return a.isAfter(b.toInstant()) ? a : b.toInstant();
  }

  private static Instant earlier(Instant a, java.time.ZonedDateTime b) {
    return a.isBefore(b.toInstant()) ? a : b.toInstant();
  }

  static CalendarEntry blocker(CalendarBlocker blocker) {
    var own = blocker.getSource() == CalendarBlocker.Source.ORISO;
    return new CalendarEntry(
        blocker.getId().toString(),
        own ? CalendarEntryType.BLOCKER : CalendarEntryType.BUSY,
        blocker.getStartTime(),
        blocker.getEndTime(),
        null,
        own ? blocker.getNote() : null,
        null,
        own && blocker.getSeriesId() != null ? blocker.getSeriesId().toString() : null,
        own && blocker.isAllDay());
  }

  private List<CalendarEntry> occurrencesOf(Collection<Chat> found, Instant from, Instant to) {
    var series = new LinkedHashMap<Long, Chat>();
    found.forEach(
        chat -> {
          if (isScheduled(chat)) {
            series.putIfAbsent(chat.getId(), chat);
          }
        });
    var result = new ArrayList<CalendarEntry>();
    if (series.isEmpty()) {
      return result;
    }
    // One read for the exceptions of every series, not one per series.
    var exceptionsOf =
        occurrenceExceptions.findBySeries_IdIn(List.copyOf(series.keySet())).stream()
            .collect(Collectors.groupingBy(exception -> exception.getSeries().getId()));
    for (var chat : series.values()) {
      var exceptions = exceptionsOf.getOrDefault(chat.getId(), List.of());
      var cursor = from.minus(LOOK_BACK);
      while (cursor.isBefore(to)) {
        var sliceEnd = cursor.plus(SLICE).isBefore(to) ? cursor.plus(SLICE) : to;
        for (var occurrence :
            projector.project(chat, exceptions, utc(cursor), utc(sliceEnd), SLICE_LIMIT)) {
          var start = occurrence.start().toInstant(ZoneOffset.UTC);
          var end = start.plus(Duration.ofMinutes(occurrence.duration()));
          if (end.isAfter(from)) {
            result.add(
                new CalendarEntry(
                    "chat-" + chat.getId() + "-" + occurrence.occurrenceIndex(),
                    CalendarEntryType.GROUP,
                    start,
                    end,
                    null,
                    chat.getTopic(),
                    null,
                    null));
          }
        }
        cursor = sliceEnd;
      }
    }
    return result;
  }

  /**
   * An internal group chat of colleagues is a standing room without a date, so it is no calendar
   * entry; a series without an anchor or interval cannot be projected.
   */
  private static boolean isScheduled(Chat chat) {
    return chat.getConversationType() != ConversationType.INTERNAL_GROUP
        && chat.getInitialStartDate() != null
        && (chat.getRepeatCount() <= 1 || chat.getChatInterval() != null);
  }

  private CalendarEntry appointment(SessionAppointment appointment, String title) {
    return new CalendarEntry(
        appointment.getId().toString(),
        CalendarEntryType.APPOINTMENT,
        appointment.getStartTime(),
        appointment.getEndTime(),
        appointment.getKind(),
        title,
        appointment.getSession() == null ? null : appointment.getSession().getId(),
        null);
  }

  /** The name askers see elsewhere (the public display name), else the username. */
  private String publicNameOf(Consultant consultant) {
    if (consultant == null) {
      return null;
    }
    var displayName = consultant.getDisplayName();
    return displayName != null && !displayName.isBlank()
        ? displayName
        : usernameTranscoder.decodeUsername(consultant.getUsername());
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
