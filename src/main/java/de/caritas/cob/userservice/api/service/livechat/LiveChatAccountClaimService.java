package de.caritas.cob.userservice.api.service.livechat;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits mutually exclusive claims before irreversible identity actions. */
@Service
@RequiredArgsConstructor
public class LiveChatAccountClaimService {
  private final @NonNull LiveChatInactivityFence inactivity;
  private final @NonNull UserRepository users;
  private final @NonNull SessionRepository sessions;
  private final @NonNull LiveChatAccountClaimRepository records;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LiveChatAccountClaim claimConversion(String userId, String intentId, String password) {
    try {
      UUID.fromString(intentId);
    } catch (RuntimeException invalid) {
      throw new BadRequestException("Invalid conversion intent");
    }
    inactivity.requireActive(userId);
    var user = locked(userId);
    if (user.getDeletionLifecycleState()
            != de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState.ACTIVE
        || user.getReactivationOperationId() != null)
      throw new ConflictException("Account lifecycle action prevents conversion");
    if (user.getDeleteDate() != null) throw new ConflictException("Account deletion has started");
    if (user.isTemporaryAccount())
      throw new BadRequestException("Temporary direct-link accounts are not eligible");
    var claim = records.findForUpdate(userId).orElse(null);
    inactivity.requirePureAsker(userId);
    if (claim != null) {
      if (claim.getLifecycle() == LiveChatAccountLifecycle.CLEANUP_STARTED
          || claim.getLifecycle() == LiveChatAccountLifecycle.FINISH_PENDING)
        throw new ConflictException("Guest identity transition has started");
      if (claim.getLifecycle() == LiveChatAccountLifecycle.ANONYMOUS_FINISHED) {
        claim.setFinishedOrigin(true);
        claim.setLifecycle(LiveChatAccountLifecycle.PROMOTION_PENDING);
        claim.setIntentId(intentId);
        claim.setCredentialVerifier(ConversionCredentialVerifier.encode(password));
        return records.save(claim);
      }
      if (!intentId.equals(claim.getIntentId()))
        throw new ConflictException("Another conversion was started");
      if (claim.getLifecycle() == LiveChatAccountLifecycle.PROMOTION_PENDING
          && !ConversionCredentialVerifier.matches(password, claim.getCredentialVerifier()))
        throw new ConflictException("Conversion credentials differ");
      return claim;
    }
    if (sessions.findByUser(user).stream()
        .noneMatch(s -> s.getRegistrationType() == RegistrationType.ANONYMOUS))
      throw new BadRequestException("Account has no anonymous Live Chat");
    claim = new LiveChatAccountClaim(userId, LiveChatAccountLifecycle.PROMOTION_PENDING);
    claim.setIntentId(intentId);
    claim.setCredentialVerifier(ConversionCredentialVerifier.encode(password));
    return records.save(claim);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimCleanup(String userId) {
    inactivity.lockIfPresent(userId);
    if (users.findByUserIdForUpdate(userId).isEmpty()) return false;
    var claim = records.findForUpdate(userId).orElse(null);
    if (claim != null
        && (claim.getLifecycle() == LiveChatAccountLifecycle.ANONYMOUS_FINISHED
            || claim.getLifecycle() == LiveChatAccountLifecycle.FINISH_PENDING)) {
      claim.setLifecycle(LiveChatAccountLifecycle.CLEANUP_STARTED);
      records.save(claim);
      return true;
    }
    if (claim != null && claim.getLifecycle() != LiveChatAccountLifecycle.CLEANUP_STARTED)
      return false;
    if (claim == null)
      records.save(new LiveChatAccountClaim(userId, LiveChatAccountLifecycle.CLEANUP_STARTED));
    return true;
  }

  public enum FinishMode {
    CONVERTIBLE_FINISH,
    LEGACY_DISABLE,
    KEEP_IDENTITY
  }

  /** Claims the reason before disable; legacy lifecycle gaps cannot become reactivation proof. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public FinishMode claimNormalFinish(String userId) {
    var inactivityStates = inactivity.lockIfPresent(userId);
    var user = locked(userId);
    var claim = records.findForUpdate(userId).orElse(null);
    if (claim != null
        && (claim.getLifecycle() == LiveChatAccountLifecycle.PROMOTION_PENDING
            || claim.getLifecycle() == LiveChatAccountLifecycle.PROMOTED
            || claim.getLifecycle() == LiveChatAccountLifecycle.ANONYMOUS_FINISHED))
      return FinishMode.KEEP_IDENTITY;
    boolean eligible =
        inactivityStates.size() == 1
            && "ACTIVE".equals(inactivityStates.getFirst())
            && user.getDeleteDate() == null
            && !user.isTemporaryAccount()
            && user.getDeletionLifecycleState()
                == de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState
                    .ACTIVE
            && user.getReactivationOperationId() == null;
    if (claim != null && claim.getLifecycle() == LiveChatAccountLifecycle.FINISH_PENDING) {
      if (!eligible) throw new ConflictException("Account lifecycle action prevents finishing");
      return FinishMode.CONVERTIBLE_FINISH;
    }
    if (claim != null && claim.getLifecycle() == LiveChatAccountLifecycle.CLEANUP_STARTED)
      return FinishMode.LEGACY_DISABLE;
    if (claim != null) throw new ConflictException("Account lifecycle action prevents finishing");
    if (sessions.findByUser(user).stream()
        .noneMatch(s -> s.getRegistrationType() == RegistrationType.ANONYMOUS))
      throw new ConflictException("Account has no anonymous Live Chat");
    records.save(
        new LiveChatAccountClaim(
            userId,
            eligible
                ? LiveChatAccountLifecycle.FINISH_PENDING
                : LiveChatAccountLifecycle.CLEANUP_STARTED));
    return eligible ? FinishMode.CONVERTIBLE_FINISH : FinishMode.LEGACY_DISABLE;
  }

  /**
   * Explicit self/admin deletion remains permitted after promotion but wins before external
   * disable.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void claimIntentionalDeletion(String userId) {
    inactivity.lockIfPresent(userId);
    var user = locked(userId);
    var claim = records.findForUpdate(userId).orElse(null);
    if (claim != null
        && (claim.getLifecycle() == LiveChatAccountLifecycle.PROMOTION_PENDING
            || claim.getLifecycle() == LiveChatAccountLifecycle.FINISH_PENDING))
      throw new ConflictException("Live Chat identity transition is in progress");
    if (claim != null) {
      claim.setLifecycle(LiveChatAccountLifecycle.CLEANUP_STARTED);
      records.save(claim);
    } else if (sessions.findByUser(user).stream()
        .anyMatch(s -> s.getRegistrationType() == RegistrationType.ANONYMOUS)) {
      records.save(new LiveChatAccountClaim(userId, LiveChatAccountLifecycle.CLEANUP_STARTED));
    }
  }

  /** Serialize direct identity deactivation with promotion, including after the last Live Chat. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimIdentityDeactivation(String userId) {
    inactivity.lockIfPresent(userId);
    var user = users.findByUserIdForUpdate(userId).orElse(null);
    if (user == null) return false;
    var claim = records.findForUpdate(userId).orElse(null);
    if (claim != null) return claim.getLifecycle() == LiveChatAccountLifecycle.CLEANUP_STARTED;
    if (sessions.findByUser(user).stream()
        .anyMatch(s -> s.getRegistrationType() == RegistrationType.ANONYMOUS)) {
      records.save(new LiveChatAccountClaim(userId, LiveChatAccountLifecycle.CLEANUP_STARTED));
    }
    return true;
  }

  public record Status(String userId, String state, String intentId, boolean finishedOrigin) {}

  @Transactional(readOnly = true)
  public Status status(String userId) {
    var claim = records.findById(userId);
    if (claim.isPresent()) {
      return new Status(
          userId,
          claim.get().getLifecycle().name(),
          claim.get().getIntentId(),
          claim.get().isFinishedOrigin());
    }
    var user = users.findById(userId).orElseThrow(() -> new NotFoundException("Account not found"));
    boolean eligible =
        !user.isTemporaryAccount()
            && user.getDeleteDate() == null
            && sessions.findByUser(user).stream()
                .anyMatch(s -> s.getRegistrationType() == RegistrationType.ANONYMOUS);
    if (eligible) return new Status(userId, "ELIGIBLE", null, false);
    return new Status(
        userId,
        !user.isTemporaryAccount() && user.getDeleteDate() == null
            ? "ALREADY_REGISTERED"
            : "NOT_ELIGIBLE",
        null,
        false);
  }

  private User locked(String userId) {
    return users
        .findByUserIdForUpdate(userId)
        .orElseThrow(() -> new NotFoundException("Account not found"));
  }
}
