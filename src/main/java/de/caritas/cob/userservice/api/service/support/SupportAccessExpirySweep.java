package de.caritas.cob.userservice.api.service.support;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-018 hard cutoff: at four hours the lease ends even if the homeserver hiccups.
 *
 * <p>The sweep only marks sessions; proving the withdrawal is the job runner's business, which is
 * why this stays a handful of lines rather than another place that talks to Matrix.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SupportAccessExpirySweep {

  private final @NonNull SupportAccessMatrixWorker worker;
  private final @NonNull SupportAccessSessionService sessionService;

  @Scheduled(fixedDelayString = "${support.session-sweep-delay-ms:60000}")
  public void sweepExpired() {
    worker
        .findExpired()
        .forEach(
            session -> {
              if (sessionService.beginRevocation(
                  session.getId(), SupportAccessSessionService.REASON_EXPIRED)) {
                log.info("Support session {} reached its four-hour cutoff", session.getId());
              }
            });
  }
}
