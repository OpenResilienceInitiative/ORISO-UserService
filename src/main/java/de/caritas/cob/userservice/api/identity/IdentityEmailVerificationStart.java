package de.caritas.cob.userservice.api.identity;

public record IdentityEmailVerificationStart(boolean started, String failureMessage) {

  public static IdentityEmailVerificationStart success() {
    return new IdentityEmailVerificationStart(true, null);
  }

  public static IdentityEmailVerificationStart failure(String failureMessage) {
    return new IdentityEmailVerificationStart(false, failureMessage);
  }
}
