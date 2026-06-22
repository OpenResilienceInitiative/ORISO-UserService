package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatrixAccessTokenServiceTest {

  private static final String MATRIX_USER_ID = "@consultant:matrix.example";

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantService consultantService;
  @Mock private UserService userService;

  private MatrixAuthProperties matrixAuthProperties;
  private MatrixAccessTokenService matrixAccessTokenService;

  @BeforeEach
  void setUp() {
    matrixAuthProperties = new MatrixAuthProperties();
    matrixAccessTokenService =
        new MatrixAccessTokenService(
            matrixSynapseService, consultantService, userService, matrixAuthProperties);
  }

  @Test
  void createBrowserTokenShouldMintTokenForConsultantMatrixUserId() {
    var authenticatedUser = authenticatedUser("consultant-id", UserRole.CONSULTANT);
    var consultant = new Consultant();
    consultant.setMatrixUserId(MATRIX_USER_ID);
    when(consultantService.getConsultant("consultant-id")).thenReturn(Optional.of(consultant));
    when(matrixSynapseService.loginAsUser(
            MATRIX_USER_ID, matrixAuthProperties.getBrowserTokenTtlMs()))
        .thenReturn(
            Map.of(
                "access_token", "matrix-token",
                "user_id", MATRIX_USER_ID,
                "device_id", "DEVICE"));

    var token = matrixAccessTokenService.createBrowserToken(authenticatedUser);

    assertThat(token).isPresent();
    assertThat(token.get().getAccessToken()).isEqualTo("matrix-token");
    assertThat(token.get().getUserId()).isEqualTo(MATRIX_USER_ID);
    assertThat(token.get().getDeviceId()).isEqualTo("DEVICE");
    assertThat(token.get().getExpiresInMs()).isEqualTo(matrixAuthProperties.getBrowserTokenTtlMs());
    verifyNoInteractions(userService);
  }

  @Test
  void createBrowserTokenShouldNotTouchMatrixWhenUserTokensAreDisabled() {
    matrixAuthProperties.setUserTokenEnabled(false);
    var authenticatedUser = authenticatedUser("user-id", UserRole.USER);

    var token = matrixAccessTokenService.createBrowserToken(authenticatedUser);
    var serverToken = matrixAccessTokenService.createServerAccessToken(authenticatedUser);

    assertThat(token).isEmpty();
    assertThat(serverToken).isNull();
    verifyNoInteractions(matrixSynapseService, consultantService, userService);
  }

  @Test
  void createBrowserTokenShouldReturnEmptyWhenMatrixUserIdIsMissing() {
    var authenticatedUser = authenticatedUser("user-id", UserRole.USER);
    var user = new User();
    when(userService.getUser("user-id")).thenReturn(Optional.of(user));

    var token = matrixAccessTokenService.createBrowserToken(authenticatedUser);

    assertThat(token).isEmpty();
    verifyNoInteractions(matrixSynapseService, consultantService);
  }

  private AuthenticatedUser authenticatedUser(String userId, UserRole role) {
    return new AuthenticatedUser(userId, "username", Set.of(role.getValue()), "kc-token", Set.of());
  }
}
