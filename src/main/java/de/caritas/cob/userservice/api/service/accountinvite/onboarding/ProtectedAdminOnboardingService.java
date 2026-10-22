package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.TenantAdminRegistrationResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Public recipient coordinator; all effect authority comes from checked private owners. */
public final class ProtectedAdminOnboardingService {
  private final AccountInviteService invites;
  private final TaskActorConfiguration actors;
  private final JdbcProtectedInvitationAcceptance acceptance;
  private final ProtectedAccountCodec codec;
  private final JdbcProtectedAccountCreation creation;
  private final KeycloakProtectedAccounts accounts;
  private final ProtectedOtpBootstrap otp;
  private final JdbcProtectedAccountActivation activation;
  private final AccountInactivityEnrollmentService inactivity;
  private final UsernameTranscoder usernames;
  private final ProtectedInvitationProfiles profiles;
  private final OperatorDpaContentClient dpa;
  private final TenantCreationClient tenants;
  private final Clock clock;
  private final ObjectMapper nodes = new ObjectMapper();

  public ProtectedAdminOnboardingService(AccountInviteService invites, TaskActorConfiguration actors,
      JdbcProtectedInvitationAcceptance acceptance, ProtectedAccountCodec codec,
      JdbcProtectedAccountCreation creation, KeycloakProtectedAccounts accounts,
      ProtectedOtpBootstrap otp, JdbcProtectedAccountActivation activation,
      AccountInactivityEnrollmentService inactivity, UsernameTranscoder usernames,
      ProtectedInvitationProfiles profiles, OperatorDpaContentClient dpa,
      TenantCreationClient tenants, Clock clock) {
    this.invites = Objects.requireNonNull(invites); this.actors = Objects.requireNonNull(actors);
    this.acceptance = Objects.requireNonNull(acceptance); this.codec = Objects.requireNonNull(codec);
    this.creation = Objects.requireNonNull(creation); this.accounts = Objects.requireNonNull(accounts);
    this.otp = Objects.requireNonNull(otp); this.activation = Objects.requireNonNull(activation);
    this.inactivity = Objects.requireNonNull(inactivity); this.usernames = Objects.requireNonNull(usernames);
    this.profiles = Objects.requireNonNull(profiles); this.dpa = Objects.requireNonNull(dpa);
    this.tenants = Objects.requireNonNull(tenants); this.clock = Objects.requireNonNull(clock);
  }

  public TenantAdminRegistrationResult register(String token, RegisterTenantAdminCommand command, JsonNode originalRequest) {
    outsideSql();
    TenantAdminOnboardingService.validateRegistration(command);
    if (originalRequest == null || !originalRequest.isObject()) throw unavailable();
    AccountInvite invite = current(token);
    AcceptanceClaim claim = acceptance.claim(invite.getId(), AccountInviteService.hash(token), originalRequest);
    JsonNode scope = checkedScope(invite, claim);
    var phase = activation.phase(claim);
    if (phase == JdbcProtectedAccountActivation.Phase.WAITING_OTP) return result(invite, activation.currentSetup(claim));
    if (phase == JdbcProtectedAccountActivation.Phase.COMMITTED) return new TenantAdminRegistrationResult(invite.getTenantId(),null,null,"COMPLETED");
    if (phase == JdbcProtectedAccountActivation.Phase.READY) {
      finish(claim, otp.completed(claim));
      return new TenantAdminRegistrationResult(invite.getTenantId(),null,null,"COMPLETED");
    }
    if (phase != JdbcProtectedAccountActivation.Phase.UNSTAGED) throw unavailable();
    if (!"EXISTING".equals(scope.path("tenantMode").asText())) throw unavailable();
    String email = invite.getRecipientEmail();
    String first = blank(invite.getFirstName()) ? email.substring(0,email.indexOf('@')) : invite.getFirstName();
    String last = blank(invite.getLastName()) ? "Admin" : invite.getLastName();
    var prepared = codec.prepare(claim, originalRequest, new AccountCreation(usernames.encodeUsername(email),email,first,last,"de",command.password(),false));
    // The original opaque metadata is durably committed before this first native RPC.
    creation.prepare(claim, prepared);
    var created = accounts.create(prepared);
    creation.recordCreated(claim, created);
    var pending = accounts.ownedRead(claim, created);
    var bootstrap = otp.prepare(claim, pending);
    var policy = inactivity.capture(invite.getTenantId(), AccountInactivityEnrollmentService.Group.OTHER);
    return result(invite, activation.stage(claim, pending, bootstrap, policy));
  }

  public Resolved resolve(String token) {
    outsideSql();
    AccountInvite invite = current(token);
    if (invite.getStatus() == AccountInviteStatus.ACCEPTED) {
      var claim = resumed(invite, token);
      var phase = activation.phase(claim);
      if (phase == JdbcProtectedAccountActivation.Phase.WAITING_OTP)
        return new Resolved(invite,activation.currentSetup(claim),null,null,null,true,"PENDING_2FA_ACTIVATION");
      if (phase == JdbcProtectedAccountActivation.Phase.COMMITTED)
        return new Resolved(invite,null,null,null,null,true,"COMPLETED");
      if (phase == JdbcProtectedAccountActivation.Phase.READY)
        return new Resolved(invite,null,null,null,null,true,"PENDING_2FA_ACTIVATION");
      throw unavailable();
    }
    boolean existing = invite.getTargetRole() == AccountInviteTargetRole.PLATFORM_ADMIN || blank(invite.getTenantIdReservationToken());
    if (existing) return new Resolved(invite,null,null,null,null,true,null);
    var lookup = dpa.lookupPublishedDpa();
    return new Resolved(invite,null,lookup.content(),lookup.dpa()==null?null:lookup.dpa().version(),lookup.reason(),false,null);
  }

  public void setupApp(String token, String code) {
    outsideSql();
    var invite = accepted(token);
    var claim = resumed(invite,token);
    complete(claim,otp.setupApp(claim,code));
  }

  public void startEmail(String token) {
    outsideSql();
    var invite = accepted(token);
    var claim = resumed(invite,token);
    var attempt = otp.startEmail(claim);
    // READY or unknown SMTP delivery cannot be represented as a delivered message.
    if (attempt == null || attempt.state() == null || !"SENT".equals(attempt.state().name())) throw unavailable();
  }

  public void finishEmail(String token, String code) {
    outsideSql();
    var invite = accepted(token);
    var claim = resumed(invite,token);
    complete(claim,otp.finishEmail(claim,code));
  }

  private void complete(AcceptanceClaim claim, ProtectedOtpBootstrap.BootstrapAttempt attempt) {
    if (attempt == null || attempt.state() != ProtectedOtpBootstrap.AttemptState.COMPLETE || attempt.completion() == null) throw unavailable();
    finish(claim,attempt.completion());
  }

  private void finish(AcceptanceClaim claim, ProtectedOtpBootstrap.Completion completion) {
    var ready = activation.ready(claim,completion);
    var committed = accounts.commit(ready);
    activation.acknowledge(ready,committed);
  }

  private AcceptanceClaim resumed(AccountInvite invite, String token) {
    var claim = acceptance.resume(invite.getId(),AccountInviteService.hash(token));
    checkedScope(invite,claim);
    return claim;
  }

  private JsonNode checkedScope(AccountInvite invite, AcceptanceClaim claim) {
    try {
      if (claim == null || claim.frozen() == null || !profiles.realmId().equals(claim.frozen().issuerRealm())) throw unavailable();
      var scope = nodes.readTree(claim.frozen().requestJson()).path("scope");
      if (!invite.getTargetRole().name().equals(scope.path("protectedKind").asText())
          || !profiles.profileIds().get(invite.getTargetRole()).equals(scope.path("protectedProfileId").asText())
          || !scope.path("targetTenantIds").isArray() || scope.path("targetTenantIds").size()!=1
          || !scope.path("targetTenantIds").get(0).isIntegralNumber()
          || scope.path("targetTenantIds").get(0).longValue()!=invite.getTenantId()) throw unavailable();
      return scope;
    } catch (java.io.IOException failure) { throw unavailable(); }
  }

  private AccountInvite accepted(String token) {
    var invite = current(token);
    if (invite.getStatus()!=AccountInviteStatus.ACCEPTED) throw unavailable();
    return invite;
  }

  private AccountInvite current(String token) {
    if (!actors.isEnabled() || blank(token)) throw unavailable();
    var invite = invites.findInviteByToken(token);
    if (invite == null || invite.getId()==null || invite.getTenantId()==null
        || !Set.of(AccountInviteTargetRole.TENANT_ADMIN,AccountInviteTargetRole.PLATFORM_ADMIN).contains(invite.getTargetRole())
        || invite.getPurpose()!=AccountInvitePurpose.INVITE
        || !Set.of(AccountInviteStatus.EMAIL_SENT,AccountInviteStatus.ACCEPTED).contains(invite.getStatus())) throw unavailable();
    if (invite.getExpiresAt()==null || !invite.getExpiresAt().isAfter(LocalDateTime.now(clock)))
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
    return invite;
  }

  private static TenantAdminRegistrationResult result(AccountInvite invite, IdentityOtpCredential secret) {
    if (secret==null || blank(secret.secret())) throw unavailable();
    return new TenantAdminRegistrationResult(invite.getTenantId(),secret.secret(),secret.secretQrCode(),"PENDING_2FA_ACTIVATION");
  }
  private static boolean blank(String value) { return value==null || value.isBlank(); }
  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
  }
  private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Protected administrator onboarding unavailable"); }
  public record Resolved(AccountInvite invite, IdentityOtpCredential twoFactor, String dpaContent,String dpaVersion, OperatorDpaContentClient.DpaUnavailableReason dpaUnavailableReason,boolean joinsExistingTenant,String phase) {}
}
