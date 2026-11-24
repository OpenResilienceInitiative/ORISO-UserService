package de.caritas.cob.userservice.api.admin.service.agency;

import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides, for one advice seeker of a Beratungsstelle being deleted (ORISO-Admin#1524), whether
 * they keep their account, and takes their sessions and membership there if they do.
 *
 * <p>The decision and the removal happen in one transaction under the advice seeker's row lock. Two
 * Beratungsstellen deleted at the same time therefore decide one after the other: the second sees
 * what the first took and finds the advice seeker only there. Nothing is taken from an advice
 * seeker who belongs only here; their account is marked instead, and until that succeeds their link
 * here is what lets the next call find them again.
 */
@Component
@RequiredArgsConstructor
public class AgencyAdviceSeekerUnlinker {

  /** What happened to the advice seeker. */
  public sealed interface Outcome {}

  /** Marked for deletion or gone since they were listed: nothing to do. */
  public record Gone() implements Outcome {}

  /** They belong only here: their account is to be marked; nothing was taken. */
  public record OnlyHere() implements Outcome {}

  /** They belong elsewhere too: their sessions here are marked, their membership here removed. */
  public record Kept(int markedSessions) implements Outcome {}

  private final @NonNull UserRepository userRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull UserAgencyRepository userAgencyRepository;
  private final @NonNull PlatformTransactionManager transactionManager;

  public Outcome unlink(String userId, Long agencyId, LocalDateTime now) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction.execute(
        status -> {
          var user = userRepository.findByUserIdForUpdate(userId);
          if (user.isEmpty() || user.get().getDeleteDate() != null) {
            return new Gone();
          }
          if (!sessionRepository.hasActiveSessionOutsideAgency(userId, agencyId)
              && !userAgencyRepository.hasMembershipOutsideAgency(userId, agencyId)) {
            return new OnlyHere();
          }
          int marked =
              sessionRepository.markSessionsOfUserInAgencyForDeletion(userId, agencyId, now);
          userAgencyRepository.deleteMembershipsOfUserInAgency(userId, agencyId);
          return new Kept(marked);
        });
  }
}
