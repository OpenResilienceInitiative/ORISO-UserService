package de.caritas.cob.userservice.api.adapters.rocketchat.client;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatEndpoints;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.PresenceDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.PresenceListDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.message.MessageResponse;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Client for Rocket.Chat user-presence API operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketChatPresenceClient {

  private final @NonNull RocketChatClient rocketChatClient;
  private final @NonNull RocketChatMapper mapper;
  private final @NonNull RocketChatConfig rocketChatConfig;

  public Set<String> findAllAvailableUserIds() {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_PRESENCE_LIST);

    try {
      var presentList = rocketChatClient.getForEntity(url, PresenceListDTO.class).getBody();
      if (isNull(presentList)) {
        log.warn("Present user search inconclusive");
      } else {
        return mapper.mapAvailableOf(presentList);
      }
    } catch (HttpClientErrorException exception) {
      log.error("Present user search failed.", exception);
    }

    return Set.of();
  }

  public Optional<Boolean> isLoggedIn(String chatUserId) {
    return getUserPresence(chatUserId).flatMap(presenceDTO -> Optional.of(presenceDTO.isPresent()));
  }

  public Optional<Boolean> isAvailable(String chatUserId) {
    return getUserPresence(chatUserId)
        .flatMap(presenceDTO -> Optional.of(presenceDTO.isAvailable()));
  }

  public boolean setUserPresence(String username, String status) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_PRESENCE_SET);
    var userPresence = mapper.setUserPresenceOf(status);

    try {
      var response =
          rocketChatClient.postForEntity(url, username, userPresence, MessageResponse.class);
      return isSuccessful(response);
    } catch (HttpClientErrorException exception) {
      log.error("Setting user presence failed.", exception);
      return false;
    }
  }

  private Optional<PresenceDTO> getUserPresence(String chatUserId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_PRESENCE_GET + chatUserId);

    try {
      var body = rocketChatClient.getForEntity(url, PresenceDTO.class).getBody();
      if (isNull(body)) {
        log.warn("Presence check inconclusive (user \"{}\".)", chatUserId);
      } else {
        return Optional.of(body);
      }
    } catch (HttpClientErrorException exception) {
      log.error("Presence check failed.", exception);
    }

    return Optional.empty();
  }

  private boolean isSuccessful(ResponseEntity<MessageResponse> response) {
    var body = response.getBody();

    return nonNull(body)
        && Boolean.TRUE.equals(body.getSuccess())
        && !body.getMessage().contains("\"error\"");
  }
}
