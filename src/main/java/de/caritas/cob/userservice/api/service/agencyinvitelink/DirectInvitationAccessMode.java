package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** Who may enter through a direct invitation (ORISO-Frontend#2252). */
public enum DirectInvitationAccessMode {
  /** Only people with a temporary account (guests). */
  GUESTS_ONLY,
  /** Only people with a permanent account. */
  ACCOUNTS_ONLY,
  /** The person chooses. */
  BOTH;

  public boolean admits(boolean temporaryAccount) {
    return switch (this) {
      case GUESTS_ONLY -> temporaryAccount;
      case ACCOUNTS_ONLY -> !temporaryAccount;
      case BOTH -> true;
    };
  }

  /** The link creates temporary accounts unless it is for accounts only. */
  public boolean createsTemporaryAccounts() {
    return this != ACCOUNTS_ONLY;
  }
}
