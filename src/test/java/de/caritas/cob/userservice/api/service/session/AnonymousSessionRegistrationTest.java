package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ORISO-UserService#1697: a guest is recognised by what was stored about the registration, never by
 * the shape of the User-ID. Animal handles ({@code katze_sam_4821}), counter names ({@code anon_7})
 * and timestamp names ({@code Anonymous-1700000000000}) must all get the same answer for the same
 * stored facts.
 */
class AnonymousSessionRegistrationTest {

  @ParameterizedTest
  @ValueSource(strings = {"katze_sam_4821", "anon_7", "Anonymous-1700000000000"})
  void matches_Should_RecogniseEveryGuestRegisteredAsAnonymous_Whatever_TheUsername(String name) {
    assertThat(
            AnonymousSessionRegistration.matches(
                session(RegistrationType.ANONYMOUS, "00000", name)))
        .isTrue();
  }

  /** The early live chat and invite links wrote REGISTERED sessions with the postcode 00000. */
  @ParameterizedTest
  @ValueSource(strings = {"katze_sam_4821", "anon_7", "Anonymous-1700000000000"})
  void matches_Should_RecogniseLiveChatStyleRegistrations_ByTheirPostcode(String name) {
    assertThat(
            AnonymousSessionRegistration.matches(
                session(RegistrationType.REGISTERED, "00000", name)))
        .isTrue();
  }

  /** The name text alone no longer makes a guest: only the stored registration facts do. */
  @ParameterizedTest
  @ValueSource(strings = {"katze_sam_4821", "anon_7", "Anonymous-1700000000000", "regular"})
  void matches_Should_NotDecideByTheUsername_When_TheRegistrationIsARegularOne(String name) {
    assertThat(
            AnonymousSessionRegistration.matches(
                session(RegistrationType.REGISTERED, "12345", name)))
        .isFalse();
  }

  @Test
  void matches_Should_AnswerFromTheStoredFields_When_OnlyTheDtoValuesAreKnown() {
    assertThat(AnonymousSessionRegistration.matches("ANONYMOUS", "12345")).isTrue();
    assertThat(AnonymousSessionRegistration.matches("REGISTERED", "00000")).isTrue();
    assertThat(AnonymousSessionRegistration.matches("REGISTERED", "12345")).isFalse();
    assertThat(AnonymousSessionRegistration.matches(null, null)).isFalse();
  }

  @Test
  void matches_Should_BeFalse_When_ThereIsNoSession() {
    assertThat(AnonymousSessionRegistration.matches((Session) null)).isFalse();
  }

  private static Session session(RegistrationType type, String postcode, String username) {
    var user = new User();
    user.setUsername(username);
    var session = new Session();
    session.setUser(user);
    session.setRegistrationType(type);
    session.setPostcode(postcode);
    return session;
  }
}
