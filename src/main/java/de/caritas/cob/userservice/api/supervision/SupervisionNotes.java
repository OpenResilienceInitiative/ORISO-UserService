package de.caritas.cob.userservice.api.supervision;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-008 item 4 (DRAFT): structured reason + justification + consent for a supervisor add,
 * serialized into the EXISTING {@code session_supervisor.notes} TEXT column so the draft needs NO
 * schema migration (the deployed clusters run {@code ddl-auto=none} with Liquibase off — a
 * new @Column would 500 supervisor queries at runtime; the ORISO-006 hazard). A productionised
 * version should promote these to dedicated, queryable columns once the Liquibase re-enablement
 * lands.
 *
 * <p>Backward compatible: an old free-text note (pre-A4) decodes as a justification with no reason
 * and NOT_REQUIRED consent.
 */
@Slf4j
public final class SupervisionNotes {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SupervisionNotes() {}

  /** The parsed shape of a structured supervision note. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Payload {
    public String reasonCode;
    public String justification;
    public String consent;

    public Payload() {}

    public Payload(String reasonCode, String justification, String consent) {
      this.reasonCode = reasonCode;
      this.justification = justification;
      this.consent = consent;
    }
  }

  /** Serialize reason + justification + consent to the JSON stored in {@code notes}. */
  public static String encode(
      SupervisionReason reason, String justification, SupervisionConsent consent) {
    Payload payload =
        new Payload(
            reason == null ? null : reason.name(),
            justification,
            consent == null ? null : consent.name());
    try {
      return MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      // Never fail an add over serialization — fall back to the raw justification.
      log.warn("Failed to encode supervision note, storing raw justification", e);
      return justification;
    }
  }

  /**
   * Decode a stored note. JSON → structured payload; a legacy free-text note → a payload whose
   * justification is the raw text, reason null, consent NOT_REQUIRED.
   */
  public static Payload decode(String notes) {
    if (notes == null || notes.isBlank()) {
      return new Payload(null, null, SupervisionConsent.NOT_REQUIRED.name());
    }
    String trimmed = notes.trim();
    if (trimmed.startsWith("{")) {
      try {
        Payload payload = MAPPER.readValue(trimmed, Payload.class);
        if (payload.consent == null) {
          payload.consent = SupervisionConsent.NOT_REQUIRED.name();
        }
        return payload;
      } catch (JsonProcessingException e) {
        log.debug("Supervision note is not valid JSON, treating as legacy free text");
      }
    }
    return new Payload(null, notes, SupervisionConsent.NOT_REQUIRED.name());
  }
}
