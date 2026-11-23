package de.caritas.cob.userservice.api.service.sessionappointment;

import de.caritas.cob.userservice.api.model.SessionAppointment.Status;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expiry of advice seekers' appointment requests (ORISO-Frontend#2287, Frank 17 Nov 2026): a
 * request nobody answered by its start is declined automatically. From its start on it neither
 * takes the counsellor's time nor counts as the conversation's one appointment, also before this
 * sweep ran (the queries say so themselves). The advice seeker is told like after a counsellor's
 * decline, by a notice in the conversation: the backend holds no room keys, so the row keeps {@code
 * noticePending} until a participant's client claims and posts it.
 */
@Service
@RequiredArgsConstructor
public class AppointmentRequestExpiry {

  private final SessionAppointmentRepository appointments;

  /** Declines the requests whose start is {@code now} or earlier; returns how many. */
  @Transactional
  public int declineUnanswered(Instant now) {
    return appointments.declineRequestsStartedBy(now, Status.REQUESTED, Status.DECLINED);
  }
}
