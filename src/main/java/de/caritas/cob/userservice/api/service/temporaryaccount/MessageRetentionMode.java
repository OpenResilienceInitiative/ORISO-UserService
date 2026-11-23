package de.caritas.cob.userservice.api.service.temporaryaccount;

/** What happens to the messages of a temporary account when it is deleted (ADR-029 §5). */
public enum MessageRetentionMode {
  /** Deleted together with the account. */
  ALWAYS_TEMPORARY,
  /** The guest is asked once per conversation; undecided counts as deleted. */
  GUEST_DECIDES,
  /** Kept; the sender then shows as "Gast (gelöscht)". */
  ALWAYS_PERSISTENT
}
