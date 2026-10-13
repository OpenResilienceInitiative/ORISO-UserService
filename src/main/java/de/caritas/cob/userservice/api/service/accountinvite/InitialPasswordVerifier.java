package de.caritas.cob.userservice.api.service.accountinvite;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Salted, full-input one-way verifier for the administrator-known temporary credential. */
@Component
public class InitialPasswordVerifier {

  // Spring Security's PBKDF2 encoder processes the full UTF-8 input. BCrypt's 72-byte limit
  // would silently allow two different long passwords to compare equal here.
  private final PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  public String encode(String temporaryPassword) {
    if (temporaryPassword == null || temporaryPassword.isBlank()) {
      throw new IllegalArgumentException("Initial credential is missing");
    }
    return encoder.encode(temporaryPassword);
  }

  public boolean matches(String candidate, String verifier) {
    if (candidate == null || verifier == null || verifier.isBlank()) {
      throw new IllegalArgumentException("Initial credential verifier is missing");
    }
    return encoder.matches(candidate, verifier);
  }
}
