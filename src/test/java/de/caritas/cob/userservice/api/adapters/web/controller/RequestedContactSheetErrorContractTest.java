package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.notification.RequestedContactSheetService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class RequestedContactSheetErrorContractTest {

  @Test
  void generatedUserOperationSendsOnlyForTheAuthenticatedOwner() throws Exception {
    var service = mock(RequestedContactSheetService.class);
    var user = mock(AuthenticatedUser.class);
    when(user.getUserId()).thenReturn("asker");
    var mvc = MockMvcBuilders.standaloneSetup(userController(service, user)).build();

    mvc.perform(post("/users/sessions/42/contact-sheet-email"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(service).send(42L, "asker");
  }

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
        MockMvcBuilders.standaloneSetup(userController(service, user))
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
        MockMvcBuilders.standaloneSetup(userController(service, user))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();

    mvc.perform(post("/users/sessions/42/contact-sheet-email"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string(""));
  }

  private UserController userController(
      RequestedContactSheetService service, AuthenticatedUser user) throws Exception {
    var constructor = UserController.class.getConstructors()[0];
    Object[] dependencies =
        Arrays.stream(constructor.getParameterTypes())
            .map(
                type -> {
                  if (type == RequestedContactSheetService.class) {
                    return service;
                  }
                  if (type == AuthenticatedUser.class) {
                    return user;
                  }
                  return mock(type);
                })
            .toArray();
    return (UserController) constructor.newInstance(dependencies);
  }
}
