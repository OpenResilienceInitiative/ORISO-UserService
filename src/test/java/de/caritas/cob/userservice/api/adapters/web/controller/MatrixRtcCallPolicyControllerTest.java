package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.controller.MatrixRtcCallPolicyController.CallPolicyRequest;
import de.caritas.cob.userservice.api.service.matrixrtc.CallMediaPolicy;
import de.caritas.cob.userservice.api.service.matrixrtc.MatrixRtcCallPolicyService;
import de.caritas.cob.userservice.api.service.matrixrtc.MatrixRtcPolicyTokenVerifier;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class MatrixRtcCallPolicyControllerTest {

  @Mock private MatrixRtcCallPolicyService callPolicyService;

  private static final String ROOM_ID = "!room:matrix.oriso.org";
  private static final String MATRIX_USER_ID = "@participant:matrix.oriso.org";

  @Test
  void rejectsMissingOrInvalidInternalCredentialBeforeResolvingRoom() {
    var controller =
        new MatrixRtcCallPolicyController(
            callPolicyService, new MatrixRtcPolicyTokenVerifier("expected-secret"));

    assertThat(
            controller
                .resolve(null, new CallPolicyRequest(ROOM_ID, MATRIX_USER_ID))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            controller
                .resolve("wrong-secret", new CallPolicyRequest(ROOM_ID, MATRIX_USER_ID))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(callPolicyService);
  }

  @Test
  void returnsCurrentPolicyForValidInternalCredential() {
    var policy = new CallMediaPolicy(true, false);
    when(callPolicyService.resolve(ROOM_ID, MATRIX_USER_ID)).thenReturn(policy);
    var controller =
        new MatrixRtcCallPolicyController(
            callPolicyService, new MatrixRtcPolicyTokenVerifier("expected-secret"));

    var response =
        controller.resolve("expected-secret", new CallPolicyRequest(ROOM_ID, MATRIX_USER_ID));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(policy);
    verify(callPolicyService).resolve(ROOM_ID, MATRIX_USER_ID);
  }

  @Test
  void verifierFailsClosedWhenNoCredentialIsConfigured() {
    var verifier = new MatrixRtcPolicyTokenVerifier("");

    assertThat(verifier.isValid("")).isFalse();
    assertThat(verifier.isValid("anything")).isFalse();
  }

  @Test
  void bindsTheCanonicalEnvironmentCompatiblePropertyName() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new SystemEnvironmentPropertySource(
                  "matrixrtc-test-env", Map.of("MATRIXRTC_CALL_POLICY_TOKEN", "expected-secret")));
      context.registerBean(MatrixRtcPolicyTokenVerifier.class);
      context.refresh();

      assertThat(context.getBean(MatrixRtcPolicyTokenVerifier.class).isValid("expected-secret"))
          .isTrue();
    }
  }
}
