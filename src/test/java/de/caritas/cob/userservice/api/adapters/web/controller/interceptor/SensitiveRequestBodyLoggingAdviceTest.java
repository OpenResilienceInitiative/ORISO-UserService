package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import org.junit.jupiter.api.Test;

class SensitiveRequestBodyLoggingAdviceTest {

  private static final String OLD_PASSWORD = "old-secret-password";
  private static final String NEW_PASSWORD = "new-secret-password";

  private final SensitiveRequestBodyLoggingAdvice advice = new SensitiveRequestBodyLoggingAdvice();

  @Test
  void afterBodyRead_ShouldPreservePasswordValuesButRedactToString_ForPasswordDto() {
    var passwordDTO = new PasswordDTO();
    passwordDTO.setOldPassword(OLD_PASSWORD);
    passwordDTO.setNewPassword(NEW_PASSWORD);

    var result =
        (PasswordDTO) advice.afterBodyRead(passwordDTO, null, null, PasswordDTO.class, null);

    assertThat(result.getOldPassword()).isEqualTo(OLD_PASSWORD);
    assertThat(result.getNewPassword()).isEqualTo(NEW_PASSWORD);
    assertThat(result.toString()).doesNotContain(OLD_PASSWORD, NEW_PASSWORD);
    assertThat(result.toString()).contains("[REDACTED]");
  }

  @Test
  void afterBodyRead_ShouldLeaveOtherBodiesUnchanged() {
    var body = new Object();

    assertThat(advice.afterBodyRead(body, null, null, Object.class, null)).isSameAs(body);
  }
}
