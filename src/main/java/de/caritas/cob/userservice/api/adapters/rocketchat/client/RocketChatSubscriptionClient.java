package de.caritas.cob.userservice.api.adapters.rocketchat.client;

import static java.util.Arrays.asList;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatEndpoints;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatHttpHeaders;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.StandardResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsUpdateDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.RocketChatUnauthorizedException;
import de.caritas.cob.userservice.api.service.LogService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/** Client for Rocket.Chat subscription-related API operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketChatSubscriptionClient {

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull RocketChatCredentialsProvider rcCredentialHelper;
  private final @NonNull RocketChatConfig rocketChatConfig;
  private final @NonNull RocketChatHttpHeaders headersHelper;
  private final @NonNull RocketChatClient rocketChatClient;
  private final @NonNull RocketChatMapper mapper;

  /**
   * Returns the subscriptions for the given user id.
   *
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return the subscriptions of the user
   */
  public List<SubscriptionsUpdateDTO> getSubscriptionsOfUser(
      RocketChatCredentials rocketChatCredentials) {

    ResponseEntity<SubscriptionsGetDTO> response;

    try {
      var header = headersHelper.getStandardHttpHeaders(rocketChatCredentials);
      HttpEntity<Void> request = new HttpEntity<>(header);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.SUBSCRIPTION_GET);
      response = restTemplate.exchange(url, HttpMethod.GET, request, SubscriptionsGetDTO.class);

    } catch (HttpStatusCodeException ex) {
      if (ex.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
        throw new RocketChatUnauthorizedException(rocketChatCredentials.getRocketChatUserId(), ex);
      }
      throw new InternalServerErrorException(ex.getMessage(), LogService::logRocketChatError);
    }

    if (response.getStatusCode() == HttpStatus.OK && nonNull(response.getBody())) {
      return asList(response.getBody().getUpdate());
    } else {
      var error = "Could not get Rocket.Chat subscriptions for user id %s";
      throw new InternalServerErrorException(error, LogService::logRocketChatError);
    }
  }

  public Optional<List<Map<String, String>>> findAllChats(String chatUserId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.SUBSCRIPTION_GET);

    try {
      var response = rocketChatClient.getForEntity(url, chatUserId, SubscriptionsGetDTO.class);
      return mapper.mapOfSubscriptionsResponse(response);
    } catch (HttpClientErrorException exception) {
      log.error("Subscriptions Get failed.", exception);
      return Optional.empty();
    }
  }

  public boolean updateChatE2eKey(String chatUserId, String roomId, String key) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_KEY_UPDATE);
    var updateUser = mapper.updateGroupKeyOf(chatUserId, roomId, key);

    try {
      var response =
          rocketChatClient.postForEntity(url, chatUserId, updateUser, StandardResponseDTO.class);
      return response.getStatusCode().is2xxSuccessful();
    } catch (HttpClientErrorException exception) {
      log.error("Updating E2E group key failed.", exception);
      return false;
    }
  }
}
