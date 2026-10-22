package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupService;
import de.caritas.cob.userservice.api.service.auth.PasswordResetBinding;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

/** Only actual service-private setup/reset continuations may reach this finite adapter. */
@RequiredArgsConstructor
public final class ClaimedPasswordMaintenance {
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull Clock clock;
  private final @NonNull String authServer;
  private final @NonNull String publicIssuer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;
  private final @NonNull Map<String, ProfileSelector> profiles;

  /** Reviewed mounted selectors are data; the native receiver independently checks actual provenance. */
  public record ProfileSelector(String kind, String id, long version) {
    public ProfileSelector {
      if (!Set.of("ORDINARY", "PROTECTED").contains(kind) || id == null || id.isBlank()
          || id.length() > 255 || version <= 0) throw new IllegalArgumentException("Invalid password profile selector");
    }
  }

  public void requireSupport() {
    if (!mode.isFiveActorMode() || !profiles.keySet().equals(Set.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN"))
        || actors.require(TaskPurpose.ACCOUNT_MAINTENANCE).actor() != TaskActor.ACCOUNT_ADMIN)
      throw new ScopedIdentityCommandRequiredException();
  }

  public String existingSetupRequestDigest(ExistingSetupPasswordClaims.Binding binding, String password) {
    requireSupport(); return codec.existingSetupPasswordRequestDigest(binding, password);
  }

  public String passwordResetRequestDigest(PasswordResetBinding binding, String password) {
    requireSupport(); return codec.passwordResetRequestDigest(binding, password);
  }

  public PasswordResetBinding issuance(UUID workflowId, String accountId, Long tenantId,
      String username, String recipient, String accountKind, Instant expiresAt) {
    requireSupport(); throw new ScopedIdentityCommandRequiredException();
  }

  public void setup(ExistingAccountSetupService.ClaimedSetup claim, String password) {
    requireSupport(); throw new ScopedIdentityCommandRequiredException();
  }

  public void reset(PasswordResetService.ClaimedReset claim, String password) {
    requireSupport(); throw new ScopedIdentityCommandRequiredException();
  }
}
