package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Rechecks the actual asker and pending provider addresses before email-OTP effects. */
@Component
@RequiredArgsConstructor
public class AskerEmailCollectionGuard {
  private final UserRepository users;
  private final AuthenticatedUser authenticatedUser;
  private final AskerEmailCollectionPolicy collectionPolicy;
  private final IdentitySecondFactor secondFactor;

  public void beforeStart(String address) {
    requireRegisteredAccount();
    currentAsker().ifPresent(user -> requireChangeAllowed(user, address));
  }

  public void beforeFinish() {
    requireRegisteredAccount();
    currentAsker()
        .ifPresent(
            user -> {
              ServiceUnavailableException unavailable = null;
              try {
                // A permitted account needs no extra credential-provider dependency.
                if (collectionPolicy.allowedForAccount(user)) return;
              } catch (ServiceUnavailableException policyUnavailable) {
                unavailable = policyUnavailable;
              }
              // Metadata can prove that no new address is collected even while collection is
              // closed.
              var addresses = secondFactor.emailVerificationAddresses(user.getUserId());
              if (addresses != null
                  && !addresses.isEmpty()
                  && addresses.stream().allMatch(address -> sameAddress(user.getEmail(), address)))
                return;
              if (unavailable != null) throw unavailable;
              throw new ForbiddenException(
                  "Email collection is disabled for the pending verification");
            });
  }

  private void requireRegisteredAccount() {
    if (authenticatedUser.isAnonymous()) {
      throw new ForbiddenException("Anonymous live-chat accounts cannot verify an email address");
    }
  }

  private void requireChangeAllowed(User user, String target) {
    if (!sameAddress(user.getEmail(), target)) {
      collectionPolicy.requireForAccount(user);
    }
  }

  private Optional<User> currentAsker() {
    String userId = authenticatedUser.getUserId();
    if (userId == null) {
      if (isAskerActor()) throw new ServiceUnavailableException("Asker identity unavailable");
      return Optional.empty();
    }
    var user = users.findByUserIdAndDeleteDateIsNull(userId);
    if (user.isEmpty() && isAskerActor()) {
      throw new ServiceUnavailableException("Asker identity unavailable");
    }
    return user;
  }

  private boolean isAskerActor() {
    return authenticatedUser.isAdviceSeeker()
        || authenticatedUser.getRoles() != null
            && authenticatedUser.getRoles().contains(UserRole.ANONYMOUS.getValue());
  }

  private boolean sameAddress(String existing, String target) {
    return AskerEmailCollectionPolicy.sameAddress(existing, target);
  }
}
