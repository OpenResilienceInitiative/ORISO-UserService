package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Closes the enquiries of an advice seeker who is marked for deletion and that no counsellor has
 * taken yet. Otherwise they stay in the counsellors' enquiry lists until the nightly run and block
 * deleting the agency's last counsellor (ORISO-Admin#1521). Taken sessions are left alone: their
 * counsellor still sees them as read-only until the hard delete.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AskerEnquiryCloser {

  private final @NonNull SessionRepository sessionRepository;

  /**
   * @return how many enquiries were closed
   */
  public int closeOpenEnquiries(User asker) {
    if (asker == null) {
      return 0;
    }
    int closed =
        sessionRepository.closeUnassignedEnquiries(asker, LocalDateTime.now(ZoneOffset.UTC));
    if (closed > 0) {
      log.info(
          "Closed {} open enquiries of advice seeker {} marked for deletion",
          closed,
          asker.getUserId());
    }
    return closed;
  }
}
