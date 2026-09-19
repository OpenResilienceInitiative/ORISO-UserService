package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.GuestJoinAttempt;
import de.caritas.cob.userservice.api.model.GuestJoinAttempt.Phase;
import de.caritas.cob.userservice.api.port.out.GuestChatIdentity;
import de.caritas.cob.userservice.api.port.out.GuestIdentityAccount;
import de.caritas.cob.userservice.api.port.out.GuestJoinAttemptRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Each pending intent commits separately before its bounded, serialized provider phase. */
@Service
public class GuestJoinProvisioner {
  private final GuestJoinAttemptRepository attempts;
  private final GuestJoinEligibility eligibility;
  private final GuestIdentityAccount identity;
  private final GuestChatIdentity matrix;
  private final de.caritas.cob.userservice.api.service.identity.GuestIdentityCatalog catalog;
  private final TransactionTemplate transaction;

  public GuestJoinProvisioner(
      GuestJoinAttemptRepository attempts,
      GuestJoinEligibility eligibility,
      GuestIdentityAccount identity,
      GuestChatIdentity matrix,
      de.caritas.cob.userservice.api.service.identity.GuestIdentityCatalog catalog,
      PlatformTransactionManager manager) {
    this.attempts = attempts;
    this.eligibility = eligibility;
    this.identity = identity;
    this.matrix = matrix;
    this.catalog = catalog;
    this.transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public GuestJoinAttempt provision(GuestJoinCapability capability) {
    InitialDispatch permit = null;
    var tried = new java.util.HashSet<String>();
    // Includes independently committed reconciliation before either repeated provider write.
    for (int step = 0; step < 8; step++) {
      var currentPermit = permit;
      var result =
          Objects.requireNonNull(transaction.execute(status -> advance(capability, currentPermit)));
      var attempt = result.attempt();
      // Report only after the outcome commits. Throwing inside advance would erase the evidence.
      if (attempt.getPhase() == Phase.IDENTITY_COLLISION
          || attempt.getPhase() == Phase.MATRIX_COLLISION) {
        tried.add(attempt.actualUsername());
        // A create the provider refused owns nothing, so another name may be tried at once. A
        // Matrix collision leaves an owned identity behind and must clean it up first; until that
        // is implemented it stays a conflict.
        var replacement =
            attempt.getPhase() == Phase.IDENTITY_COLLISION && attempt.mayTryAnotherCandidate()
                ? catalog.nextForAvatar(attempt.getOriginalAvatarKey(), tried)
                : java.util.Optional.<String>empty();
        if (replacement.isPresent()) {
          transaction.execute(status -> advanceCandidate(capability, replacement.get()));
          permit = null;
          continue;
        }
        throw new ConflictException("Selected guest name is occupied");
      }
      if (attempt.getPhase() == Phase.MATRIX_READY || attempt.getPhase() == Phase.COMPLETE)
        return attempt;
      // Hibernate updates the version at commit, before TransactionTemplate returns.
      permit =
          result.preparedPhase() == null
              ? null
              : new InitialDispatch(attempt.getId(), attempt.getVersion(), result.preparedPhase());
    }
    throw unknownOutcome();
  }

  private Step advance(GuestJoinCapability capability, InitialDispatch permit) {
    var attempt =
        attempts
            .findByKeyHashForUpdate(capability.attemptHash())
            .orElseThrow(() -> new NotFoundException("Guest Join attempt not found"));
    eligibility.requireActiveTarget(attempt);
    switch (attempt.getPhase()) {
      case PREPARED -> {
        attempt.beginIdentity();
        return new Step(attempt, Phase.IDENTITY_PENDING);
      }
      case IDENTITY_PENDING -> {
        if (permit != null && permit.matches(attempt))
          provisionIdentity(attempt, capability, false);
        else attempt.reconcileIdentity();
      }
      case IDENTITY_RECONCILING -> provisionIdentity(attempt, capability, true);
      case IDENTITY_COLLISION, MATRIX_COLLISION -> {
        /* Durable collision: no repeated provider dispatch. Replacement is a separate step. */
      }
      case IDENTITY_READY -> {
        attempt.beginMatrix();
        return new Step(attempt, Phase.MATRIX_PENDING);
      }
      case MATRIX_PENDING -> {
        if (permit != null && permit.matches(attempt)) provisionMatrix(attempt, capability, false);
        else attempt.reconcileMatrix();
      }
      case MATRIX_RECONCILING -> provisionMatrix(attempt, capability, true);
      case MATRIX_READY, COMPLETE -> {
        /* The caller performs local finalization and active-session checks. */
      }
      case TERMINAL -> throw new ForbiddenException("Guest Join attempt has ended");
    }
    return new Step(attempt, null);
  }

  /** Archives the collided candidate and binds the next one, under the attempt's own row lock. */
  private GuestJoinAttempt advanceCandidate(GuestJoinCapability capability, String nextUsername) {
    var attempt =
        attempts
            .findByKeyHashForUpdate(capability.attemptHash())
            .orElseThrow(() -> new ForbiddenException("Guest Join attempt is unknown"));
    attempt.advanceCandidate(nextUsername);
    return attempts.saveAndFlush(attempt);
  }

  private void provisionIdentity(
      GuestJoinAttempt attempt, GuestJoinCapability capability, boolean reconciling) {
    String name = attempt.actualUsername();
    String marker = attempt.ownershipMarker();
    String id;
    try {
      id =
          identity
              .findOwned(name, attempt.getTenantId(), marker)
              .orElseGet(
                  () ->
                      identity.createOnly(
                          name, capability.identityPassword(name), attempt.getTenantId(), marker));
    } catch (ConflictException conflict) {
      if (reconciling) throw unknownOutcome();
      attempt.identityCollision();
      return;
    }
    try {
      identity.completeOwned(id, name, attempt.getTenantId(), marker);
    } catch (ConflictException changedProfile) {
      // Creation/ownership has already succeeded; a later profile conflict is not a free-name
      // retry.
      throw unknownOutcome();
    }
    attempt.identityReady(id);
  }

  private void provisionMatrix(
      GuestJoinAttempt attempt, GuestJoinCapability capability, boolean reconciling) {
    String name = attempt.actualUsername();
    try {
      attempt.matrixReady(
          reconciling
              ? matrix.ensureOwned(name, capability.chatPassword(name))
              : matrix.createOnly(name, capability.chatPassword(name)));
    } catch (ConflictException conflict) {
      if (reconciling) throw unknownOutcome();
      attempt.matrixCollision();
    }
  }

  private ServiceUnavailableException unknownOutcome() {
    return new ServiceUnavailableException(
        "Guest provisioning outcome is unknown; retry the same request");
  }

  private record Step(GuestJoinAttempt attempt, Phase preparedPhase) {}

  private record InitialDispatch(Long attemptId, long version, Phase phase) {
    boolean matches(GuestJoinAttempt attempt) {
      return attemptId.equals(attempt.getId())
          && version == attempt.getVersion()
          && phase == attempt.getPhase();
    }
  }
}
