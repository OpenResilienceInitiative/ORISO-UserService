package de.caritas.cob.userservice.api.service.httpheader;

import java.util.Optional;

/** Request-thread override for authenticated service-to-service calls in public workflows. */
public final class TechnicalAccessTokenContext {

  private static final ThreadLocal<String> ACCESS_TOKEN = new ThreadLocal<>();

  private TechnicalAccessTokenContext() {}

  public static void set(String accessToken) {
    ACCESS_TOKEN.set(accessToken);
  }

  public static Optional<String> get() {
    return Optional.ofNullable(ACCESS_TOKEN.get());
  }

  public static void clear() {
    ACCESS_TOKEN.remove();
  }
}
