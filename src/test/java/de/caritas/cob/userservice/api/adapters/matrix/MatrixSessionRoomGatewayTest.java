package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class MatrixSessionRoomGatewayTest {

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixConfig matrixConfig;
  @InjectMocks private MatrixSessionRoomGateway gateway;

  @Test
  void shouldTranslateRoomTransportResponseToStableRoomId() throws Exception {
    var body = new MatrixCreateRoomResponseDTO();
    body.setRoomId("!room:matrix");
    when(matrixSynapseService.createRoom("name", "alias", "token"))
        .thenReturn(ResponseEntity.ok(body));

    assertThat(gateway.createRoom("name", "alias", "token")).isEqualTo("!room:matrix");
  }

  @Test
  void shouldTranslateMissingRoomTransportBodyToNull() throws Exception {
    when(matrixSynapseService.createRoom("name", "alias", "token"))
        .thenReturn(ResponseEntity.ok(null));

    assertThat(gateway.createRoom("name", "alias", "token")).isNull();
  }

  @Test
  void shouldTranslateUserTransportResponseToStableMatrixId() throws Exception {
    var body = new MatrixCreateUserResponseDTO();
    body.setUserId("@consultant:matrix");
    when(matrixSynapseService.createUser("consultant", "password", "Consultant Name"))
        .thenReturn(ResponseEntity.ok(body));

    assertThat(gateway.createUser("consultant", "password", "Consultant Name"))
        .isEqualTo("@consultant:matrix");
  }

  @Test
  void shouldTranslateMissingUserTransportResponseToNull() throws Exception {
    when(matrixSynapseService.createUser("consultant", "password", "Consultant Name"))
        .thenReturn(null);

    assertThat(gateway.createUser("consultant", "password", "Consultant Name")).isNull();
  }

  @Test
  void shouldBuildUserIdFromConfiguredHomeserver() {
    when(matrixConfig.getServerName()).thenReturn("matrix.example.org");

    assertThat(gateway.userIdFor("consultant")).isEqualTo("@consultant:matrix.example.org");
  }

  @ParameterizedTest
  @CsvSource({
    "bart.simpson@dreambau.com,bart.simpson=40dreambau.com",
    "Bart.Simpson@Dreambau.com,bart.simpson=40dreambau.com",
    "bart.simpson=40dreambau.com,bart.simpson=3d40dreambau.com",
    "jürgen@example.org,j=c3=bcrgen=40example.org",
    "ordinary_user-1.2/+,ordinary_user-1.2/+"
  })
  void shouldUseTheSameSafeIdentityForProvisioningAndExistingAccountLookup(
      String username, String expectedLocalpart) throws Exception {
    when(matrixConfig.getServerName()).thenReturn("matrix.example.org");
    var expectedId = "@" + expectedLocalpart + ":matrix.example.org";
    var body = new MatrixCreateUserResponseDTO();
    body.setUserId(expectedId);
    when(matrixSynapseService.createUser(expectedLocalpart, "password", "Consultant"))
        .thenReturn(ResponseEntity.ok(body));

    assertThat(gateway.createUser(username, "password", "Consultant")).isEqualTo(expectedId);
    assertThat(gateway.userIdFor(username)).isEqualTo(expectedId);
  }

  @Test
  void shouldDelegateAssignmentRoomOperations() {
    when(matrixSynapseService.setUserPowerLevel("!room", "@user", 100, "token")).thenReturn(true);
    when(matrixSynapseService.removeUserFromRoom("!room", "@user", "token")).thenReturn(true);

    assertThat(gateway.setUserPowerLevel("!room", "@user", 100, "token")).isTrue();
    assertThat(gateway.removeUserFromRoom("!room", "@user", "token")).isTrue();
  }
}
