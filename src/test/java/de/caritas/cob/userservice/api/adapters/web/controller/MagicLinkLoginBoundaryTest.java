package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.facade.CreateEnquiryMessageFacade;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange.MagicLinkChallenge;
import de.caritas.cob.userservice.api.service.archive.SessionDeleteService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual public mapping, JSON validation, delegate and advice; only session production is mocked. */
class MagicLinkLoginBoundaryTest {

  private final MagicLinkLoginService sessions = mock(MagicLinkLoginService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    var delegate =
        new UserRegistrationControllerDelegate(
            mock(UserAccountService.class),
            mock(SessionService.class),
            mock(CreateEnquiryMessageFacade.class),
            mock(AssignEnquiryFacade.class),
            mock(CreateUserFacade.class),
            mock(CreateNewSessionFacade.class),
            mock(Messaging.class),
            mock(ConsultantDtoMapper.class),
            mock(UserHelper.class),
            mock(IdentityManaging.class),
            sessions,
            mock(PasswordResetService.class),
            mock(SessionDeleteService.class));
    var controller = mock(UserController.class, CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(controller, "userRegistrationControllerDelegate", delegate);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
  }

  @Test
  void firstRequestPreservesTheExistingSnakeCaseSessionContract() throws Exception {
    when(sessions.consumeMagicLink("link"))
        .thenReturn(Optional.of(session()));

    consume("{\"token\":\"link\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token").value("access"))
        .andExpect(jsonPath("$.refresh_token").value("refresh"))
        .andExpect(jsonPath("$.expires_in").value(300))
        .andExpect(jsonPath("$.accessToken").doesNotExist());
  }

  @Test
  void codeContinuationKeepsTheSameLinkAndForwardsOnlyItsSixDigits() throws Exception {
    when(sessions.consumeMagicLink("link", "123456"))
        .thenReturn(Optional.of(session()));

    consume("{\"token\":\"link\",\"otp\":\"123456\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token").value("access"));
    verify(sessions).consumeMagicLink("link", "123456");
    verifyNoMoreInteractions(sessions);
  }

  @Test
  void malformedCodesAreRefusedBeforeClaimingAnyLink() throws Exception {
    for (String code : new String[] {"", "12345", "1234567", "１２３４５６", " 123456", "abcdef"}) {
      consume("{\"token\":\"link\",\"otp\":\"" + code + "\"}")
          .andExpect(status().isBadRequest());
      verifyNoInteractions(sessions);
    }
  }

  @Test
  void blankLinkIsRefusedBeforeCallingTheSessionProducer() throws Exception {
    consume("{\"token\":\" \",\"otp\":\"123456\"}")
        .andExpect(status().isBadRequest());
    verifyNoInteractions(sessions);
  }

  @Test
  void appChallengeExposesOnlyTheFiniteChallengeAndCannotBeCached() throws Exception {
    var challenge = new MagicLinkChallenge("APP", null, 400);
    challenge.initCause(new IllegalStateException("synthetic-private-provider-body"));
    when(sessions.consumeMagicLink("link")).thenThrow(challenge);

    consume("{\"token\":\"link\"}")
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json(
            "{\"error\":\"invalid_grant\",\"error_description\":\"Missing totp\",\"otpType\":\"APP\"}",
            org.springframework.test.json.JsonCompareMode.STRICT));
  }

  @Test
  void emailChallengePreservesItsCountdown() throws Exception {
    when(sessions.consumeMagicLink("link"))
        .thenThrow(new MagicLinkChallenge("EMAIL", 30, 400));

    consume("{\"token\":\"link\"}")
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.otpType").value("EMAIL"))
        .andExpect(jsonPath("$.resendAvailableInSeconds").value(30));
  }

  @Test
  void emailRateLimitRetainsTheChallengeAndRetryTime() throws Exception {
    when(sessions.consumeMagicLink("link"))
        .thenThrow(new MagicLinkChallenge("EMAIL", 60, 429));

    consume("{\"token\":\"link\"}")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.otpType").value("EMAIL"))
        .andExpect(jsonPath("$.resendAvailableInSeconds").value(60));
  }

  @Test
  void invalidFactorDoesNotExposeSessionOrProviderDetails() throws Exception {
    when(sessions.consumeMagicLink("link", "123456"))
        .thenReturn(Optional.empty());

    consume("{\"token\":\"link\",\"otp\":\"123456\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));
    verify(sessions).consumeMagicLink("link", "123456");
    verifyNoMoreInteractions(sessions);
  }

  private ResultActions consume(String body) {
    return assertDoesNotThrow(
        () -> mvc.perform(post("/users/magic-link/consume")
            .contentType(MediaType.APPLICATION_JSON).content(body)));
  }

  private static IdentitySession session() {
    return new IdentitySession("access", 300, 600, "refresh", "Bearer", "state", "openid profile");
  }
}
