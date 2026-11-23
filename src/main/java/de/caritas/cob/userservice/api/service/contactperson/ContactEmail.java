package de.caritas.cob.userservice.api.service.contactperson;

import java.util.Locale;

/** A contact e-mail as stored: trimmed and lower-case; a blank one means "none". */
public final class ContactEmail {

  private ContactEmail() {}

  public static String normalize(String email) {
    return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
