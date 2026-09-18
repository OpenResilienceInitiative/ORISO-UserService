package de.caritas.cob.userservice.api.adapters.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.port.out.GuestChatIdentity;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Guest provisioning deliberately cannot reach the legacy account-reactivation path. */
@Component
@RequiredArgsConstructor
public class GuestMatrixIdentityClient implements GuestChatIdentity {
  private static final String REGISTER = "/_synapse/admin/v1/register";
  private static final String LOGIN = "/_matrix/client/v3/login";
  private static final String LOGOUT = "/_matrix/client/v3/logout";
  private final MatrixConfig config;
  private final RestTemplate rest;

  @Override
  public String ensureOwned(String username, String password) {
    return verifyOwnership(username, password)
        ? "@" + username + ":" + config.getServerName()
        : createOnly(username, password);
  }

  @Override
  public String createOnly(String username, String password) {
    validateCredentials(username, password);
    try {
      var nonceResponse = rest.getForEntity(config.getApiUrl(REGISTER), Map.class);
      var nonceBody = nonceResponse.getBody();
      if (!nonceResponse.getStatusCode().is2xxSuccessful()
          || nonceBody == null
          || !(nonceBody.get("nonce") instanceof String nonce)
          || nonce.isBlank()) {
        throw unavailable();
      }
      Map<String, Object> request =
          Map.of(
              "username",
              username,
              "password",
              password,
              "displayname",
              username,
              "admin",
              false,
              "inhibit_login",
              true,
              "nonce",
              nonce,
              "mac",
              registrationMac(nonce, username, password));
      org.springframework.http.ResponseEntity<Map> response;
      try {
        response = rest.postForEntity(config.getApiUrl(REGISTER), request, Map.class);
      } catch (HttpClientErrorException exception) {
        if (exception.getStatusCode() == HttpStatus.BAD_REQUEST
            && "M_USER_IN_USE".equals(errorCode(exception))) {
          throw new ConflictException("Guest chat username is occupied");
        }
        throw unavailable();
      }
      var body = response.getBody();
      if (body != null && body.get("access_token") instanceof String token && !token.isBlank()) {
        // Shared-secret registration may also issue a login token. Do not leave it behind.
        revoke(token);
      }
      String expectedId = "@" + username + ":" + config.getServerName();
      if (!response.getStatusCode().is2xxSuccessful()
          || body == null
          || !expectedId.equals(body.get("user_id"))) {
        throw unavailable();
      }
      return expectedId;
    } catch (ConflictException | ServiceUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      // Provider response bodies and request exceptions may contain credentials.
      throw unavailable();
    }
  }

  @Override
  public boolean verifyOwnership(String username, String password) {
    validateCredentials(username, password);
    String expectedId = "@" + username + ":" + config.getServerName();
    try {
      Map<String, Object> request =
          Map.of(
              "type",
              "m.login.password",
              "identifier",
              Map.of("type", "m.id.user", "user", expectedId),
              "password",
              password,
              // A fixed dedicated device bounds sessions even when a logout response is lost.
              "device_id",
              "ORISO_GUEST_JOIN_PROOF_V1",
              "refresh_token",
              false);
      org.springframework.http.ResponseEntity<Map> response;
      try {
        response = rest.postForEntity(config.getApiUrl(LOGIN), request, Map.class);
      } catch (HttpClientErrorException exception) {
        String code = errorCode(exception);
        if (exception.getStatusCode().value() == 403 && "M_USER_DEACTIVATED".equals(code)) {
          throw new ForbiddenException("Guest chat identity is no longer active");
        }
        if (exception.getStatusCode().value() == 403 && "M_FORBIDDEN".equals(code)) {
          return false;
        }
        throw unavailable();
      }
      var body = response.getBody();
      if (body == null || !(body.get("access_token") instanceof String token) || token.isBlank()) {
        throw unavailable();
      }
      revoke(token);
      if (!response.getStatusCode().is2xxSuccessful() || !expectedId.equals(body.get("user_id"))) {
        throw unavailable();
      }
      return true;
    } catch (ForbiddenException | ServiceUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private void validateCredentials(String username, String password) {
    if (username == null
        || !username.matches("[a-z0-9_]{3,30}")
        || password == null
        || password.isBlank()) {
      throw new BadRequestException("Invalid guest identity credentials");
    }
  }

  private String registrationMac(String nonce, String username, String password)
      throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(
        new SecretKeySpec(
            config.getRegistrationSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    return HexFormat.of()
        .formatHex(
            mac.doFinal(
                (nonce + "\0" + username + "\0" + password + "\0notadmin")
                    .getBytes(StandardCharsets.UTF_8)));
  }

  private void revoke(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    var response =
        rest.postForEntity(
            config.getApiUrl(LOGOUT), new HttpEntity<>(Map.of(), headers), Void.class);
    if (!response.getStatusCode().is2xxSuccessful()) throw unavailable();
  }

  private String errorCode(HttpClientErrorException exception) {
    try {
      return new ObjectMapper()
          .readTree(exception.getResponseBodyAsString())
          .path("errcode")
          .asText();
    } catch (Exception ignored) {
      return "";
    }
  }

  private ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Guest chat provisioning outcome is unknown");
  }
}
