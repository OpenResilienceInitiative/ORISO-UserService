package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.identity.*;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class OnboardingEmailSecondFactorTest {
  @Mock IdentityProfileLookup profiles;
  @Mock IdentitySecondFactor otp;
  AccountInvite invite;

  @BeforeEach
  void setUp() {
    invite =
        AccountInvite.builder()
            .acceptedByUserId("owner")
            .recipientEmail("owner@example.org")
            .build();
    when(profiles.findById("owner"))
        .thenReturn(
            Optional.of(
                new IdentityProfile("owner", "owner-username", null, null, "owner@example.org")));
  }

  @Test
  void existingCredential_cannotBeReplacedBySendingMail() {
    when(otp.getOtpCredential("owner-username"))
        .thenReturn(new IdentityOtpCredential(true, null, null, IdentityOtpType.APP));
    var rejected =
        assertThrows(
            ResponseStatusException.class,
            () -> OnboardingEmailSecondFactor.start(invite, profiles, otp));
    assertEquals(HttpStatus.PRECONDITION_FAILED, rejected.getStatusCode());
    verify(otp, never()).initiateEmailVerification(anyString(), anyString());
  }

  @Test
  void sendFailure_isUnavailableAndDoesNotExposeProviderDetails() {
    when(otp.initiateEmailVerification("owner-username", "owner@example.org"))
        .thenReturn(IdentityEmailVerificationStart.failure("private provider details"));
    var failure =
        assertThrows(
            ServiceUnavailableException.class,
            () -> OnboardingEmailSecondFactor.start(invite, profiles, otp));
    assertFalse(failure.getMessage().contains("private"));
  }

  @Test
  void existingSetupResult_isNotProofOfCorrectCode() {
    when(otp.finishEmailVerification("owner-username", "123456"))
        .thenReturn(new IdentityEmailVerification(false, true, true, "owner@example.org"));
    var failure =
        assertThrows(
            ResponseStatusException.class,
            () -> OnboardingEmailSecondFactor.verify(invite, "123456", profiles, otp));
    assertEquals(HttpStatus.PRECONDITION_FAILED, failure.getStatusCode());
  }

  @Test
  void exhaustedCode_isRateLimited() {
    when(otp.finishEmailVerification("owner-username", "123456"))
        .thenReturn(new IdentityEmailVerification(false, false, false, null));
    var failure =
        assertThrows(
            ResponseStatusException.class,
            () -> OnboardingEmailSecondFactor.verify(invite, "123456", profiles, otp));
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.getStatusCode());
  }

  @Test
  void differentVerifiedRecipient_isRejected() {
    when(otp.finishEmailVerification("owner-username", "123456"))
        .thenReturn(new IdentityEmailVerification(true, false, true, "other@example.org"));
    var failure =
        assertThrows(
            ResponseStatusException.class,
            () -> OnboardingEmailSecondFactor.verify(invite, "123456", profiles, otp));
    assertEquals(HttpStatus.PRECONDITION_FAILED, failure.getStatusCode());
  }
}
