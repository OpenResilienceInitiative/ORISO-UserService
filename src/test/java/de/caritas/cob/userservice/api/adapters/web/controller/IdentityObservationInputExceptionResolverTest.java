package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.web.dto.IdentityObservationRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class IdentityObservationInputExceptionResolverTest {
  @Test
  void unrelatedControllerOperationKeepsNormalExceptionHandling() throws Exception {
    var handler =
        new HandlerMethod(
            mock(UserAdminController.class), UserAdminController.class.getMethod("getRoot"));
    var response = new MockHttpServletResponse();
    var error =
        new HttpMessageNotReadableException(
            "private rejected body", new MockHttpInputMessage(new byte[0]));
    assertThat(
            new IdentityObservationInputExceptionResolver()
                .resolveException(new MockHttpServletRequest(), response, handler, error))
        .isNull();
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(response.getHeader("Cache-Control")).isNull();
  }

  @Test
  void unrelatedExceptionOnObserverKeepsNormalExceptionHandling() throws Exception {
    var handler =
        new HandlerMethod(
            mock(UserAdminController.class),
            UserAdminController.class.getMethod(
                "observeIdentityOccupancy", IdentityObservationRequestDTO.class));
    var response = new MockHttpServletResponse();
    assertThat(
            new IdentityObservationInputExceptionResolver()
                .resolveException(
                    new MockHttpServletRequest(),
                    response,
                    handler,
                    new IllegalStateException("other error")))
        .isNull();
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(response.getHeader("Cache-Control")).isNull();
  }
}
