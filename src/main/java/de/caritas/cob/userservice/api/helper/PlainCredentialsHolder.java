package de.caritas.cob.userservice.api.helper;

import lombok.Data;

/**
 * ThreadLocal holder for plain (unencrypted) credentials during registration. This is used to
 * capture the plain username/password before JSON deserialization encrypts them. Required for
 * Matrix user creation which needs plain credentials.
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
