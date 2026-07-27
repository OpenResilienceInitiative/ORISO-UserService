package de.caritas.cob.userservice.api.service.user.validation;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ERROR;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.PASSWORD;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class UserAccountValidatorTest {

  @InjectMocks private UserAccountValidator userAccountValidator;
  @Mock private IdentityAuthentication identityAuthentication;

  @Test
  public void checkPasswordValidity_Should_ThrowBadRequestException_When_KeycloakLoginFails() {
    assertThrows(
        BadRequestException.class,
        () -> {
          when(identityAuthentication.login(anyString(), anyString()))
              .thenThrow(new BadRequestException(ERROR));

          this.userAccountValidator.checkPasswordValidity(USERNAME, PASSWORD);
        });
  }

  @Test
  public void checkPasswordValidity_Should_LogOutUser_When_LoginWasSuccessful() {
    IdentityLogin identityLogin = new IdentityLogin("access-token", 300, 600, "refresh-token");
    when(identityAuthentication.login(USERNAME, PASSWORD)).thenReturn(identityLogin);

    this.userAccountValidator.checkPasswordValidity(USERNAME, PASSWORD);

    verify(identityAuthentication, times(1)).login(USERNAME, PASSWORD);
    verify(identityAuthentication, times(1)).logout("refresh-token");
  }
}
