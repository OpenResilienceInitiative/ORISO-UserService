package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.ProtectedInvitationProfiles;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.TenantAdminRegistrationResult;
import java.time.Clock;

/** Public recipient coordinator; all effect authority comes from checked private owners. */
public final class ProtectedAdminOnboardingService {
  public ProtectedAdminOnboardingService(AccountInviteService invites, TaskActorConfiguration actors,
      JdbcProtectedInvitationAcceptance acceptance, ProtectedAccountCodec codec,
      JdbcProtectedAccountCreation creation, KeycloakProtectedAccounts accounts,
      ProtectedOtpBootstrap otp, JdbcProtectedAccountActivation activation,
      AccountInactivityEnrollmentService inactivity, UsernameTranscoder usernames,
      ProtectedInvitationProfiles profiles, OperatorDpaContentClient dpa,
      TenantCreationClient tenants, Clock clock) {}
  public TenantAdminRegistrationResult register(String token, RegisterTenantAdminCommand command, JsonNode originalRequest) { throw unavailable(); }
  public Resolved resolve(String token) { throw unavailable(); }
  public void setupApp(String token, String code) { throw unavailable(); }
  public void startEmail(String token) { throw unavailable(); }
  public void finishEmail(String token, String code) { throw unavailable(); }
  public record Resolved(AccountInvite invite, IdentityOtpCredential twoFactor, String dpaContent, OperatorDpaContentClient.DpaUnavailableReason dpaUnavailableReason, boolean joinsExistingTenant) {}
  private static IllegalStateException unavailable() { return new IllegalStateException("Protected administrator onboarding unavailable"); }
}
