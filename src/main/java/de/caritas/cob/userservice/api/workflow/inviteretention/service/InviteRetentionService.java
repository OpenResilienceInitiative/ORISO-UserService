package de.caritas.cob.userservice.api.workflow.inviteretention.service;

import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes revoked, expired and replaced invitations together with their mail-delivery rows once the
 * retention period has passed (ORISO-Admin#1522). Open and accepted invitations are never removed:
 * an open one may still be used, an accepted one documents how an existing account was created and
 * goes with that account. The clock starts when the invitation was settled (revoked, replaced, or
 * its expiry recorded), not when it was created.
 *
 * <p>Works in batches, each in its own transaction; a run stops after {@code max-batches}, the next
 * night continues. {@code account-invite.retention.days=0} switches the purge off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteRetentionService {

  static final List<AccountInviteStatus> SETTLED =
      List.of(
          AccountInviteStatus.REVOKED, AccountInviteStatus.EXPIRED, AccountInviteStatus.SUPERSEDED);

  private final AccountInviteRepository invites;
  private final InviteEmailDeliveryRepository deliveries;
  private final PlatformTransactionManager transactionManager;

  @Value("${account-invite.retention.days:90}")
  private long retentionDays;

  @Value("${account-invite.retention.batch-size:500}")
  private int batchSize;

  @Value("${account-invite.retention.max-batches:100}")
  private int maxBatches;

  /**
   * @return how many invitations were removed
   */
  public int purge() {
    if (retentionDays <= 0) {
      return 0;
    }
    LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
    var transaction = new TransactionTemplate(transactionManager);
    int purged = 0;
    for (int batch = 0; batch < maxBatches; batch++) {
      List<Long> ids =
          transaction.execute(
              status -> invites.findSettledBefore(SETTLED, cutoff, PageRequest.of(0, batchSize)));
      if (ids == null || ids.isEmpty()) {
        break;
      }
      Integer removed;
      try {
        removed =
            transaction.execute(
                status -> {
                  deliveries.deleteByAccountInviteIdIn(ids);
                  return invites.deleteSettledByIdIn(ids, SETTLED);
                });
      } catch (RuntimeException failure) {
        // The next night retries; the failure must not repeat endlessly within one run.
        log.warn(
            "Invite purge stopped after {} invitations: {}",
            purged,
            failure.getClass().getSimpleName());
        break;
      }
      purged += removed == null ? 0 : removed;
      if (ids.size() < batchSize) {
        break;
      }
    }
    if (purged > 0) {
      log.info(
          "Purged {} settled invitations older than {} days with their mail deliveries",
          purged,
          retentionDays);
    }
    return purged;
  }
}
