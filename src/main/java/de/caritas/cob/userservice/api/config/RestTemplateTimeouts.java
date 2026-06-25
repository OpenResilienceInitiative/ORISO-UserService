package de.caritas.cob.userservice.api.config;

import java.time.Duration;

public final class RestTemplateTimeouts {

  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration MATRIX_LONG_POLL_READ_TIMEOUT = Duration.ofSeconds(42);

  private RestTemplateTimeouts() {}
}
