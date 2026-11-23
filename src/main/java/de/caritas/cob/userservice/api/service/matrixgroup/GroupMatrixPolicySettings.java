package de.caritas.cob.userservice.api.service.matrixgroup;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dedicated membership integration, deliberately disabled until its Synapse companion rolls out.
 */
@Component
public class GroupMatrixPolicySettings {
  public static final String TOKEN_HEADER = "X-Oriso-Group-Policy-Token";
  public static final String SCHEMA = "synapse-1.158.0-applied-membership-v1";
  private final boolean enabled;
  private final String token;
  private final URI historyUri;

  public GroupMatrixPolicySettings(
      @Value("${matrix.group.policy.enabled:false}") boolean enabled,
      @Value("${matrix.group.policy.token:}") String token,
      @Value("${matrix.group.participation-history.url:}") String historyUrl) {
    this.enabled = enabled;
    this.token = token;
    if (!enabled) {
      historyUri = null;
      return;
    }
    try {
      if (token == null || !token.matches("[A-Za-z0-9._~+/=-]{32,256}"))
        throw new IllegalArgumentException();
      var uri = URI.create(historyUrl);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !"/oriso-internal/group-participation-history".equals(uri.getRawPath()))
        throw new IllegalArgumentException();
      historyUri = uri;
    } catch (RuntimeException invalid) {
      throw new IllegalStateException(
          "Enabled Matrix group policy requires a dedicated credential and exact history endpoint");
    }
  }

  public boolean enabled() {
    return enabled;
  }

  public URI historyUri() {
    return historyUri;
  }

  public String token() {
    return token;
  }

  public boolean authenticates(String candidate) {
    return enabled
        && candidate != null
        && candidate.length() <= 256
        && MessageDigest.isEqual(token.getBytes(UTF_8), candidate.getBytes(UTF_8));
  }

  public static boolean validRoom(String room) {
    return room != null && room.length() <= 512 && room.matches("![^\\s]+");
  }

  public static boolean validActor(String actor) {
    return actor != null && actor.length() <= 512 && actor.matches("@[^\\s:]+:[^\\s]+");
  }
}
