package de.caritas.cob.userservice.api.service.livechat;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes the external identity step under the durable claim's row lock. */
@Service
@RequiredArgsConstructor
public class LiveChatAccountConversionExecutor {
  private final @NonNull LiveChatInactivityFence inactivity;
  private final @NonNull UserRepository users;
  private final @NonNull LiveChatAccountClaimRepository records;
  private final @NonNull IdentityClient identities;
  private final @NonNull de.caritas.cob.userservice.api.port.out.IdentityReactivator reactivator;
  private final @NonNull IdentityAuthentication authentication;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void execute(String userId, String username, String intentId, String password) {
    inactivity.requireActive(userId);
    var user =
        users
            .findByUserIdForUpdate(userId)
            .orElseThrow(() -> new ConflictException("Account no longer exists"));
    if (user.getDeleteDate() != null
        || user.getDeletionLifecycleState()
            != de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState.ACTIVE
        || user.getReactivationOperationId() != null)
      throw new ConflictException("Account lifecycle action prevents conversion");
    var claim =
        records
            .findForUpdate(userId)
            .orElseThrow(() -> new ConflictException("No conversion claim"));
    inactivity.requirePureAsker(userId);
    if (!intentId.equals(claim.getIntentId()))
      throw new ConflictException("Another conversion was started");
    if (claim.getLifecycle() == LiveChatAccountLifecycle.PROMOTED) {
      if (!authentication.verifyPasswordIgnoringSecondFactor(
          new UsernameTranscoder().encodeUsername(username), password)) {
        throw new ConflictException("Account uses different credentials");
      }
      return;
    }
    if (claim.getLifecycle() != LiveChatAccountLifecycle.PROMOTION_PENDING
        || !ConversionCredentialVerifier.matches(password, claim.getCredentialVerifier()))
      throw new ConflictException("Conversion credentials differ");
    var encodedUsername = new UsernameTranscoder().encodeUsername(username);
    if (!authentication.verifyPasswordIgnoringSecondFactor(encodedUsername, password)) {
      if (claim.isFinishedOrigin()) {
        if (user.getUsername() == null || user.getEmail() == null || user.getTenantId() == null)
          throw new ConflictException("Account identity cannot be verified");
        reactivator.reactivateUser(
            userId,
            new UsernameTranscoder().decodeUsername(user.getUsername()),
            user.getEmail(),
            user.getTenantId(),
            password);
      } else if (!identities.changePassword(userId, password)) {
        throw new InternalServerErrorException("Unable to establish account credentials");
      }
    }
    // A password update can succeed while Keycloak remains disabled; never report success then.
    if (!authentication.verifyPasswordIgnoringSecondFactor(encodedUsername, password))
      throw new InternalServerErrorException("Account credentials could not be verified");
    claim.setLifecycle(LiveChatAccountLifecycle.PROMOTED);
    claim.setCredentialVerifier(null);
    records.save(claim);
  }
}
