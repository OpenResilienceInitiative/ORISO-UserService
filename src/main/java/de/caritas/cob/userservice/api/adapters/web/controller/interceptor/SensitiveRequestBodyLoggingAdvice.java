package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

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
    return PasswordDTO.class.equals(targetType);
  }

  @Override
  public Object afterBodyRead(
      Object body,
      HttpInputMessage inputMessage,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    if (body instanceof PasswordDTO) {
      var passwordDTO = (PasswordDTO) body;
      return RedactedPasswordDTO.from(passwordDTO);
    }
    return body;
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
