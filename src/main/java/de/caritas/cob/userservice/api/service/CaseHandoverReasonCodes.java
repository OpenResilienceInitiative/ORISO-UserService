package de.caritas.cob.userservice.api.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Case Handover reason codes. The four neutral codes replace retired codes whose wording read as a
 * health statement about the counsellor (GDPR Art. 9, #1536). TenantService still keys its policies
 * by the retired codes, so both directions of the mapping live here.
 */
public final class CaseHandoverReasonCodes {

  public static final String ADVICE_REQUESTED = "ADVICE_REQUESTED";
  public static final String PLANNED_ABSENCE = "PLANNED_ABSENCE";
  public static final String UNPLANNED_ABSENCE = "UNPLANNED_ABSENCE";
  public static final String ASSIGNMENT_ENDED = "ASSIGNMENT_ENDED";

  private static final Map<String, String> RETIRED_TO_NEUTRAL =
      Map.of(
          "COUNSELLOR_ASKED_FOR_ADVICE", ADVICE_REQUESTED,
          "COUNSELLOR_ON_HOLIDAY", PLANNED_ABSENCE,
          "COUNSELLOR_IS_ILL", UNPLANNED_ABSENCE,
          "COUNSELLOR_LEFT", ASSIGNMENT_ENDED);

  private static final Map<String, String> NEUTRAL_LABELS =
      Map.of(
          ADVICE_REQUESTED, "Advice requested",
          PLANNED_ABSENCE, "Planned absence",
          UNPLANNED_ABSENCE, "Unplanned absence",
          ASSIGNMENT_ENDED, "Assignment ended");

  private CaseHandoverReasonCodes() {}

  /** Upper-cased code; a retired code becomes its neutral successor. */
  public static String canonical(String reasonCode) {
    String code = reasonCode == null ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
    return RETIRED_TO_NEUTRAL.getOrDefault(code, code);
  }

  public static boolean isRetired(String reasonCode) {
    return reasonCode != null
        && RETIRED_TO_NEUTRAL.containsKey(reasonCode.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Label to show for a stored record. Records written under a retired code may carry wording such
   * as "Counsellor is ill"; they get the neutral label instead, the code stays as stored.
   */
  public static String displayLabel(String storedCode, String storedLabel) {
    if (!isRetired(storedCode)) {
      return storedLabel;
    }
    return Optional.ofNullable(NEUTRAL_LABELS.get(canonical(storedCode))).orElse(storedLabel);
  }
}
