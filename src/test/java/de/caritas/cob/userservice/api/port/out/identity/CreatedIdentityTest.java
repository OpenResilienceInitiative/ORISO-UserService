package de.caritas.cob.userservice.api.port.out.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.identity.IdentityProvisioningException;
import org.junit.jupiter.api.Test;

class CreatedIdentityTest {

  @Test
  void requireUserIdReturnsNonBlankUserId() {
    assertThat(CreatedIdentity.requireUserId(new CreatedIdentity("user-id"))).isEqualTo("user-id");
  }

  @Test
  void requireUserIdRejectsMissingResult() {
    assertThatThrownBy(() -> CreatedIdentity.requireUserId(null))
        .isInstanceOf(IdentityProvisioningException.class);
  }

  @Test
  void requireUserIdRejectsBlankUserId() {
    assertThatThrownBy(() -> CreatedIdentity.requireUserId(new CreatedIdentity(" ")))
        .isInstanceOf(IdentityProvisioningException.class);
  }
}
