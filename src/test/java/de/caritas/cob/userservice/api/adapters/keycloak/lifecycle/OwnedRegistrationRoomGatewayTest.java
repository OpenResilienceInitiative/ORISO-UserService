package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSessionRoomGateway;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.port.out.OwnedMatrixEffect;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class OwnedRegistrationRoomGatewayTest {
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final MatrixSessionRoomGateway gateway =
      new MatrixSessionRoomGateway(matrix, mock(MatrixConfig.class));
  private final OwnedMatrixEffect effect = mock(OwnedMatrixEffect.class);

  @Test
  void durableStartPrecedesRoomCreationAndExactAcknowledgmentFollows() throws Exception {
    var body = new MatrixCreateRoomResponseDTO();
    body.setRoomId("!synthetic:example.test");
    when(matrix.createRoom("room", "alias", "synthetic-token")).thenReturn(ResponseEntity.ok(body));
    assertEquals(body.getRoomId(), gateway.createRoom("room", "alias", "synthetic-token", effect));
    var order = inOrder(effect, matrix);
    order.verify(effect).started("alias");
    order.verify(matrix).createRoom("room", "alias", "synthetic-token");
    order.verify(effect).created(body.getRoomId());
  }

  @Test
  void uncertainRoomOutcomeCannotBecomeDeletionAuthority() throws Exception {
    when(matrix.createRoom("room", "alias", "synthetic-token"))
        .thenThrow(new MatrixCreateRoomException("unconfirmed"));
    assertThrows(
        MatrixCreateRoomException.class,
        () -> gateway.createRoom("room", "alias", "synthetic-token", effect));
    verify(effect).started("alias");
    verify(effect, never()).created(any());
    verify(effect, never()).rejectedWithoutEffect();
  }

  @Test
  void absentRoomReceiptCannotBeAcknowledged() throws Exception {
    when(matrix.createRoomAsMatrixUser("room", "alias", "@synthetic:example.test"))
        .thenReturn(ResponseEntity.ok(new MatrixCreateRoomResponseDTO()));
    assertThrows(
        IllegalStateException.class,
        () -> gateway.createRoomAsUser("room", "alias", "@synthetic:example.test", effect));
    verify(effect).started("alias");
    verify(effect, never()).created(any());
  }
}
