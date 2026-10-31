package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
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
  private final TenantService tenants;
  private final IdentitySecondFactor secondFactor;

  public void beforeStart(String address) {
    currentAsker().ifPresent(user -> requireChangeAllowed(user, address));
  }

  public void beforeFinish() {
    currentAsker()
        .ifPresent(
            user -> {
              if (AskerIdentityPolicy.emailCollectionAllowed(tenants, user.getTenantId())) return;
              // Read only credential metadata (email), never OTP secrets. A pending different
              // target
              // must not become a new account address after the tenant disabled collection.
              var addresses = secondFactor.emailVerificationAddresses(user.getUserId());
              if (addresses == null
                  || addresses.isEmpty()
                  || addresses.stream()
                      .anyMatch(address -> !sameAddress(user.getEmail(), address))) {
                throw new ForbiddenException(
                    "Email collection is disabled for the pending verification");
              }
            });
  }

  private void requireChangeAllowed(User user, String target) {
    if (!sameAddress(user.getEmail(), target)) {
      AskerIdentityPolicy.requireEmailCollectionAllowed(tenants, user.getTenantId());
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
    return existing != null
        && !existing.isBlank()
        && target != null
        && existing.equalsIgnoreCase(target);
  }
}
