package de.caritas.cob.userservice.api.service.livechat;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.PasswordPolicy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Promotes the authenticated anonymous Live Chat identity without replacing its Matrix user. */
@Service
@RequiredArgsConstructor
public class LiveChatAccountConversionService {
  private final @NonNull LiveChatAccountClaimService claims;
  private final @NonNull LiveChatAccountConversionExecutor executor;
  private final @NonNull AuthenticatedUser authenticated;

  public LiveChatAccountClaimService.Status status() {
    return claims.status(authenticated.getUserId());
  }

  public void convert(String expectedUserId, String intentId, String password) {
    if (!PasswordPolicy.isLongEnough(password))
      throw new BadRequestException("Password is too short");
    var userId = authenticated.getUserId();
    if (!java.util.Objects.equals(userId, expectedUserId)) {
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
          "Account changed");
    }
    claims.claimConversion(userId, intentId, password);
    executor.execute(userId, authenticated.getUsername(), intentId, password);
  }
}
