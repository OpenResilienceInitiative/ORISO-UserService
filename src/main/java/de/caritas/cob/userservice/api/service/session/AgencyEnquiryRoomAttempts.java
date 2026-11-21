package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRoomAttemptRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgencyEnquiryRoomAttempts {

  private final AgencyEnquiryRoomAttemptRepository attempts;
  private final Clock clock;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Long recordCreated(Session session, String roomId) {
    return attempts
        .save(new AgencyEnquiryRoomAttempt(session, roomId, LocalDateTime.now(clock)))
        .getId();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markRequesterInvitationStarted(Long attemptId) {
    attempts
        .findById(attemptId)
        .orElseThrow(() -> new IllegalStateException("Holding room attempt is missing"))
        .markRequesterInvitationStarted();
  }

  /** Retire only the exact attempt in the transaction that confirms its binding. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void completePublication(Session confirmed) {
    var matching =
        attempts
            .findAllBySessionIdAndRoomId(confirmed.getId(), confirmed.getMatrixRoomId())
            .stream()
            .filter(attempt -> attempt.matchesBinding(confirmed))
            .toList();
    attempts.deleteAll(matching);
  }
}
