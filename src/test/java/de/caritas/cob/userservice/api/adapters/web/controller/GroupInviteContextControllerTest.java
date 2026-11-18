package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.facade.GroupInviteRegistration;
import de.caritas.cob.userservice.api.model.GuestAccountMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** #1662: the public context of a Gesprächskreis invite carries the guest account mode only. */
class GroupInviteContextControllerTest {
  private final GroupInviteRegistration registration = mock(GroupInviteRegistration.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new GroupInviteContextController(registration))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
  }

  @Test
  void theContextNamesTheCappedModeAndNothingElse() throws Exception {
    when(registration.publicGuestAccountMode(600L, 70L, "secret"))
        .thenReturn(GuestAccountMode.GUEST_CHOOSES);

    mvc.perform(
            get("/users/chat/600/invite-context")
                .param("agencyId", "70")
                .param("inviteToken", "secret"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"guestAccountMode\":\"GUEST_CHOOSES\"}",
                    org.springframework.test.json.JsonCompareMode.STRICT));
  }

  @Test
  void aWrongSecretIsRefusedLikeTheRegistration() throws Exception {
    when(registration.publicGuestAccountMode(600L, 70L, "guessed"))
        .thenThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.GROUP_INVITE_INVALID, HttpStatus.FORBIDDEN));

    mvc.perform(
            get("/users/chat/600/invite-context")
                .param("agencyId", "70")
                .param("inviteToken", "guessed"))
        .andExpect(status().isForbidden());
  }

  @Test
  void theSecretAndTheAgencyAreRequired() throws Exception {
    mvc.perform(get("/users/chat/600/invite-context").param("agencyId", "70"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/users/chat/600/invite-context").param("inviteToken", "secret"))
        .andExpect(status().isBadRequest());
  }
}
