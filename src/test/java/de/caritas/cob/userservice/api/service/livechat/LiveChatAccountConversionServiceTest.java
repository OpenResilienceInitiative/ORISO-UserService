package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import org.junit.jupiter.api.Test;

class LiveChatAccountConversionServiceTest {
  private final LiveChatAccountClaimService claims = mock(LiveChatAccountClaimService.class);
  private final LiveChatAccountConversionExecutor executor =
      mock(LiveChatAccountConversionExecutor.class);
  private final AuthenticatedUser authenticated = mock(AuthenticatedUser.class);
  private final LiveChatAccountConversionService service =
      new LiveChatAccountConversionService(claims, executor, authenticated);

  @Test
  void commitsClaimBeforeTheExternalIdentityStep() {
    when(authenticated.getUserId()).thenReturn("guest-id");
    when(authenticated.getUsername()).thenReturn("guest-name");
    service.convert("guest-id", "intent", "chosen-password-123");
    var order = inOrder(claims, executor);
    order.verify(claims).claimConversion("guest-id", "intent", "chosen-password-123");
    order.verify(executor).execute("guest-id", "guest-name", "intent", "chosen-password-123");
  }

  @Test
  void changedAccountRefusesBeforeClaimOrIdentityAction() {
    when(authenticated.getUserId()).thenReturn("guest-b");
    assertThatThrownBy(() -> service.convert("guest-a", "intent", "chosen-password-123"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    verifyNoInteractions(claims, executor);
  }

  @Test
  void changedToRegularAccountAlsoRefusesBeforeClaimOrIdentityAction() {
    when(authenticated.getUserId()).thenReturn("regular-user");
    assertThatThrownBy(() -> service.convert("guest-a", "intent", "chosen-password-123"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    verifyNoInteractions(claims, executor);
  }

  @Test
  void mixedUserAndConsultantCannotClaimOrChangeCredentials() {
    when(authenticated.getUserId()).thenReturn("guest-id");
    when(authenticated.isConsultant()).thenReturn(true);
    assertThatThrownBy(() -> service.convert("guest-id", "intent", "chosen-password-123"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    org.assertj.core.api.Assertions.assertThat(service.status().state()).isEqualTo("NOT_ELIGIBLE");
    verifyNoInteractions(claims, executor);
  }

  @Test
  void mixedUserAndAdminCannotClaimOrChangeCredentials() {
    when(authenticated.getUserId()).thenReturn("guest-id");
    when(authenticated.isAgencySuperAdmin()).thenReturn(true);
    assertThatThrownBy(() -> service.convert("guest-id", "intent", "chosen-password-123"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    org.assertj.core.api.Assertions.assertThat(service.status().state()).isEqualTo("NOT_ELIGIBLE");
    verifyNoInteractions(claims, executor);
  }

  @Test
  void shortPasswordNeverClaimsOrCallsIdentity() {
    assertThatThrownBy(() -> service.convert("guest-id", "intent", "short"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class);
    verifyNoInteractions(claims, executor);
  }
}
