package de.caritas.cob.userservice.api.supervision;

/**
 * ADR-008 item 4 (DRAFT): the disclosure shown to the ratsuchende that supervision/peer involvement
 * may occur. Per ADR-008 the exact wording is <b>pending the data-protection officer</b> and is
 * surfaced via the agency's privacy policy + Impressum on chat entry.
 *
 * <p>This constant is a labelled PLACEHOLDER so the plumbing (recording which disclosure version a
 * supervisor add was made under) exists now; swap {@link #PLACEHOLDER_TEXT_KEY} for the approved
 * copy once the DPO signs off. Do NOT present this placeholder to real users as final legal text.
 */
public final class SupervisionDisclosure {

  private SupervisionDisclosure() {}

  /** Bump when the approved disclosure copy changes, so audit rows record which version applied. */
  public static final String VERSION = "draft-0";

  /** i18n key for the placeholder disclosure copy (client resolves it; NOT final legal wording). */
  public static final String PLACEHOLDER_TEXT_KEY = "supervision.disclosure.draftPlaceholder";
}
