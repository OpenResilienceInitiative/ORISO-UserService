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
    var user = locked(userId);
    if (user.getDeleteDate() != null) throw new ConflictException("Account deletion has started");
    if (user.isTemporaryAccount())
      throw new BadRequestException("Temporary direct-link accounts are not eligible");
    var claim = records.findForUpdate(userId).orElse(null);
    if (claim != null) {
      if (claim.getLifecycle() == LiveChatAccountLifecycle.CLEANUP_STARTED)
        throw new ConflictException("Guest cleanup has started");
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
    if (users.findByUserIdForUpdate(userId).isEmpty()) return false;
    var claim = records.findForUpdate(userId).orElse(null);
    if (claim != null && claim.getLifecycle() != LiveChatAccountLifecycle.CLEANUP_STARTED)
      return false;
    if (claim == null)
      records.save(new LiveChatAccountClaim(userId, LiveChatAccountLifecycle.CLEANUP_STARTED));
    return true;
  }

  public record Status(String userId, String state, String intentId) {}

  @Transactional(readOnly = true)
  public Status status(String userId) {
    var claim = records.findById(userId);
    if (claim.isPresent()) {
      return new Status(userId, claim.get().getLifecycle().name(), claim.get().getIntentId());
    }
    var user = users.findById(userId).orElseThrow(() -> new NotFoundException("Account not found"));
    boolean eligible =
        !user.isTemporaryAccount()
            && user.getDeleteDate() == null
            && sessions.findByUser(user).stream()
                .anyMatch(s -> s.getRegistrationType() == RegistrationType.ANONYMOUS);
    if (eligible) return new Status(userId, "ELIGIBLE", null);
    return new Status(
        userId,
        !user.isTemporaryAccount() && user.getDeleteDate() == null
            ? "ALREADY_REGISTERED"
            : "NOT_ELIGIBLE",
        null);
  }

  private User locked(String userId) {
    return users
        .findByUserIdForUpdate(userId)
        .orElseThrow(() -> new NotFoundException("Account not found"));
  }
}
