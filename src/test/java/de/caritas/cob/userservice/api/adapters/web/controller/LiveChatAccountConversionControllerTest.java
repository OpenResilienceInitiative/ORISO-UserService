package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.livechat.LiveChatAccountConversionService;
import org.junit.jupiter.api.Test;

class LiveChatAccountConversionControllerTest {
  private final LiveChatAccountConversionService service =
      mock(LiveChatAccountConversionService.class);
  private final AuthenticatedUser authenticated = mock(AuthenticatedUser.class);
  private final LiveChatAccountConversionController controller =
      new LiveChatAccountConversionController(service, authenticated);

  @Test
  void usesOnlyTheCurrentUserAndReturnsTheCurrentLoginName() {
    when(authenticated.getUsername()).thenReturn("guest-name");
    var result =
        controller.convert(
            new LiveChatAccountConversionController.Request(
                "guest-id", "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123", true));
    verify(service)
        .convert("guest-id", "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123");
    assertThat(result.getBody().username()).isEqualTo("guest-name");
    assertThat(result.getHeaders().getCacheControl()).contains("no-store");
  }

  @Test
  void rejectsMissingRecoveryConfirmationBeforeConversion() {
    assertThatThrownBy(
            () ->
                controller.convert(
                    new LiveChatAccountConversionController.Request(
                        "guest-id", "intent", "password", false)))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class);
    verifyNoInteractions(service);
  }

  @Test
  void doesNotClaimSuccessWhenConversionFails() {
    doThrow(new IllegalStateException("identity down")).when(service).convert(any(), any(), any());
    assertThatThrownBy(
            () ->
                controller.convert(
                    new LiveChatAccountConversionController.Request(
                        "guest-id", "intent", "password", true)))
        .isInstanceOf(IllegalStateException.class);
  }
}
