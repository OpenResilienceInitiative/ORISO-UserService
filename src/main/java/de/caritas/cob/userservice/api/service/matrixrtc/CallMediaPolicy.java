package de.caritas.cob.userservice.api.service.matrixrtc;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the media gate may hand out for one call room.
 *
 * @param denialReason set only for a refusal the client may act on; every other denial stays a bare
 *     {@code false/false}, so the gateway learns nothing more than before
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CallMediaPolicy(boolean audioAllowed, boolean videoAllowed, String denialReason) {

  /** Frank, 17 Nov 2026: the person is no counsellor and the call room holds no counsellor call. */
  public static final String CALL_START_REQUIRES_COUNSELLOR = "call_start_requires_counsellor";

  public CallMediaPolicy(boolean audioAllowed, boolean videoAllowed) {
    this(audioAllowed, videoAllowed, null);
  }

  public static CallMediaPolicy denied() {
    return new CallMediaPolicy(false, false);
  }

  public static CallMediaPolicy refused(String reason) {
    return new CallMediaPolicy(false, false, reason);
  }
}
