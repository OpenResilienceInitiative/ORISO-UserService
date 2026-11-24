package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarConsultationOnceCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarConsultationOnceDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.model.CalendarConsultationOnce;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationOnceRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A counsellor's one-off Sprechstunden (ORISO-Frontend#2494, product owner 23 Nov 2026): single
 * dated windows they release for booking once, at short notice, each slot bookable until its own
 * notice in minutes before its start. Such a window may lie over the counsellor's own blockers
 * (Sperrzeit) only when they confirmed it ({@code overBlocker}); otherwise the request is refused
 * with {@link HttpStatusExceptionReason#CONSULTATION_ONCE_OVER_BLOCKER}, so the frontend can warn
 * first. Only the counsellor themself reads, creates and removes them; anybody else gets the 404 of
 * a one-off that does not exist. Writes of one counsellor run one at a time under their row lock,
 * the lock a booking holds too.
 */
@Service
@RequiredArgsConstructor
public class CalendarConsultationOnceService {

  static final Duration MAX_LENGTH = Duration.ofHours(12);
  static final int MAX_UPCOMING = 50;
  static final Set<Integer> NOTICE_MINUTES = Set.of(0, 30, 60, 120, 720, 1440);
  private static final String NOT_FOUND = "One-off Sprechstunde not found.";

  private final AuthenticatedUser caller;
  private final CalendarConsultationOnceRepository onces;
  private final CalendarBlockerRepository blockers;
  private final ConsultantRepository consultants;
  private final Clock clock;

  /** The caller's one-offs whose end lies ahead, by start. */
  @Transactional(readOnly = true)
  public List<CalendarConsultationOnceDTO> upcoming() {
    requireConsultant();
    return onces
        .findByConsultantIdAndEndAtAfterOrderByStartAtAsc(caller.getUserId(), Instant.now(clock))
        .stream()
        .map(CalendarConsultationOnceService::toDto)
        .toList();
  }

  /**
   * Stores one one-off of the caller; nothing is stored when anything is invalid.
   *
   * @throws BadRequestException end not after start, end not ahead, longer than 12 hours, a notice
   *     not offered, overlapping another one-off of the caller, or 50 upcoming one-offs already
   * @throws CustomValidationHttpStatusException 409 with {@code CONSULTATION_ONCE_OVER_BLOCKER}
   *     when it overlaps one of the caller's own blockers and overBlocker is not true
   */
  @Transactional
  public CalendarConsultationOnceDTO create(CalendarConsultationOnceCreateDTO request) {
    requireConsultant();
    if (request == null || request.getStart() == null || request.getEnd() == null) {
      throw new BadRequestException("A one-off Sprechstunde names its start and end.");
    }
    var start = request.getStart().toInstant();
    var end = request.getEnd().toInstant();
    var now = Instant.now(clock);
    if (!end.isAfter(start)) {
      throw new BadRequestException("The end of a one-off Sprechstunde must be after its start.");
    }
    if (!end.isAfter(now)) {
      throw new BadRequestException("A one-off Sprechstunde ends in the future.");
    }
    if (Duration.between(start, end).compareTo(MAX_LENGTH) > 0) {
      throw new BadRequestException("A one-off Sprechstunde lasts at most 12 hours.");
    }
    var notice = request.getNoticeMinutes();
    if (notice == null || !NOTICE_MINUTES.contains(notice)) {
      throw new BadRequestException(
          "A one-off Sprechstunde is bookable until 0, 30, 60, 120, 720 or 1440 minutes before.");
    }
    var overBlocker = Boolean.TRUE.equals(request.getOverBlocker());
    var consultantId = caller.getUserId();
    if (consultants.lockConsultant(consultantId).isEmpty()) {
      throw new ForbiddenException("Only a counsellor keeps Sprechstunden.");
    }
    if (!onces.findOverlapping(consultantId, start, end).isEmpty()) {
      throw new BadRequestException("One-off Sprechstunden of one counsellor do not overlap.");
    }
    if (onces.findByConsultantIdAndEndAtAfterOrderByStartAtAsc(consultantId, now).size()
        >= MAX_UPCOMING) {
      throw new BadRequestException("At most " + MAX_UPCOMING + " upcoming one-off Sprechstunden.");
    }
    if (!overBlocker
        && blockers.findOverlapping(consultantId, start, end).stream()
            .anyMatch(blocker -> blocker.getSource() == Source.ORISO)) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CONSULTATION_ONCE_OVER_BLOCKER,
          HttpStatus.CONFLICT,
          "The one-off Sprechstunde lies over a blocker of the counsellor.");
    }
    var once = new CalendarConsultationOnce();
    once.setConsultantId(consultantId);
    once.setStartAt(start);
    once.setEndAt(end);
    once.setNoticeMinutes(notice);
    once.setOverBlocker(overBlocker);
    return toDto(onces.save(once));
  }

  /** Removes one one-off of the caller; appointments booked in it stay. */
  @Transactional
  public void delete(UUID onceId) {
    requireConsultant();
    var consultantId = caller.getUserId();
    var once =
        onces
            .findByIdAndConsultantId(onceId, consultantId)
            .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    consultants.lockConsultant(consultantId);
    onces.delete(once);
  }

  private static CalendarConsultationOnceDTO toDto(CalendarConsultationOnce once) {
    return new CalendarConsultationOnceDTO()
        .id(once.getId())
        .start(once.getStartAt().atOffset(ZoneOffset.UTC))
        .end(once.getEndAt().atOffset(ZoneOffset.UTC))
        .noticeMinutes(once.getNoticeMinutes())
        .overBlocker(once.isOverBlocker());
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor keeps Sprechstunden.");
    }
  }
}
