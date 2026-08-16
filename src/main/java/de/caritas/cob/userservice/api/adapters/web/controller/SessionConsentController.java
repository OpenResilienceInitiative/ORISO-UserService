package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.session.SessionConsentService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate 2 write path (ADR-022 decision 2): the help-seeker records which legal-text version their
 * room is cleared for.
 *
 * <p>{@code PUT}, not {@code POST}, on purpose — the pointer is overwritten, so the call is
 * idempotent and repeating it is not "consenting twice". Nothing is appended and nothing about the
 * person is stored; the read side travels on {@code SessionDTO}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Api(tags = "session-consent-controller")
@RequestMapping("/users/sessions")
public class SessionConsentController {

  private final @NonNull SessionConsentService sessionConsentService;
  private final @NonNull UserAccountService userAccountService;

  /**
   * Move this room's consent pointer to the given legal-text version.
   *
   * @param sessionId the session ID
   * @param request the legal-text version the help-seeker agreed to
   * @return no content
   */
  @PutMapping("/{sessionId}/consent")
  public ResponseEntity<Void> recordConsent(
      @PathVariable @NotNull Long sessionId, @Valid @RequestBody SessionConsentDTO request) {
    var adviceSeeker = userAccountService.retrieveValidatedUser();
    sessionConsentService.recordConsent(sessionId, adviceSeeker, request.getLegalVersionId());
    return ResponseEntity.noContent().build();
  }

  /** Request body of {@link #recordConsent(Long, SessionConsentDTO)}. */
  @Getter
  @Setter
  @NoArgsConstructor
  public static class SessionConsentDTO {

    /**
     * The legal-text version the help-seeker agreed to, as published by ORISO-AgencyService. A
     * public document version — no personal data.
     */
    @NotNull private Long legalVersionId;
  }
}
