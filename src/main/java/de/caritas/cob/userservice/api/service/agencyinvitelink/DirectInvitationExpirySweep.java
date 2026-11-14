package de.caritas.cob.userservice.api.service.agencyinvitelink;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires direct invitations whose appointment validity ran out (ORISO-Frontend#2252), and retries
 * room removals Matrix did not confirm, each in its own transaction; one failure does not stop the
 * others.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DirectInvitationExpirySweep {

  static final int PAGE_SIZE = 100;

  private final @NonNull DirectInvitationExpiryService service;

  @Scheduled(
      fixedDelayString = "${direct-invitations.expiry-sweep.delay-ms:60000}",
      initialDelayString = "${direct-invitations.expiry-sweep.initial-delay-ms:60000}")
  public void sweep() {
    long afterId = 0;
    int expired = 0;
    while (true) {
      var ids = service.dueIds(afterId, PAGE_SIZE);
      for (Long id : ids) {
        try {
          if (service.expire(id)) expired++;
        } catch (RuntimeException failure) {
          log.warn("Direct invitation {} could not be expired: {}", id, failure.getMessage());
        }
      }
      if (ids.size() < PAGE_SIZE) break;
      afterId = ids.getLast();
    }
    if (expired > 0) log.info("Direct invitation sweep expired {} invitation(s)", expired);
    retryDueRemovals();
  }

  /** Removals Matrix did not confirm, also of invitations switched off or deleted since. */
  private void retryDueRemovals() {
    long afterId = 0;
    while (true) {
      var ids = service.pendingRemovalIds(afterId, PAGE_SIZE);
      for (Long id : ids) {
        try {
          service.retryRemoval(id);
        } catch (RuntimeException failure) {
          log.warn("Invited member {} could not be removed: {}", id, failure.getMessage());
        }
      }
      if (ids.size() < PAGE_SIZE) break;
      afterId = ids.getLast();
    }
  }
}
