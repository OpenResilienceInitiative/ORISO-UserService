package de.caritas.cob.userservice.api.config;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class ConsultantActivityInterceptorTest {

  private static final String CONSULTANT_ID = "consultant-1";

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private ConsultantActivityRegistry consultantActivityRegistry;
  @Mock private ObjectProvider<AuthenticatedUser> authenticatedUserProvider;
  @Mock private ObjectProvider<ConsultantActivityRegistry> consultantActivityRegistryProvider;

  private ConsultantActivityInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor =
        new ConsultantActivityInterceptor(
            authenticatedUserProvider, consultantActivityRegistryProvider);
  }

  private void givenAuthenticatedConsultant() {
    when(authenticatedUserProvider.getIfAvailable()).thenReturn(authenticatedUser);
    when(consultantActivityRegistryProvider.getIfAvailable())
        .thenReturn(consultantActivityRegistry);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
  }

  @Test
  void preHandle_Should_notRefreshTtl_When_getAvailabilityPath() {
    var request =
        new MockHttpServletRequest("GET", ConsultantActivityInterceptor.AVAILABILITY_PATH);
    var response = new MockHttpServletResponse();

    interceptor.preHandle(request, response, new Object());

    verify(consultantActivityRegistry, never()).refreshIfAvailable(CONSULTANT_ID);
  }

  @Test
  void preHandle_Should_refreshTtl_When_putAvailabilityPath() {
    givenAuthenticatedConsultant();
    var request =
        new MockHttpServletRequest("PUT", ConsultantActivityInterceptor.AVAILABILITY_PATH);
    var response = new MockHttpServletResponse();

    interceptor.preHandle(request, response, new Object());

    verify(consultantActivityRegistry).refreshIfAvailable(CONSULTANT_ID);
  }

  @Test
  void preHandle_Should_refreshTtl_When_getOtherPath() {
    givenAuthenticatedConsultant();
    var request = new MockHttpServletRequest("GET", "/users/sessions/open");
    var response = new MockHttpServletResponse();

    interceptor.preHandle(request, response, new Object());

    verify(consultantActivityRegistry).refreshIfAvailable(CONSULTANT_ID);
  }
}
