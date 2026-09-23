package de.caritas.cob.userservice.api.service.email.sender;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * The organisation a mail footer names as its sender: name, postal address, contact line.
 *
 * <p>A blank value is stored as {@code null}, so "not entered" has exactly one representation and
 * never overrides anything.
 */
public record SenderOrganisation(String name, String address, String contactLine) {

  public static final SenderOrganisation NONE = new SenderOrganisation(null, null, null);

  public SenderOrganisation {
    name = trimToNull(name);
    address = trimToNull(address);
    contactLine = trimToNull(contactLine);
  }

  /** Field by field: every value {@code override} has wins, every other one stays. */
  public SenderOrganisation overriddenBy(SenderOrganisation override) {
    return new SenderOrganisation(
        override.name() != null ? override.name() : name,
        override.address() != null ? override.address() : address,
        override.contactLine() != null ? override.contactLine() : contactLine);
  }

  public boolean isEmpty() {
    return name == null && address == null && contactLine == null;
  }

  private static String trimToNull(String value) {
    return isBlank(value) ? null : value.trim();
  }
}
