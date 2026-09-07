package de.caritas.cob.userservice.api.exception.httpresponses;

import de.caritas.cob.userservice.api.exception.httpresponses.customheader.CustomHttpHeader;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.service.LogService;
import lombok.Getter;
import org.springframework.http.HttpHeaders;

/**
 * 409 — nothing may be written into this counselling room because its advice seeker has not
 * confirmed the data protection notice (ADR-018 §9, ADR-022 decision 2).
 *
 * <h2>Why it is its own type and not a plain {@link ConflictException}</h2>
 *
 * The same flows already answer 409 for "somebody else has already taken this enquiry" ({@code
 * SessionToConsultantVerifier}). A frontend that cannot tell the two apart shows the wrong sentence
 * — a real complaint from the field. This subclass therefore carries the machine-readable {@code
 * X-Reason: DATA_PRIVACY_CONSENT_MISSING} that {@code docs/api-error-contract.md} asks for on a
 * conflict, while remaining a {@code ConflictException} so every existing {@code catch} and every
 * status mapping keeps working. The "already taken" conflict deliberately keeps carrying no reason
 * header, so the absence of one is itself the distinguishing signal.
 */
@Getter
public class ConsentNotRecordedException extends ConflictException {

  private static final long serialVersionUID = 1L;

  private final transient HttpHeaders customHttpHeaders =
      new CustomHttpHeader(HttpStatusExceptionReason.DATA_PRIVACY_CONSENT_MISSING).buildHeader();

  /**
   * @param message diagnostic text for the log — never sent to the caller, so it must still name
   *     nothing about the person behind an anonymous session
   */
  public ConsentNotRecordedException(String message) {
    super(message, LogService::logWarn);
  }
}
