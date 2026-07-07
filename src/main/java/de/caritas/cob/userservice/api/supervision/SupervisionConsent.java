package de.caritas.cob.userservice.api.supervision;

/**
 * ADR-008 item 4 (DRAFT): the consent state recorded when a supervisor is attached, mirroring the
 * Case-Handover status vocabulary.
 *
 * <p>DRAFT scope: this records the consent *state*. The client-facing approve/decline workflow (a
 * separate endpoint like Case-Handover's {@code client-consent}) is a documented follow-up that
 * depends on the data-protection officer's disclosure decision.
 */
public enum SupervisionConsent {

  /** The reason does not require the ratsuchende's consent — supervision may proceed. */
  NOT_REQUIRED,

  /** Consent is required and has been requested/disclosed but not yet decided. */
  PENDING,

  /** The ratsuchende (or guardian) approved the supervision. */
  APPROVED,

  /** The ratsuchende (or guardian) declined the supervision. */
  DECLINED;

  /**
   * The initial consent state for a reason: PENDING when consent is required, else NOT_REQUIRED.
   */
  public static SupervisionConsent initialFor(SupervisionReason reason) {
    return reason != null && reason.isClientConsentRequired() ? PENDING : NOT_REQUIRED;
  }
}
