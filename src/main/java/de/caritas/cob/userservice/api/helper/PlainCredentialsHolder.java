package de.caritas.cob.userservice.api.helper;

import lombok.Data;

/**
 * ThreadLocal holder for registration data needed before JSON deserialization transforms it. Matrix
 * account creation needs the plain username for the Matrix localpart; the platform password must
 * not be retained here.
 */
public class PlainCredentialsHolder {

  private static final ThreadLocal<PlainCredentials> credentials = new ThreadLocal<>();

  public static void set(String plainUsername, String plainPassword) {
    credentials.set(new PlainCredentials(plainUsername, plainPassword));
  }

  public static PlainCredentials get() {
    return credentials.get();
  }

  public static void clear() {
    credentials.remove();
  }

  @Data
  public static class PlainCredentials {
    private final String username;
    private final String password;
  }
}
