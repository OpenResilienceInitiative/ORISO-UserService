package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentKind;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarColleagueDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarColleaguesDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Colleague;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * The calendar read model (ADR-032, ORISO-UserService#1000): one's own calendar, the colleagues
 * whose calendar one may look at, and the redacted calendar of one of them.
 *
 * <p>A colleague outside the caller's scope answers like one who does not exist (404), and what is
 * shown of a colleague's calendar is built field by field here: times, type and kind, and the
 * asker's username only where that colleague's Träger shows it. Never the session, a blocker's note
 * or a group's topic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalendarService {

  static final Duration MAX_WINDOW = Duration.ofDays(62);
  static final int COLLEAGUE_LIMIT = 200;
  private static final String NOT_FOUND = "Calendar not found.";
  private static final Comparator<CalendarEntry> BY_START =
      Comparator.comparing(CalendarEntry::start).thenComparing(CalendarEntry::id);

  private final AuthenticatedUser caller;
  private final CalendarEntryReader reader;
  private final CalendarColleagueAppointmentReader meetings;
  private final CalendarColleagueScope colleagues;
  private final CalendarChatReach chatReach;
  private final AgencyService agencyService;
  private final Clock clock;

  /** The caller's own calendar: a counsellor's with blockers and busy times, an asker's without. */
  public List<CalendarEntryDTO> ownEntries(OffsetDateTime from, OffsetDateTime to) {
    var window = Window.of(from, to);
    var me = caller.getUserId();
    if (!caller.isConsultant()) {
      var entries = new ArrayList<CalendarEntry>();
      entries.addAll(reader.appointmentsOfAsker(me, window.from(), window.to()));
      entries.addAll(reader.groupOccurrencesOfAsker(me, window.from(), window.to()));
      return entries.stream().sorted(BY_START).map(entry -> own(entry, me, false)).toList();
    }
    var now = Instant.now(clock);
    var entries = new ArrayList<>(entriesOfConsultant(me, window));
    entries.addAll(meetingsOf(me, window, false));
    entries.sort(BY_START);
    return entries.stream().map(entry -> own(entry, me, isMovable(entry, now))).toList();
  }

  /** The colleagues in the caller's scope, with the setting that selected them. */
  public CalendarColleaguesDTO colleagues(String search) {
    requireConsultant();
    var listing = colleagues.list(caller.getUserId(), search, COLLEAGUE_LIMIT);
    var agencyNames = agencyNamesOf(listing.colleagues());
    // Unknown reach leaves the field out; the app then offers every kind and the server decides.
    var chats =
        chatReach.of(
            caller.getUserId(), listing.colleagues().stream().map(Colleague::consultant).toList());
    return new CalendarColleaguesDTO()
        .visibilityScope(
            CalendarColleaguesDTO.VisibilityScopeEnum.valueOf(
                listing.policy().visibilityScope().name()))
        .showAskerUsernames(listing.policy().showAskerUsernames())
        .colleagues(
            listing.colleagues().stream()
                .map(
                    colleague ->
                        new CalendarColleagueDTO()
                            .id(colleague.consultant().getId())
                            .displayName(colleague.name())
                            .secondaryLabel(secondaryLabelOf(colleague))
                            .agencyName(
                                colleague.agencyId() == null
                                    ? ""
                                    : agencyNames.getOrDefault(colleague.agencyId(), ""))
                            .relation(
                                CalendarColleagueDTO.RelationEnum.valueOf(
                                    colleague.relation().name()))
                            .internalChat(
                                chats
                                    .map(ids -> ids.contains(colleague.consultant().getId()))
                                    .orElse(null)))
                .toList());
  }

  /** The redacted calendar of one colleague; 404 unless that colleague is in the caller's scope. */
  public List<CalendarEntryDTO> colleagueEntries(
      String consultantId, OffsetDateTime from, OffsetDateTime to) {
    requireConsultant();
    var window = Window.of(from, to);
    var colleague =
        colleagues
            .find(caller.getUserId(), consultantId)
            .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    // The scope check above is the authorisation; the rows may lie in the colleague's Träger.
    List<CalendarEntry> entries =
        TenantContext.supplyAcrossTenants(
            () -> {
              var all = new ArrayList<>(entriesOfConsultant(consultantId, window));
              all.addAll(meetingsOf(consultantId, window, true));
              all.sort(BY_START);
              return all;
            });
    return entries.stream()
        .map(entry -> redacted(entry, consultantId, colleague.showAskerUsernames()))
        .toList();
  }

  private List<CalendarEntry> entriesOfConsultant(String consultantId, Window window) {
    var entries = new ArrayList<CalendarEntry>();
    entries.addAll(reader.appointmentsOfConsultant(consultantId, window.from(), window.to()));
    entries.addAll(reader.groupOccurrencesOfConsultant(consultantId, window.from(), window.to()));
    entries.addAll(reader.blockersOf(consultantId, window.from(), window.to()));
    entries.addAll(reader.absenceOf(consultantId, window.from(), window.to()));
    entries.sort(BY_START);
    return entries;
  }

  /**
   * Appointments with colleagues: one's own calendar shows the waiting ones too, a colleague's view
   * only those that are fixed.
   */
  private List<CalendarEntry> meetingsOf(
      String consultantId, Window window, boolean confirmedOnly) {
    return meetings.entriesOf(consultantId, window.from(), window.to(), confirmedOnly);
  }

  private static boolean isMovable(CalendarEntry entry, Instant now) {
    return switch (entry.type()) {
        // An unopened direct invitation (no conversation yet) moves with its invitation (#2252).
        // An appointment with a colleague is moved by cancelling it and making a new one (#2305).
      case APPOINTMENT ->
          entry.sessionId() != null && entry.meeting() == null && entry.start().isAfter(now);
      case BLOCKER -> true;
      case GROUP, BUSY, ABSENCE -> false;
    };
  }

  static CalendarEntryDTO own(CalendarEntry entry, String ownerId, boolean movable) {
    var dto = ownWithoutMeeting(entry, ownerId, movable);
    var meeting = entry.meeting();
    if (meeting != null) {
      dto.colleagueId(meeting.colleagueId())
          .colleagueName(meeting.colleagueName())
          .colleagueStatus(
              meeting.confirmed()
                  ? CalendarEntryDTO.ColleagueStatusEnum.CONFIRMED
                  : CalendarEntryDTO.ColleagueStatusEnum.PENDING)
          .invited(meeting.invited())
          .matrixRoomId(meeting.matrixRoomId())
          .chatId(meeting.chatId());
    }
    return dto;
  }

  private static CalendarEntryDTO ownWithoutMeeting(
      CalendarEntry entry, String ownerId, boolean movable) {
    return new CalendarEntryDTO()
        .id(entry.id())
        .type(entry.type())
        .start(entry.start().atOffset(ZoneOffset.UTC))
        .end(entry.end().atOffset(ZoneOffset.UTC))
        .ownerId(ownerId)
        .kind(kindOf(entry))
        .title(entry.title())
        .sessionId(entry.sessionId())
        .seriesId(entry.seriesId())
        .allDay(allDayOf(entry))
        .movable(movable);
  }

  /** Names every field a colleague gets; whatever is not set here does not leave the service. */
  static CalendarEntryDTO redacted(
      CalendarEntry entry, String ownerId, boolean showAskerUsernames) {
    var appointment = entry.type() == CalendarEntryType.APPOINTMENT;
    return new CalendarEntryDTO()
        .id(entry.id())
        .type(entry.type() == CalendarEntryType.BUSY ? CalendarEntryType.BLOCKER : entry.type())
        .start(entry.start().atOffset(ZoneOffset.UTC))
        .end(entry.end().atOffset(ZoneOffset.UTC))
        .ownerId(ownerId)
        .kind(appointment ? kindOf(entry) : null)
        // Who meets whom is not shown to third colleagues (ORISO-Frontend#2305).
        .title(appointment && showAskerUsernames && entry.meeting() == null ? entry.title() : null)
        // A colleague's absence (ORISO-Frontend#2303) reads as whole days, without its message.
        .allDay(entry.type() == CalendarEntryType.ABSENCE ? Boolean.TRUE : null)
        .movable(false);
  }

  private static Boolean allDayOf(CalendarEntry entry) {
    return switch (entry.type()) {
      case BLOCKER -> entry.allDay();
      case ABSENCE -> true;
      default -> null;
    };
  }

  /**
   * The username tells colleagues of one name apart (ORISO-UserService#1663). Within the Träger the
   * consultant lists show it already; another Träger's colleague is told apart by their counselling
   * centre, so the username does not leave the Träger.
   */
  // Provisioning stores usernames encoded ("enc.…"); colleagues read the login name.
  private static final UsernameTranscoder USERNAMES = new UsernameTranscoder();

  private static String secondaryLabelOf(Colleague colleague) {
    return colleague.relation() == CalendarColleagueScope.Relation.OTHER_TENANT
        ? null
        : Optional.ofNullable(StringUtils.trimToNull(colleague.consultant().getUsername()))
            .map(USERNAMES::decodeUsername)
            .orElse(null);
  }

  private static AppointmentKind kindOf(CalendarEntry entry) {
    SessionAppointment.Kind kind = entry.kind();
    return kind == null ? null : AppointmentKind.valueOf(kind.name());
  }

  /**
   * One lookup per Träger. A name is decoration: when the AgencyService does not answer, the
   * colleagues are listed without it.
   */
  private Map<Long, String> agencyNamesOf(List<Colleague> listed) {
    var idsByTenant = new HashMap<Long, Set<Long>>();
    for (var colleague : listed) {
      if (colleague.agencyId() != null) {
        idsByTenant
            .computeIfAbsent(colleague.consultant().getTenantId(), tenant -> new TreeSet<>())
            .add(colleague.agencyId());
      }
    }
    var names = new HashMap<Long, String>();
    idsByTenant.forEach(
        (tenantId, ids) -> {
          try {
            List<AgencyDTO> agencies =
                Objects.equals(tenantId, caller.getTenantId())
                    ? agencyService.getAgencies(List.copyOf(ids))
                    : agencyService.getPublicAgenciesForTenant(tenantId, List.copyOf(ids));
            if (agencies != null) {
              agencies.stream()
                  .filter(agency -> agency != null && agency.getId() != null)
                  .forEach(
                      agency -> names.put(agency.getId(), Objects.toString(agency.getName(), "")));
            }
          } catch (RuntimeException failure) {
            log.warn(
                "Agency names for the calendar colleague list could not be read: {}",
                failure.getClass().getSimpleName());
          }
        });
    return names;
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor looks at colleagues' calendars.");
    }
  }

  /** A window of at most {@link #MAX_WINDOW}, start before end. */
  record Window(Instant from, Instant to) {

    static Window of(OffsetDateTime from, OffsetDateTime to) {
      if (from == null || to == null || !from.isBefore(to)) {
        throw new BadRequestException("The calendar window must start before it ends.");
      }
      if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
        throw new BadRequestException("The calendar window must not exceed 62 days.");
      }
      return new Window(from.toInstant(), to.toInstant());
    }
  }
}
