package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.adapters.web.controller.TenantAdminOnboardingController;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Register-only in-memory original bytes; never a credential, journal or authority source. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "ORISO_ACCOUNT_LIFECYCLE_ENABLED",
    havingValue = "true")
@ControllerAdvice
public final class ProtectedAdminRegistrationBodyAdvice extends RequestBodyAdviceAdapter {
  public static final String ATTRIBUTE =
      ProtectedAdminRegistrationBodyAdvice.class.getName() + ".original";
  public static final int MAX_BYTES = 65536;

  @Override
  public boolean supports(
      MethodParameter parameter, Type target, Class<? extends HttpMessageConverter<?>> converter) {
    return parameter.getContainingClass() == TenantAdminOnboardingController.class
        && parameter.getMethod() != null
        && "registerTenantAdmin".equals(parameter.getMethod().getName())
        && parameter.getParameterType()
            == TenantAdminOnboardingController.TenantAdminRegistrationRequestDTO.class;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input,
      MethodParameter parameter,
      Type target,
      Class<? extends HttpMessageConverter<?>> converter)
      throws IOException {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current))
      throw new HttpMessageNotReadableException("Original registration request unavailable", input);
    byte[] original = input.getBody().readNBytes(MAX_BYTES + 1);
    if (original.length > MAX_BYTES)
      throw new HttpMessageNotReadableException("Registration body exceeds limit", input);
    current.getRequest().setAttribute(ATTRIBUTE, original.clone());
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return new ByteArrayInputStream(original);
      }

      @Override
      public HttpHeaders getHeaders() {
        return input.getHeaders();
      }
    };
  }
}
