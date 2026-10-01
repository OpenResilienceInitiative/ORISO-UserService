package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.notification.RequestedContactSheetService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class RequestedContactSheetErrorContractTest {

  @Test
  void upstreamFailureReturnsBadGatewayWithoutUpstreamDetails() throws Exception {
    var service = mock(RequestedContactSheetService.class);
    var user = mock(AuthenticatedUser.class);
    when(user.getUserId()).thenReturn("asker");
    doThrow(
            new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Agency contact details are unavailable"))
        .when(service)
        .send(42L, "asker");
    var mvc =
        MockMvcBuilders.standaloneSetup(new RequestedContactSheetController(service, user))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();

    mvc.perform(post("/users/sessions/42/contact-sheet-email"))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(""));
  }

  @Test
  void missingAgencyPreservesExistingInternalErrorStatusWithoutDetails() throws Exception {
    var service = mock(RequestedContactSheetService.class);
    var user = mock(AuthenticatedUser.class);
    when(user.getUserId()).thenReturn("asker");
    doThrow(new IllegalStateException("Agency contact details are unavailable"))
        .when(service)
        .send(42L, "asker");
    var mvc =
        MockMvcBuilders.standaloneSetup(new RequestedContactSheetController(service, user))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();

    mvc.perform(post("/users/sessions/42/contact-sheet-email"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string(""));
  }
}
