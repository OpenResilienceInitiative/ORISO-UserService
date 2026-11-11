package de.caritas.cob.userservice.api.helper;

/**
 * The one minimum password length for every path that sets a user-chosen password (registration,
 * invite onboarding, account setup, password reset, password change, admin-set password). Keep in
 * sync with the Keycloak realm password policy and the Admin and Frontend password rules.
 */
public final class PasswordPolicy {

  public static final int MIN_LENGTH = 16;

  private PasswordPolicy() {}

  public static boolean isLongEnough(String password) {
    return password != null && password.length() >= MIN_LENGTH;
  }
}
