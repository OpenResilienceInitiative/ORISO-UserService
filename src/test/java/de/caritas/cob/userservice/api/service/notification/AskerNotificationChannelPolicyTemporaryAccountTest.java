package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.User;
import org.junit.jupiter.api.Test;

/** #1662: a temporary account cannot sign up for e-mail or push notifications. */
class AskerNotificationChannelPolicyTemporaryAccountTest {

  @Test
  void aTemporaryAccountMayNotOptIntoNotifications() {
    var guest = new User();
    guest.setTemporaryAccount(true);

    assertThatThrownBy(() -> AskerNotificationChannelPolicy.requireNotificationOptInAllowed(guest))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void aPermanentAccountKeepsTodaysRules() {
    var asker = new User();

    assertThatCode(() -> AskerNotificationChannelPolicy.requireNotificationOptInAllowed(asker))
        .doesNotThrowAnyException();
    assertThatCode(() -> AskerNotificationChannelPolicy.requireNotificationOptInAllowed(null))
        .doesNotThrowAnyException();
  }
}
