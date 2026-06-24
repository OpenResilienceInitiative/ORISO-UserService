package de.caritas.cob.userservice.api.facade.sessionlist;

import static java.util.Collections.emptyList;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import static java.util.Collections.emptyMap;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import static java.util.Objects.nonNull;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import static java.util.Objects.requireNonNull;
import de.caritas.cob.userservice.api.helper.MatrixIds;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsLastMessageDTO;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsUpdateDTO;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsUpdateDTO;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.container.RocketChatRoomInformation;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import java.util.Date;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import java.util.List;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import java.util.Map;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import java.util.stream.Collectors;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import lombok.extern.slf4j.Slf4j;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import org.springframework.stereotype.Component;
import de.caritas.cob.userservice.api.helper.MatrixIds;

@Slf4j
@Component
public class RocketChatRoomInformationProvider {

  private final RocketChatService rocketChatService;
  private final MatrixSynapseService matrixSynapseService;
  private final ConsultantRepository consultantRepository;

  public RocketChatRoomInformationProvider(
      RocketChatService rocketChatService,
      MatrixSynapseService matrixSynapseService,
      ConsultantRepository consultantRepository) {
    this.rocketChatService = requireNonNull(rocketChatService);
    this.matrixSynapseService = requireNonNull(matrixSynapseService);
    this.consultantRepository = requireNonNull(consultantRepository);
  }

  /**
   * Get room and update information from Rocket.Chat for a user.
   *
   * @param rocketChatCredentials the Rocket.Chat credentials of the user
   * @return an instance of {@link RocketChatRoomInformation}
   */
  public RocketChatRoomInformation retrieveRocketChatInformation(
      RocketChatCredentials rocketChatCredentials) {
    return retrieveRocketChatInformation(rocketChatCredentials, null);
  }

  /**
   * Get room and update information from Rocket.Chat for a user.
   *
   * @param rocketChatCredentials the Rocket.Chat credentials of the user
   * @param consultant the consultant (optional, used for Matrix fallback)
   * @return an instance of {@link RocketChatRoomInformation}
   */
  public RocketChatRoomInformation retrieveRocketChatInformation(
      RocketChatCredentials rocketChatCredentials,
      de.caritas.cob.userservice.api.model.Consultant consultant) {

    Map<String, Boolean> readMessages = emptyMap();
    List<RoomsUpdateDTO> roomsForUpdate = emptyList();
    List<String> userRooms = emptyList();

    if (isMatrixMigrationCredential(rocketChatCredentials)) {
      userRooms =
          consultant != null
              ? getMatrixRoomsForConsultant(consultant)
              : getMatrixRoomsForUser(rocketChatCredentials.getRocketChatUserId());
      return buildRoomInformation(readMessages, roomsForUpdate, userRooms);
    }

    // RocketChat is deprecated - fail gracefully if not available
    try {
      if (nonNull(rocketChatCredentials.getRocketChatUserId())) {
        readMessages = buildMessagesWithReadInfo(rocketChatCredentials);
        roomsForUpdate = rocketChatService.getRoomsOfUser(rocketChatCredentials);
        userRooms = roomsForUpdate.stream().map(RoomsUpdateDTO::getId).collect(Collectors.toList());
      }
    } catch (Exception e) {
      log.warn(
          "RocketChat is not available (expected during Matrix migration): {}", e.getMessage());
      // Try to get Matrix rooms instead using the actual consultant
      if (consultant != null) {
        userRooms = getMatrixRoomsForConsultant(consultant);
      } else {
        userRooms = getMatrixRoomsForUser(rocketChatCredentials.getRocketChatUserId());
      }
    }

    return buildRoomInformation(readMessages, roomsForUpdate, userRooms);
  }

  private RocketChatRoomInformation buildRoomInformation(
      Map<String, Boolean> readMessages,
      List<RoomsUpdateDTO> roomsForUpdate,
      List<String> userRooms) {
    var lastMessagesRoom = getRcRoomLastMessages(roomsForUpdate);
    var groupIdToLastMessageFallbackDate =
        collectFallbackDateOfRoomsWithoutLastMessage(roomsForUpdate);

    return RocketChatRoomInformation.builder()
        .readMessages(readMessages)
        .roomsForUpdate(roomsForUpdate)
        .userRooms(userRooms)
        .lastMessagesRoom(lastMessagesRoom)
        .groupIdToLastMessageFallbackDate(groupIdToLastMessageFallbackDate)
        .build();
  }

  private boolean isMatrixMigrationCredential(RocketChatCredentials rocketChatCredentials) {
    if (rocketChatCredentials == null) {
      return true;
    }

    var userId = rocketChatCredentials.getRocketChatUserId();
    var token = rocketChatCredentials.getRocketChatToken();

    return isBlank(userId)
        || isBlank(token)
        || userId.startsWith("@")
        || userId.startsWith("dummy-")
        || token.startsWith("syt_")
        || token.startsWith("dummy-");
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Get Matrix rooms for a consultant directly.
   *
   * @param consultant the consultant
   * @return list of Matrix room IDs
   */
  private List<String> getMatrixRoomsForConsultant(
      de.caritas.cob.userservice.api.model.Consultant consultant) {
    try {
      String matrixUserId = consultant.getMatrixUserId();

      if (matrixUserId != null) {
        log.info("🔍 Fetching Matrix rooms for consultant: {}", matrixUserId);
        var rooms = matrixSynapseService.getJoinedRoomsForMatrixUser(matrixUserId);
        log.info("✅ Found {} Matrix rooms for consultant {}", rooms.size(), matrixUserId);
        return rooms;
      }

      log.warn("Could not find Matrix user ID for consultant {}", consultant.getId());
      return emptyList();

    } catch (Exception e) {
      log.error(
          "Error getting Matrix rooms for consultant {}: {}", consultant.getId(), e.getMessage());
      return emptyList();
    }
  }

  /**
   * Get Matrix rooms for a user (consultant or user) by their RocketChat ID.
   *
   * @param rcUserId the RocketChat user ID (which is actually the Keycloak user ID)
   * @return list of Matrix room IDs
   */
  private List<String> getMatrixRoomsForUser(String rcUserId) {
    if (rcUserId == null) {
      return emptyList();
    }

    try {
      // Try to find consultant by ID (rcUserId is actually Keycloak ID)
      var consultantOpt = consultantRepository.findById(rcUserId);
      if (consultantOpt.isPresent()) {
        return getMatrixRoomsForConsultant(consultantOpt.get());
      }

      log.warn("Could not find Matrix credentials for user {}", rcUserId);
      return emptyList();

    } catch (Exception e) {
      log.error("Error getting Matrix rooms for user {}: {}", rcUserId, e.getMessage());
      return emptyList();
    }
  }

  /**
   * Extract plain username from Matrix ID (e.g., "@username:server" -> "username").
   *
   * @param matrixUserId the full Matrix user ID
   * @return the plain username
   */
  private String extractMatrixUsername(String matrixUserId) {
    if (matrixUserId == null || !matrixUserId.startsWith("@")) {
      return null;
    }
    return MatrixIds.localpart(matrixUserId);
  }

  private Map<String, Boolean> buildMessagesWithReadInfo(
      RocketChatCredentials rocketChatCredentials) {

    List<SubscriptionsUpdateDTO> subscriptions =
        rocketChatService.getSubscriptionsOfUser(rocketChatCredentials);

    return subscriptions.stream()
        .collect(Collectors.toMap(SubscriptionsUpdateDTO::getRoomId, this::isMessageRead));
  }

  private boolean isMessageRead(SubscriptionsUpdateDTO subscription) {
    return nonNull(subscription.getUnread()) && subscription.getUnread() == 0;
  }

  private Map<String, RoomsLastMessageDTO> getRcRoomLastMessages(
      List<RoomsUpdateDTO> roomsUpdateList) {

    return roomsUpdateList.stream()
        .filter(this::isLastMessageAndTimestampForRocketChatRoomAvailable)
        .collect(Collectors.toMap(RoomsUpdateDTO::getId, RoomsUpdateDTO::getLastMessage));
  }

  private boolean isLastMessageAndTimestampForRocketChatRoomAvailable(RoomsUpdateDTO room) {
    return nonNull(room.getLastMessage()) && nonNull(room.getLastMessage().getTimestamp());
  }

  private Map<String, Date> collectFallbackDateOfRoomsWithoutLastMessage(
      List<RoomsUpdateDTO> roomsForUpdate) {
    return roomsForUpdate.stream()
        .filter(room -> !isLastMessageAndTimestampForRocketChatRoomAvailable(room))
        .filter(room -> nonNull(room.getLastMessageDate()))
        .collect(Collectors.toMap(RoomsUpdateDTO::getId, RoomsUpdateDTO::getLastMessageDate));
  }
}
