package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.Status;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentMailOutboxRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits the at-most-once claim before any SMTP or OWN-relay handoff. */
@Service
@RequiredArgsConstructor
public class GroupAppointmentMailClaimService {
  private final GroupAppointmentMailOutboxRepository outbox;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(long mailId) {
    return outbox.claim(mailId, Status.PENDING, Status.SENDING, nowUtc()) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(long mailId, Status terminalStatus) {
    if (terminalStatus != Status.SENT
        && terminalStatus != Status.SUPPRESSED
        && terminalStatus != Status.UNCERTAIN) {
      throw new IllegalArgumentException("Only terminal appointment-mail states are allowed");
    }
    GroupAppointmentMailOutbox mail =
        outbox
            .findById(mailId)
            .orElseThrow(() -> new IllegalStateException("Appointment-mail claim is missing"));
    if (mail.getStatus() != Status.SENDING) {
      throw new IllegalStateException("Appointment-mail claim is no longer held");
    }
    mail.setStatus(terminalStatus);
    if (terminalStatus == Status.SENT) {
      mail.setSentAt(nowUtc());
    }
    outbox.save(mail);
  }

  private static LocalDateTime nowUtc() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
