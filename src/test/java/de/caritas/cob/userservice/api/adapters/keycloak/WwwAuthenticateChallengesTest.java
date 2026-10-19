package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class WwwAuthenticateChallengesTest {

  @Test
  void schemesOf_Should_ReadBareAndParameterizedChallenges() {
    assertThat(WwwAuthenticateChallenges.schemesOf("Bearer")).containsExactly("bearer");
    assertThat(WwwAuthenticateChallenges.schemesOf("Bearer realm=\"oriso\", error=\"x\""))
        .containsExactly("bearer");
  }

  @Test
  void schemesOf_Should_ReadEveryChallengeInOneField() {
    assertThat(WwwAuthenticateChallenges.schemesOf("Basic realm=\"otp\", Bearer"))
        .containsExactly("basic", "bearer");
    assertThat(WwwAuthenticateChallenges.schemesOf("Negotiate abc==, Bearer realm=\"r\""))
        .containsExactly("negotiate", "bearer");
  }

  @Test
  void schemesOf_Should_NotSplitOnCommasInsideQuotedValues() {
    assertThat(
            WwwAuthenticateChallenges.schemesOf(
                "Basic realm=\"a, Bearer b\", title=\"say \\\"x, Bearer\\\"\""))
        .containsExactly("basic");
  }

  @Test
  void schemesOf_Should_TreatSpacedAuthParamAsParameter() {
    assertThat(WwwAuthenticateChallenges.schemesOf("Basic, realm = \"otp\""))
        .containsExactly("basic");
  }

  @Test
  void containsScheme_Should_InspectRepeatedHeaderFields() {
    var headers = new HttpHeaders();
    headers.add(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"otp\"");
    headers.add(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"oriso\"");

    assertThat(WwwAuthenticateChallenges.containsScheme(headers, "Bearer")).isTrue();
  }

  @Test
  void containsScheme_Should_RejectMissingOrOtherChallenges() {
    var basicOnly = new HttpHeaders();
    basicOnly.add(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Bearer\"");

    assertThat(WwwAuthenticateChallenges.containsScheme(null, "Bearer")).isFalse();
    assertThat(WwwAuthenticateChallenges.containsScheme(new HttpHeaders(), "Bearer")).isFalse();
    assertThat(WwwAuthenticateChallenges.containsScheme(basicOnly, "Bearer")).isFalse();
  }
}
