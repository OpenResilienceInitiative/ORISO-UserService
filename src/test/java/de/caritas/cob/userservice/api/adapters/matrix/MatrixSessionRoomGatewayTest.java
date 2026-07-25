package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class MatrixSessionRoomGatewayTest {

  @Mock private MatrixSynapseService matrixSynapseService;
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
}
