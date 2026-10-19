package de.caritas.cob.userservice.api.workflow.accountinactivity;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Media revocation is an external effect: never mark it complete without a confirmed response. */
@Component
public class AccountInactivityMediaClient {
  private final RestTemplate http;
  private final boolean enabled;
  private final String baseUrl;
  private final String token;

  public AccountInactivityMediaClient(
      RestTemplate http,
      @Value("${matrixrtc.lifecycle.enabled:false}") boolean enabled,
      @Value("${matrixrtc.lifecycle.base-url:}") String baseUrl,
      @Value("${matrixrtc.lifecycle.token:}") String token) {
    this.http = http;
    this.enabled = enabled;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.token = token;
  }

  public void revoke(List<String> matrixIds) {
    confirm("revoke", matrixIds);
  }

  public void forget(List<String> matrixIds) {
    confirm("forget", matrixIds);
  }

  public void restore(List<String> matrixIds) {
    confirm("restore", matrixIds);
  }

  private void confirm(String action, List<String> matrixIds) {
    if (matrixIds.isEmpty()) return;
    if (!enabled || baseUrl.isBlank() || token.length() < 32) throw unconfirmed();
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      var response =
          http.exchange(
              baseUrl + "/internal/lifecycle/" + action,
              HttpMethod.POST,
              new HttpEntity<>(Map.of("matrixUserIds", matrixIds), headers),
              Void.class);
      if (response.getStatusCode().value() != 204) throw unconfirmed();
    } catch (RuntimeException failure) {
      // Do not persist response bodies, identifiers, authorization headers or transport URLs.
      throw unconfirmed();
    }
  }

  private AccountInactivityEffectException unconfirmed() {
    return new AccountInactivityEffectException(
        AccountInactivityEffectException.Target.MEDIA,
        AccountInactivityEffectException.Code.UNCONFIRMED);
  }
}
