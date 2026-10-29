package de.caritas.cob.userservice.api.service.agencyinvitelink;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes call share links whose call is over (#1487), independent of the hang-up event: a missed
 * event leaves the call's memberships to expire, and the backstop lifetime ends every link. Each
 * link is closed in its own transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CallShareLinkSweep {

  static final int PAGE_SIZE = 100;

  private final @NonNull CallShareLinkService service;

  @Scheduled(
      fixedDelayString = "${call-share-links.sweep.delay-ms:60000}",
      initialDelayString = "${call-share-links.sweep.initial-delay-ms:60000}")
  public void sweep() {
    long afterId = 0;
    int closed = 0;
    while (true) {
      var ids = service.activeLinkIds(afterId, PAGE_SIZE);
      for (Long id : ids) {
        try {
          if (service.expireIfOver(id)) closed++;
        } catch (RuntimeException failure) {
          log.warn("Call share link {} could not be swept: {}", id, failure.getMessage());
        }
      }
      if (ids.size() < PAGE_SIZE) break;
      afterId = ids.getLast();
    }
    if (closed > 0) log.info("Call share link sweep closed {} link(s)", closed);
  }
}
