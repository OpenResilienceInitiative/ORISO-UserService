package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantEnquiryOpened;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantEnquiryOpenedRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Remembers, per counsellor, which waiting enquiries they have opened (UserService#1549). */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnquiryOpenedService {

  private final @NonNull SessionService sessionService;
  private final @NonNull ConsultantEnquiryOpenedRepository repository;

  /**
   * Marks the waiting enquiry as opened for {@code consultant} only. Idempotent: a repeat call, or
   * a concurrent one losing the race on the unique key, ends quietly with the one existing row.
   *
   * <p>Deliberately not {@code @Transactional}: the insert runs in its own repository transaction,
   * so a lost race rolls back only that insert and is answered here as "already marked".
   *
   * @throws NotFoundException the session does not exist
   * @throws ConflictException the session is no waiting enquiry (accepted, rejected, ...)
   * @throws ForbiddenException the enquiry is not in this counsellor's pool
   */
  public void markOpened(@NonNull Long sessionId, @NonNull Consultant consultant) {
    Session session =
        sessionService
            .getSession(sessionId)
            .orElseThrow(() -> new NotFoundException("Session with id %s not found.", sessionId));
    if (session.getStatus() != SessionStatus.NEW || session.getConsultant() != null) {
      throw new ConflictException(String.format("Session %s is not a waiting enquiry.", sessionId));
    }
    // Same agency / topic / consulting-type check that decides what the pool shows.
    if (!sessionService.isConsultantPermittedToSession(consultant, session)) {
      throw new ForbiddenException(
          String.format("Consultant %s may not see enquiry %s", consultant.getId(), sessionId));
    }
    if (repository.existsByConsultantIdAndSessionId(consultant.getId(), sessionId)) {
      return;
    }
    try {
      repository.save(
          new ConsultantEnquiryOpened(
              consultant.getId(), sessionId, LocalDateTime.now(ZoneOffset.UTC)));
    } catch (DataIntegrityViolationException alreadyMarked) {
      log.debug("Enquiry {} already marked as opened for {}", sessionId, consultant.getId());
    }
  }
}
