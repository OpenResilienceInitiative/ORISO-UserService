package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatrixRoomMembershipProviderTest {

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private UserRepository userRepository;
  @InjectMocks private MatrixRoomMembershipProvider provider;

  @Test
  void joinedRoomsForConsultantUsesMatrixIdentity() {
    var consultant = consultantWithMatrixId("@c:oriso");
    when(matrixSynapseService.getJoinedRoomsForMatrixUser("@c:oriso"))
        .thenReturn(List.of("!one:oriso", "!two:oriso"));

    assertThat(provider.joinedRoomsForConsultant(consultant))
        .containsExactlyInAnyOrder("!one:oriso", "!two:oriso");
  }

  @Test
  void joinedRoomsForAccountResolvesMatrixIdentity() {
    var user =
        User.builder()
            .userId("user")
            .username("user")
            .email("user@example.org")
            .matrixUserId("@u:oriso")
            .build();
    when(consultantRepository.findById("user")).thenReturn(Optional.empty());
    when(userRepository.findById("user")).thenReturn(Optional.of(user));
    when(matrixSynapseService.getJoinedRoomsForMatrixUser("@u:oriso"))
        .thenReturn(List.of("!room:oriso"));

    assertThat(provider.joinedRoomsForAccount("user")).containsExactly("!room:oriso");
    verify(userRepository).findById("user");
  }

  @Test
  void joinedRoomsFailClosedWhenMatrixLookupFails() {
    var consultant = consultantWithMatrixId("@c:oriso");
    when(matrixSynapseService.getJoinedRoomsForMatrixUser("@c:oriso"))
        .thenThrow(new IllegalStateException("matrix down"));

    assertThat(provider.joinedRoomsForConsultant(consultant)).isEmpty();
  }

  private Consultant consultantWithMatrixId(String matrixUserId) {
    return Consultant.builder()
        .id("consultant")
        .username("consultant")
        .firstName("Connie")
        .lastName("Sultant")
        .email("consultant@example.org")
        .matrixUserId(matrixUserId)
        .build();
  }
}
