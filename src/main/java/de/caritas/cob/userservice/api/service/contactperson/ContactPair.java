package de.caritas.cob.userservice.api.service.contactperson;

import de.caritas.cob.userservice.api.model.Admin;

/**
 * A validated contact person and deputy. A deputy never stands alone: without a valid contact
 * person the pair is {@link #NONE}.
 */
public record ContactPair(Admin contact, Admin deputy) {

  public static final ContactPair NONE = new ContactPair(null, null);

  public boolean isSet() {
    return contact != null;
  }
}
