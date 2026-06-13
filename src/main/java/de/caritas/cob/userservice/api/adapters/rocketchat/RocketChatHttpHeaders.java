package de.caritas.cob.userservice.api.adapters.rocketchat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Shared HTTP header builder for Rocket.Chat API requests. */
@Component
public class RocketChatHttpHeaders {

  /**
   * Builds standard HTTP headers with Rocket.Chat auth token and user ID from the given
   * credentials.
   *
   * @param credentials the Rocket.Chat credentials containing the auth token and user ID
   * @return standard HTTP headers with content type JSON and auth headers
   */
  public HttpHeaders getStandardHttpHeaders(RocketChatCredentials credentials) {
    var httpHeaders = new HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
    httpHeaders.add("X-Auth-Token", credentials.getRocketChatToken());
    httpHeaders.add("X-User-Id", credentials.getRocketChatUserId());
    return httpHeaders;
  }
}
