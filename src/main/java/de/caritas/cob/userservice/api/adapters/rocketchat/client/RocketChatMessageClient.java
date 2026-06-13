package de.caritas.cob.userservice.api.adapters.rocketchat.client;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatEndpoints;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatHttpHeaders;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.StandardResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupCleanHistoryDTO;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveSystemMessagesException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Client for Rocket.Chat message-related API operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketChatMessageClient {

  private static final String RC_DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

  private final LocalDateTime localDateTime1900 = LocalDateTime.of(1900, 1, 1, 0, 0);

  private final LocalDateTime localDateTimeFuture = nowInUtc().plusYears(1L);

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull RocketChatCredentialsProvider rcCredentialHelper;
  private final @NonNull RocketChatConfig rocketChatConfig;
  private final @NonNull RocketChatHttpHeaders headersHelper;

  /**
   * Removes all messages from the specified Rocket.Chat group written by the technical user from
   * the last 24 hours (avoiding time zone failures).
   *
   * @param rcGroupId the rocket chat group id
   * @param oldest the oldest message time
   * @param latest the latest message time
   */
  public void removeSystemMessages(String rcGroupId, LocalDateTime oldest, LocalDateTime latest)
      throws RocketChatRemoveSystemMessagesException, RocketChatUserNotInitializedException {
    RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
    this.removeMessages(
        rcGroupId, new String[] {technicalUser.getRocketChatUsername()}, oldest, latest);
  }

  /**
   * Removes all messages (from every user) from a Rocket.Chat group.
   *
   * @param rcGroupId the rocket chat group id
   */
  public void removeAllMessages(String rcGroupId) throws RocketChatRemoveSystemMessagesException {
    removeMessages(rcGroupId, null, localDateTime1900, localDateTimeFuture);
  }

  private void removeMessages(
      String rcGroupId, String[] users, LocalDateTime oldest, LocalDateTime latest)
      throws RocketChatRemoveSystemMessagesException {

    StandardResponseDTO response;
    try {
      RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
      var header = headersHelper.getStandardHttpHeaders(technicalUser);
      var body =
          new GroupCleanHistoryDTO(
              rcGroupId,
              oldest.format(DateTimeFormatter.ofPattern(RC_DATE_TIME_PATTERN)),
              latest.format(DateTimeFormatter.ofPattern(RC_DATE_TIME_PATTERN)),
              (isNotEmpty(users)) ? users : new String[] {});
      HttpEntity<GroupCleanHistoryDTO> request = new HttpEntity<>(body, header);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.ROOM_CLEAN_HISTORY);
      response = restTemplate.postForObject(url, request, StandardResponseDTO.class);

    } catch (Exception ex) {
      log.error("Could not clean history of Rocket.Chat group id {}. Reason: ", rcGroupId, ex);
      throw new RocketChatRemoveSystemMessagesException(
          String.format("Could not clean history of Rocket.Chat group id %s", rcGroupId));
    }

    if (nonNull(response) && !response.isSuccess()) {
      throw new RocketChatRemoveSystemMessagesException(
          String.format("Could not clean history of Rocket.Chat group id %s", rcGroupId));
    }
  }
}
