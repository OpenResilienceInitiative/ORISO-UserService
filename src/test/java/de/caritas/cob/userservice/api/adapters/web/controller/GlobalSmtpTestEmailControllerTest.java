package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.notification.GlobalSmtpTestEmailService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.validation.Valid;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class GlobalSmtpTestEmailControllerTest {

  @Mock private GlobalSmtpTestEmailService globalSmtpTestEmailService;

  @InjectMocks private GlobalSmtpTestEmailController controller;

  @Test
  void sendGlobalSmtpTestEmail_happyPath_returnsOkAndDelegates() throws Exception {
    // Business reason: admins need immediate success confirmation when SMTP settings are valid.
    var dto = validDto();
    ArgumentCaptor<GlobalSmtpTestEmailDTO> dtoCaptor =
        ArgumentCaptor.forClass(GlobalSmtpTestEmailDTO.class);

    var response = controller.sendGlobalSmtpTestEmail(dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(globalSmtpTestEmailService).sendTestEmail(dtoCaptor.capture());
    assertEquals(dto, dtoCaptor.getValue());
  }

  @Test
  void sendGlobalSmtpTestEmail_illegalStateException_returnsBadRequestWithExceptionMessage()
      throws Exception {
    // Business reason: misconfiguration details should be surfaced directly for quick correction.
    var dto = validDto();
    doThrow(new IllegalStateException("SMTP host missing"))
        .when(globalSmtpTestEmailService)
        .sendTestEmail(dto);

    var response = controller.sendGlobalSmtpTestEmail(dto);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("SMTP host missing", ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void sendGlobalSmtpTestEmail_authenticationFailedException_returnsBadRequestWithAuthMessage()
      throws Exception {
    // Business reason: auth failures need dedicated guidance to reduce support loops.
    var dto = validDto();
    doThrow(new AuthenticationFailedException("bad credentials"))
        .when(globalSmtpTestEmailService)
        .sendTestEmail(dto);

    var response = controller.sendGlobalSmtpTestEmail(dto);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(
        "SMTP authentication failed. Please verify stored SMTP credentials and provider auth policy.",
        ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void sendGlobalSmtpTestEmail_genericException_returnsBadRequestWithDefaultMessage()
      throws Exception {
    // Business reason: unexpected SMTP failures should still return a stable, actionable message.
    var dto = validDto();
    doThrow(new RuntimeException("boom")).when(globalSmtpTestEmailService).sendTestEmail(dto);

    var response = controller.sendGlobalSmtpTestEmail(dto);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(
        "SMTP test mail could not be sent. Please verify your SMTP settings.",
        ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void sendGlobalSmtpTestEmail_requestBodyParameter_hasValidAnnotation() throws Exception {
    // Business reason: bean validation on SMTP test payload protects controller boundary inputs.
    Method method =
        GlobalSmtpTestEmailController.class.getMethod(
            "sendGlobalSmtpTestEmail", GlobalSmtpTestEmailDTO.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(Valid.class));
  }

  @Test
  void sendGlobalSmtpTestEmail_noPreAuthorize_endpointIsPublic() throws Exception {
    // Endpoint is intentionally public — no role restriction
    Method method =
        GlobalSmtpTestEmailController.class.getMethod(
            "sendGlobalSmtpTestEmail", GlobalSmtpTestEmailDTO.class);

    boolean preAuthorizePresent = method.isAnnotationPresent(PreAuthorize.class);
    assertFalse(preAuthorizePresent, "Expected no @PreAuthorize on sendGlobalSmtpTestEmail");
  }

  private GlobalSmtpTestEmailDTO validDto() {
    var dto = new GlobalSmtpTestEmailDTO();
    dto.setHost("smtp.example.org");
    dto.setPort(587);
    dto.setSecure(true);
    dto.setFrom("from@example.org");
    dto.setRecipientEmail("to@example.org");
    dto.setEmailThemeColor("#123456");
    return dto;
  }
}
