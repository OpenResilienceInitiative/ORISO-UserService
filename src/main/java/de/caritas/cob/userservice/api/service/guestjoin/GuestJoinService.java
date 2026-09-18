package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.GuestJoinAttempt;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService;
import de.caritas.cob.userservice.api.service.identity.GuestIdentityCatalog;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Explicit Join orchestration. Provider intent and local completion own their transactions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuestJoinService {
  private final AgencyInviteLinkService invitations;
  private final GuestIdentityCatalog catalog;
  private final GuestJoinAttemptStore store;
  private final GuestJoinProvisioner provisioner;
  private final GuestJoinSessionFinalizer finalizer;
  private final ChatRecoveryEnrollmentPolicyService recovery;
  private final GuestJoinNotifications notifications;
  private final IdentityAuthentication authentication;

  public JoinResponse join(
      String token, String retryKey, String username, String avatarKey, boolean languageFormal) {
    var capability = GuestJoinCapability.parse(retryKey);
    if (!catalog.isKnownSelection(username, avatarKey)) {
      throw new BadRequestException("Select a matching name and avatar from the guest catalogue");
    }
    var target = invitations.getGuestJoinTarget(token);
    var previous = TenantContext.getCurrentTenantData();
    try {
      TenantContext.setCurrentTenantData(new TenantData(target.tenantId(), null));
      // Capability recovery lifetime only: this does not end or delete a counselling session.
      var attempt =
          store.prepare(
              capability,
              target,
              username,
              avatarKey,
              languageFormal,
              LocalDateTime.now(ZoneOffset.UTC).plusHours(48));
      var policy =
          attempt.getPhase() == GuestJoinAttempt.Phase.COMPLETE
              ? null
              : recovery.forNewAsker(target.tenantId());
      provisioner.provision(capability);
      var joined = finalizer.finish(capability, policy);
      if (joined.created()) {
        try {
          // Finalization has committed. A timeline notification is best-effort, as in legacy Join.
          notifications.joined(joined.sessionId());
        } catch (RuntimeException unavailable) {
          log.warn("Could not persist guest Join notification for session {}", joined.sessionId());
        }
      }
      var login = login(joined.username(), capability.identityPassword(joined.username()));
      try {
        // A counsellor may have ended the session while authentication was in flight.
        finalizer.finish(capability, null);
      } catch (RuntimeException ended) {
        revoke(login);
        throw ended;
      }
      return new JoinResponse(
          joined.username(),
          avatarKey,
          joined.sessionId(),
          target.tenantId(),
          target.consultingTypeId(),
          target.topicId(),
          login.accessToken(),
          login.expiresIn(),
          login.refreshToken(),
          login.refreshExpiresIn());
    } catch (PessimisticLockingFailureException busy) {
      throw new ServiceUnavailableException(
          "Guest Join is being completed; retry the same request");
    } finally {
      if (previous == null) TenantContext.clear();
      else TenantContext.setCurrentTenantData(previous);
    }
  }

  private IdentityLogin login(String username, String password) {
    try {
      var result = authentication.login(username, password);
      if (result == null
          || result.accessToken() == null
          || result.accessToken().isBlank()
          || result.refreshToken() == null
          || result.refreshToken().isBlank()
          || result.expiresIn() <= 0
          || result.refreshExpiresIn() <= 0) {
        if (result != null) revoke(result);
        throw new IllegalStateException("Incomplete login");
      }
      return result;
    } catch (RuntimeException unavailable) {
      throw new ServiceUnavailableException(
          "Guest login is temporarily unavailable; retry the same request");
    }
  }

  private void revoke(IdentityLogin login) {
    if (login.refreshToken() == null || login.refreshToken().isBlank()) return;
    try {
      authentication.logout(login.refreshToken(), login.accessToken());
    } catch (RuntimeException unavailable) {
      // Do not return unusable credentials or expose upstream response bodies.
      log.warn("Could not revoke guest Join login after failed response validation");
    }
  }

  public record JoinResponse(
      String userName,
      String avatarKey,
      Long sessionId,
      Long tenantId,
      Integer consultingTypeId,
      Long topicId,
      String accessToken,
      int expiresIn,
      String refreshToken,
      int refreshExpiresIn) {
    @Override
    public String toString() {
      return "GuestJoinResponse[redacted]";
    }
  }
}
