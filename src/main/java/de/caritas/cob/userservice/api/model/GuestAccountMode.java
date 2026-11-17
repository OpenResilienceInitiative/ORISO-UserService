package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationAccessMode;

/**
 * Which account a person without an ORISO account gets through an invitation (#1662): a direct
 * link, a call share link or a Gesprächskreis invite. The counsellor sets it per link; the platform
 * and the Träger cap which modes may be offered ({@code GuestAccountModePolicy}).
 */
public enum GuestAccountMode {
  /** "Nach dem Gespräch löschen": only a temporary account (ADR-029 §3). */
  TEMPORARY,
  /** "Gast entscheidet": the guest picks a temporary or a permanent account at entry. */
  GUEST_CHOOSES,
  /** "Dauerhaftes Konto": only a permanent account. */
  PERMANENT;

  /**
   * Whether a registration for a temporary ({@code true}) or permanent account may use the link.
   */
  public boolean admits(boolean temporaryAccount) {
    return switch (this) {
      case TEMPORARY -> temporaryAccount;
      case PERMANENT -> !temporaryAccount;
      case GUEST_CHOOSES -> true;
    };
  }

  /** The link may create temporary accounts. The old boolean flag mirrors exactly this. */
  public boolean offersTemporaryAccounts() {
    return this != PERMANENT;
  }

  /** The #2252 access mode with the same meaning, written as a mirror for older releases. */
  public DirectInvitationAccessMode toAccessMode() {
    return switch (this) {
      case TEMPORARY -> DirectInvitationAccessMode.GUESTS_ONLY;
      case GUEST_CHOOSES -> DirectInvitationAccessMode.BOTH;
      case PERMANENT -> DirectInvitationAccessMode.ACCOUNTS_ONLY;
    };
  }

  public static GuestAccountMode of(DirectInvitationAccessMode accessMode) {
    return switch (accessMode) {
      case GUESTS_ONLY -> TEMPORARY;
      case BOTH -> GUEST_CHOOSES;
      case ACCOUNTS_ONLY -> PERMANENT;
    };
  }
}
