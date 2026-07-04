package de.caritas.cob.userservice.api.supervision;

import java.util.Arrays;
import java.util.Optional;

/**
 * ADR-008 item 4 (DRAFT): the recorded reason a supervisor is attached to a session, mirroring the
 * Case-Handover {@code CaseHandoverReason} pattern (code + label key + whether the ratsuchende's
 * consent is required).
 *
 * <p>DRAFT — the reason set and their {@code clientConsentRequired} flags are placeholders pending
 * the data-protection officer and Frank's final U25 requirements. They are intentionally
 * conservative: only the two clinically sensitive reasons require consent today.
 */
public enum SupervisionReason {

  /**
   * A same-age Peer (U25) helps because of shared lived experience, coordinated by a Coordinator.
   */
  PEER_SUPPORT("supervision.reason.peerSupport", false),

  /** A clinical/professional supervisor reviews the case to help a stuck counsellor. */
  CLINICAL_OVERSIGHT("supervision.reason.clinicalOversight", true),

  /** U25 safeguarding: four-eyes read-along on a minor's suicide-counselling case. */
  SAFEGUARDING_U25("supervision.reason.safeguardingU25", true),

  /** Training / onboarding of a counsellor under observation. */
  TRAINING("supervision.reason.training", false);

  private final String labelKey;
  private final boolean clientConsentRequired;

  SupervisionReason(String labelKey, boolean clientConsentRequired) {
    this.labelKey = labelKey;
    this.clientConsentRequired = clientConsentRequired;
  }

  /** i18n key for the human-facing label (resolved client-side, like Case-Handover reasons). */
  public String getLabelKey() {
    return labelKey;
  }

  /**
   * Whether the ratsuchende's (or guardian's) consent is required before this supervision starts.
   */
  public boolean isClientConsentRequired() {
    return clientConsentRequired;
  }

  /** Null-safe lookup by code; empty when the code is unknown. */
  public static Optional<SupervisionReason> fromCode(String code) {
    if (code == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(r -> r.name().equalsIgnoreCase(code.trim())).findFirst();
  }
}
