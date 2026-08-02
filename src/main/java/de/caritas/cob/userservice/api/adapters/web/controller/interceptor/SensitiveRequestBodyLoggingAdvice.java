package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice
public class SensitiveRequestBodyLoggingAdvice extends RequestBodyAdviceAdapter {

  private static final String REDACTED = "[REDACTED]";

  @Override
  public boolean supports(
      MethodParameter methodParameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    return PasswordDTO.class.equals(targetType) || OneTimePasswordDTO.class.equals(targetType);
  }

  @Override
  public Object afterBodyRead(
      Object body,
      HttpInputMessage inputMessage,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    if (body instanceof PasswordDTO passwordDTO) {
      return RedactedPasswordDTO.from(passwordDTO);
    }
    // ADR-018: a one-time code is a credential too. Without this it would survive in a request-body
    // log line long enough to be replayed inside its validity window.
    if (body instanceof OneTimePasswordDTO otpDTO) {
      return RedactedOneTimePasswordDTO.from(otpDTO);
    }
    return body;
  }

  static final class RedactedOneTimePasswordDTO extends OneTimePasswordDTO {

    static RedactedOneTimePasswordDTO from(OneTimePasswordDTO otpDTO) {
      var redacted = new RedactedOneTimePasswordDTO();
      redacted.setSecret(otpDTO.getSecret());
      redacted.setOtp(otpDTO.getOtp());
      return redacted;
    }

    @Override
    public String toString() {
      return "class OneTimePasswordDTO {\n"
          + "    secret: "
          + REDACTED
          + "\n"
          + "    otp: "
          + REDACTED
          + "\n"
          + "}";
    }
  }

  static final class RedactedPasswordDTO extends PasswordDTO {

    static RedactedPasswordDTO from(PasswordDTO passwordDTO) {
      var redactedPasswordDTO = new RedactedPasswordDTO();
      redactedPasswordDTO.setOldPassword(passwordDTO.getOldPassword());
      redactedPasswordDTO.setNewPassword(passwordDTO.getNewPassword());
      return redactedPasswordDTO;
    }

    @Override
    public String toString() {
      return "class PasswordDTO {\n"
          + "    oldPassword: "
          + REDACTED
          + "\n"
          + "    newPassword: "
          + REDACTED
          + "\n"
          + "}";
    }
  }
}
