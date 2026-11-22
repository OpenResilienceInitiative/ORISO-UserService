package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.User;
import org.junit.jupiter.api.Test;

/** #1662: a temporary account keeps the name it joined with. */
class AskerIdentityPolicyTemporaryAccountTest {

  @Test
  void aTemporaryAccountMayNotChangeItsDisplayName() {
    var guest = new User();
    guest.setTemporaryAccount(true);

    assertThatThrownBy(() -> AskerIdentityPolicy.requireDisplayNameChangeAllowed(guest))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void aPermanentAccountKeepsTodaysRules() {
    var asker = new User();
    asker.setTemporaryAccount(false);

    assertThatCode(() -> AskerIdentityPolicy.requireDisplayNameChangeAllowed(asker))
        .doesNotThrowAnyException();
    assertThatCode(() -> AskerIdentityPolicy.requireDisplayNameChangeAllowed(null))
        .doesNotThrowAnyException();
  }
}
