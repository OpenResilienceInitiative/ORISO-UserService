package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.session.SessionConsentService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** Gate 2 write path (ADR-022 decision 2): the help-seeker moves the pointer of their own room. */
@ExtendWith(MockitoExtension.class)
class SessionConsentControllerTest {

  @Mock private SessionConsentService sessionConsentService;
  @Mock private UserAccountService userAccountService;

  private SessionConsentController controller;

  @BeforeEach
  void setUp() {
    controller = new SessionConsentController(sessionConsentService, userAccountService);
  }

  @Test
  void recordConsentDelegatesToTheServiceAndAnswersNoContent() {
    var asker = new User();
    asker.setUserId("asker-1");
    when(userAccountService.retrieveValidatedUser()).thenReturn(asker);
    var request = new SessionConsentController.SessionConsentDTO();
    request.setLegalVersionId(7L);

    var response = controller.recordConsent(42L, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(sessionConsentService).recordConsent(42L, asker, 7L);
  }
}
