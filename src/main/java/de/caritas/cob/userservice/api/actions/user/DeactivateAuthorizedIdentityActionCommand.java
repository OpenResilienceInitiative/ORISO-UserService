package de.caritas.cob.userservice.api.actions.user;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Dispatches only an explicit, target-bound account lifecycle command. */
@Component
@RequiredArgsConstructor
public class DeactivateAuthorizedIdentityActionCommand
    implements ActionCommand<IdentityDeactivationTarget> {
  private final @NonNull IdentityDeactivator identityDeactivator;

  @Override
  public void execute(IdentityDeactivationTarget target) {
    try {
      identityDeactivator.deactivateUser(target.user().getUserId(), target.origin());
    } catch (org.springframework.web.client.RestClientException failure) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "Identity deactivation dependency failed",
          failure);
    }
  }
}
