package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

/** Consultant-specific compile seam. No claim or acknowledgement is issued until qualified TDD. */
@RequiredArgsConstructor
public final class ScheduledConsultantMaintenance {
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull ScheduledTaskClaimService claims;
  private final @NonNull Clock clock;
  private final @NonNull DeletionLifecycleService lifecycle;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull String authServer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;

  public void configureJpaCleanupOwner(EntityManager entities) {
    Objects.requireNonNull(entities);
  }

  public void configureAcknowledgedCleanup(Consumer<AcknowledgedConsultantDeletion> cleanup) {
    Objects.requireNonNull(cleanup);
  }

  public void performDeletionWorkflow() {
    // Default closed: no native or downstream effect while the real owner is test-first.
  }

  public static final class AcknowledgedConsultantDeletion {
    private AcknowledgedConsultantDeletion() {}

    public void requireCurrent() {
      throw unavailable();
    }

    public void requireCurrentConsultant(Consultant original) {
      throw unavailable();
    }

    public String accountId() {
      throw unavailable();
    }

    public Long tenantId() {
      throw unavailable();
    }

    public boolean completed() {
      throw unavailable();
    }

    public void runOwnedLocal(Runnable action) {
      throw unavailable();
    }

    public boolean completeOwnedLocalDeletion(Runnable finalDelete) {
      throw unavailable();
    }
  }

  private static ScopedIdentityCommandRequiredException unavailable() {
    return new ScopedIdentityCommandRequiredException();
  }
}
