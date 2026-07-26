package de.caritas.cob.userservice.api.service.sessionlist;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserChatDTO;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.service.matrix.MatrixRoomMembershipProvider;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantChatEnricherTest {

  @InjectMocks private ConsultantChatEnricher consultantChatEnricher;
  @Mock private MatrixRoomMembershipProvider matrixRoomMembershipProvider;
  @Mock private ConsultantDataFacade consultantDataFacade;

  @Test
  void marksChatAsSubscribedWhenConsultantJoinedTheMatrixRoom() {
    var response = responseForRoom("!joined:matrix.example");
    when(matrixRoomMembershipProvider.joinedRoomsForConsultant(CONSULTANT))
        .thenReturn(Set.of("!joined:matrix.example"));

    consultantChatEnricher.updateRequiredConsultantChatValues(List.of(response), CONSULTANT);

    assertThat(response.getChat().isSubscribed()).isTrue();
  }

  @Test
  void marksChatAsNotSubscribedWhenConsultantDidNotJoinTheMatrixRoom() {
    var response = responseForRoom("!other:matrix.example");
    when(matrixRoomMembershipProvider.joinedRoomsForConsultant(CONSULTANT))
        .thenReturn(Set.of("!joined:matrix.example"));

    consultantChatEnricher.updateRequiredConsultantChatValues(List.of(response), CONSULTANT);

    assertThat(response.getChat().isSubscribed()).isFalse();
  }

  @Test
  void usesDatabaseMetadataAndAddsConsultantDisplayNames() {
    var start = LocalDateTime.of(2026, 7, 26, 9, 30);
    var response = responseForRoom("!room:matrix.example");
    response.getChat().setMessagesRead(false);
    response.getChat().setStartDateWithTime(start);
    when(matrixRoomMembershipProvider.joinedRoomsForConsultant(CONSULTANT)).thenReturn(Set.of());

    var result =
        consultantChatEnricher.updateRequiredConsultantChatValues(List.of(response), CONSULTANT);

    assertThat(result).containsExactly(response);
    assertThat(response.getChat().isMessagesRead()).isTrue();
    assertThat(response.getLatestMessage()).isEqualTo(Timestamp.valueOf(start));
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(List.of(response));
  }

  private ConsultantSessionResponseDTO responseForRoom(String roomId) {
    var chat = new UserChatDTO();
    chat.setGroupId(roomId);
    return new ConsultantSessionResponseDTO().chat(chat);
  }
}
