package de.caritas.cob.userservice.api.identity;

public record IdentityOtpCredential(
    Boolean setup, String secret, String secretQrCode, IdentityOtpType type) {

  public static IdentityOtpCredential empty() {
    return new IdentityOtpCredential(null, null, null, null);
  }
}
