package de.caritas.cob.userservice.api.service.sessionlist;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.matrix.MatrixRoomMembershipProvider;
import java.sql.Timestamp;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Adds Matrix membership and database-backed metadata to consultant group-chat entries. */
@Service
@RequiredArgsConstructor
public class ConsultantChatEnricher {

  private final @NonNull MatrixRoomMembershipProvider matrixRoomMembershipProvider;
  private final @NonNull ConsultantDataFacade consultantDataFacade;

  public List<ConsultantSessionResponseDTO> updateRequiredConsultantChatValues(
      List<ConsultantSessionResponseDTO> consultantSessionResponseDTOs, Consultant consultant) {
    var joinedRoomIds = matrixRoomMembershipProvider.joinedRoomsForConsultant(consultant);

    consultantSessionResponseDTOs.forEach(
        consultantSessionResponseDTO -> {
          var chat = consultantSessionResponseDTO.getChat();
          chat.setSubscribed(joinedRoomIds.contains(chat.getMatrixRoomId()));
          // messagesRead is deprecated in the API spec: always true, read state is derived
          // client-side from the Matrix room (ORISO-Frontend#1147). Kept for compatibility.
          chat.setMessagesRead(true);
          if (chat.getStartDateWithTime() != null) {
            consultantSessionResponseDTO.setLatestMessage(
                Timestamp.valueOf(chat.getStartDateWithTime()));
          }
        });

    consultantDataFacade.addConsultantDisplayNameToSessionList(consultantSessionResponseDTOs);

    return consultantSessionResponseDTOs;
  }
}
