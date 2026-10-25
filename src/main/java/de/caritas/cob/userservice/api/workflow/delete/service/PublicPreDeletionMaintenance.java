package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

/** CLOSED test-first producer seam. No JSON/target/receipt can issue its private acknowledgement. */
@RequiredArgsConstructor
public final class PublicPreDeletionMaintenance {
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull AuthenticatedUser human;
  private final @NonNull AdminScope scope;
  private final @NonNull HttpServletRequest request;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull EntityManager entities;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull IdentityManaging passwords;
  private final @NonNull Clock clock;
  private final @NonNull String authServer;
  private final @NonNull String publicIssuer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;

  public boolean isFiveActorMode() {
    return mode.isFiveActorMode();
  }

  public void startSelfAsker(String password, Consumer<AcknowledgedPreDeletion> continuation) {
    throw new ScopedIdentityCommandRequiredException();
  }

  public void startAdminAsker(String account, Consumer<AcknowledgedPreDeletion> continuation) {
    throw new ScopedIdentityCommandRequiredException();
  }

  public void startAdminConsultant(
      String account, Boolean forceDeleteSessions, Consumer<AcknowledgedPreDeletion> continuation) {
    throw new ScopedIdentityCommandRequiredException();
  }

  public int recoverSelfAskerOutcomes(
      int limit, Consumer<AcknowledgedPreDeletion> continuation) {
    throw new ScopedIdentityCommandRequiredException();
  }

  /** No constructor or tuple factory is exposed; the closed baseline issues no live frame. */
  public static final class AcknowledgedPreDeletion {
    private AcknowledgedPreDeletion() {}

    public void requireCurrent() {
      throw new ScopedIdentityCommandRequiredException();
    }

    public String accountId() {
      throw new ScopedIdentityCommandRequiredException();
    }

    public Long tenantId() {
      throw new ScopedIdentityCommandRequiredException();
    }

    public void completeUser(Consumer<User> localEffect) {
      throw new ScopedIdentityCommandRequiredException();
    }

    public void completeConsultant(Consumer<Consultant> localEffect) {
      throw new ScopedIdentityCommandRequiredException();
    }

    public void runOwnedLocal(Runnable localEffect) {
      throw new ScopedIdentityCommandRequiredException();
    }
  }
}
