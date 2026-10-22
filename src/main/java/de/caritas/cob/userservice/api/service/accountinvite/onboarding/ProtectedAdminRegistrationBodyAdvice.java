package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Register-only in-memory original bytes; never a credential, journal or authority source. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="ORISO_ACCOUNT_LIFECYCLE_ENABLED", havingValue="true")
@ControllerAdvice
public final class ProtectedAdminRegistrationBodyAdvice extends RequestBodyAdviceAdapter {
  public static final String ATTRIBUTE = ProtectedAdminRegistrationBodyAdvice.class.getName() + ".original";
  @Override public boolean supports(MethodParameter parameter, Type target, Class<? extends HttpMessageConverter<?>> converter) { return false; }
}
