package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatrixSessionAssignmentGatewayTest {

  private static final String ROOM_ID = "!room:matrix.example";
  private static final String OLD_MXID = "@old:matrix.example";
  private static final String NEW_MXID = "@new:matrix.example";
  private static final String OLD_TOKEN = "old-token";
  private static final String NEW_TOKEN = "new-token";

  @InjectMocks MatrixSessionAssignmentGateway gateway;
  @Mock MatrixSynapseService matrixSynapseService;
  @Mock Session session;
  @Mock Consultant currentConsultant;
  @Mock Consultant newConsultant;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(session.getId()).thenReturn(42L);
    org.mockito.Mockito.lenient().when(session.getMatrixRoomId()).thenReturn(ROOM_ID);
    org.mockito.Mockito.lenient().when(session.getConsultant()).thenReturn(currentConsultant);
    org.mockito.Mockito.lenient().when(currentConsultant.getMatrixUserId()).thenReturn(OLD_MXID);
    org.mockito.Mockito.lenient().when(newConsultant.getId()).thenReturn("new-consultant");
    org.mockito.Mockito.lenient().when(newConsultant.getMatrixUserId()).thenReturn(NEW_MXID);
  }

  @Test
  void preparesTheNewConsultantMembershipAndPowerLevel() throws Exception {
    when(matrixSynapseService.loginAsUserAccessToken(OLD_MXID)).thenReturn(OLD_TOKEN);
    when(matrixSynapseService.loginAsUserAccessToken(NEW_MXID)).thenReturn(NEW_TOKEN);
    when(matrixSynapseService.joinRoom(ROOM_ID, NEW_TOKEN)).thenReturn(true);
    when(matrixSynapseService.setUserPowerLevel(ROOM_ID, NEW_MXID, 100, OLD_TOKEN))
        .thenReturn(true);

    gateway.prepareAssignment(session, newConsultant);

    verify(matrixSynapseService).inviteUserToRoom(ROOM_ID, NEW_MXID, OLD_TOKEN);
    verify(matrixSynapseService).joinRoom(ROOM_ID, NEW_TOKEN);
    verify(matrixSynapseService).setUserPowerLevel(ROOM_ID, NEW_MXID, 100, OLD_TOKEN);
  }

  @Test
  void rejectsAHandOverWithoutMatrixIdentifiers() {
    when(session.getMatrixRoomId()).thenReturn(null);

    assertThatThrownBy(() -> gateway.prepareAssignment(session, newConsultant))
        .isInstanceOf(InternalServerErrorException.class);
  }

  @Test
  void readsMembersThroughTheMatrixAdminApi() {
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(OLD_MXID, NEW_MXID)));

    assertThat(gateway.findMemberIds(ROOM_ID)).containsExactly(OLD_MXID, NEW_MXID);
  }

  @Test
  void removesObsoleteConsultantsWithTheAssignedConsultantToken() {
    when(matrixSynapseService.loginAsUserAccessToken(NEW_MXID)).thenReturn(NEW_TOKEN);
    when(matrixSynapseService.removeUserFromRoom(ROOM_ID, OLD_MXID, NEW_TOKEN)).thenReturn(true);

    gateway.removeConsultants(session, newConsultant, List.of(currentConsultant));

    verify(matrixSynapseService).removeUserFromRoom(ROOM_ID, OLD_MXID, NEW_TOKEN);
  }
}
