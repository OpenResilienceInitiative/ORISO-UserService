package de.caritas.cob.userservice.api.service.support;

import de.caritas.cob.userservice.api.service.handshake.SupportAccessJobHandler;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wires the three durable job types of ADR-018 §4 onto {@link SupportAccessMatrixWorker} and runs
 * the four-hour cutoff sweep.
 */
@Configuration
public class SupportAccessJobs {

  @Bean
  SupportAccessJobHandler provisionSupportRoomJob(SupportAccessMatrixWorker worker) {
    return new SupportAccessJobHandler() {
      @Override
      public String jobType() {
        return PROVISION_ROOM;
      }

      @Override
      public void handle(String handshakeId) {
        worker.provision(handshakeId);
      }

      @Override
      public boolean retriesForever() {
        // Bounded: a session that cannot be built becomes PROVISIONING_FAILED and is shown to
        // operations rather than retried into eternity.
        return false;
      }
    };
  }

  @Bean
  SupportAccessJobHandler revokeSupportAccessJob(SupportAccessMatrixWorker worker) {
    return new SupportAccessJobHandler() {
      @Override
      public String jobType() {
        return REVOKE_ACCESS;
      }

      @Override
      public void handle(String sessionId) {
        worker.revoke(sessionId);
      }

      @Override
      public boolean retriesForever() {
        // Unbounded on purpose: while withdrawal is unproven the session must stay
        // REVOCATION_PENDING. Giving up would turn an outage into a false security claim.
        return true;
      }
    };
  }

  @Bean
  SupportAccessJobHandler purgeCallRoomJob(SupportAccessMatrixWorker worker) {
    return new SupportAccessJobHandler() {
      @Override
      public String jobType() {
        return PURGE_CALL_ROOM;
      }

      @Override
      public void handle(String sessionId) {
        worker.purgeCallRoom(sessionId);
      }

      @Override
      public boolean retriesForever() {
        return true;
      }
    };
  }

  /** ADR-018 hard cutoff: at four hours the lease ends even if the homeserver hiccups. */
  @Component
  @Slf4j
  @RequiredArgsConstructor
  public static class SupportAccessExpirySweep {

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
}
