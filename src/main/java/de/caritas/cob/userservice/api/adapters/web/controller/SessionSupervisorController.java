package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.supervision.SupervisionNotes;
import de.caritas.cob.userservice.api.supervision.SupervisionReason;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for managing session supervisors. Allows consultants to add/remove supervisors to
 * sessions for observation purposes.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Api(tags = "session-supervisor-controller")
@RequestMapping("/users/sessions")
public class SessionSupervisorController {

  private final @NonNull SessionSupervisorFacade sessionSupervisorFacade;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull UserAccountService userAccountService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull SupervisorAddedEmailNotificationService
      supervisorAddedEmailNotificationService;
  private final @NonNull SessionService sessionService;

  /**
   * Add a supervisor to a session.
   *
   * @param sessionId the session ID
   * @param request the add supervisor request
   * @return the created supervisor
   */
  @PostMapping("/{sessionId}/supervisors")
  public ResponseEntity<SessionSupervisorResponseDTO> addSupervisor(
      @PathVariable @NotNull Long sessionId, @Valid @RequestBody AddSupervisorRequestDTO request) {
    log.info(
        "Add supervisor request: sessionId={}, supervisorConsultantId={}",
        sessionId,
        request.getSupervisorConsultantId());
    Consultant currentConsultant = userAccountService.retrieveValidatedConsultant();
    if (currentConsultant == null) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    SessionSupervisor supervisor =
        sessionSupervisorFacade.addSupervisor(
            sessionId,
            request.getSupervisorConsultantId(),
            currentConsultant,
            request.getReasonCode(),
            request.getNotes());
    // Only notify "supervisor added" when the supervisor actually became active. For a
    // consent-gated add (ADR-008 item 4) the supervisor is PENDING with no room access yet — those
    // notifications fire later, when the client approves consent.
    if (Boolean.TRUE.equals(supervisor.getIsActive())) {
      fireSupervisorActivatedNotifications(supervisor);
    }

    SessionSupervisorResponseDTO response = mapToDTO(supervisor);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * The ratsuchende approves or declines a PENDING supervision-consent request (ADR-008 item 4).
   * Only the session's own client may decide. On approval the supervisor is provisioned into the
   * rooms and the "supervisor added" notifications fire (deferred from add time).
   *
   * @param sessionId the session ID
   * @param supervisorId the pending supervisor row id
   * @param request the decision (APPROVED / DECLINED)
   * @return the updated supervisor
   */
  @PostMapping("/{sessionId}/supervisors/{supervisorId}/consent")
  public ResponseEntity<SessionSupervisorResponseDTO> decideSupervisionConsent(
      @PathVariable @NotNull Long sessionId,
      @PathVariable @NotNull Long supervisorId,
      @Valid @RequestBody SupervisionConsentDecisionDTO request) {
    User client = userAccountService.retrieveValidatedUser();
    if (client == null || !isClientOfSession(client, sessionId)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    boolean approve;
    if ("APPROVED".equalsIgnoreCase(request.getDecision())) {
      approve = true;
    } else if ("DECLINED".equalsIgnoreCase(request.getDecision())) {
      approve = false;
    } else {
      return ResponseEntity.badRequest().build();
    }

    SessionSupervisor updated =
        sessionSupervisorFacade.decideSupervisionConsent(sessionId, supervisorId, approve);
    if (approve && Boolean.TRUE.equals(updated.getIsActive())) {
      fireSupervisorActivatedNotifications(updated);
    }
    return ResponseEntity.ok(mapToDTO(updated));
  }

  /**
   * List the supervision requests for a session that are still awaiting the client's consent
   * (ADR-008 item 4). Only the session's own client may read this.
   *
   * @param sessionId the session ID
   * @return the pending-consent supervisor requests
   */
  @GetMapping("/{sessionId}/supervisors/pending-consent")
  public ResponseEntity<List<SessionSupervisorResponseDTO>> getPendingConsentSupervisors(
      @PathVariable @NotNull Long sessionId) {
    User client = userAccountService.retrieveValidatedUser();
    if (client == null || !isClientOfSession(client, sessionId)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    List<SessionSupervisorResponseDTO> response =
        sessionSupervisorFacade.getPendingConsentSupervisors(sessionId).stream()
            .map(this::mapToDTO)
            .collect(Collectors.toList());
    return ResponseEntity.ok(response);
  }

  /** True when the authenticated user is the ratsuchende (asker) that owns the session. */
  private boolean isClientOfSession(User client, Long sessionId) {
    return sessionService
        .getSession(sessionId)
        .map(session -> session.getUser())
        .map(User::getUserId)
        .filter(ownerId -> ownerId.equals(client.getUserId()))
        .isPresent();
  }

  /** Fire the "supervisor added / assigned" notifications for a now-active supervisor. */
  private void fireSupervisorActivatedNotifications(SessionSupervisor supervisor) {
    String accessToken = authenticatedUser.getAccessToken();
    String supervisorDisplayName =
        supervisor.getSupervisorConsultant().getDisplayName() != null
            ? supervisor.getSupervisorConsultant().getDisplayName()
            : supervisor.getSupervisorConsultant().getFullName();

    if (supervisor.getSession() != null
        && supervisor.getSession().getUser() != null
        && supervisor.getSession().getUser().getUserId() != null) {
      eventNotificationService.createSupervisorAddedNotification(
          supervisor.getSession(),
          supervisor.getSession().getUser().getUserId(),
          supervisorDisplayName);
    }

    supervisorAddedEmailNotificationService.notifySupervisorAdded(
        supervisor.getSession() != null ? supervisor.getSession().getUser() : null,
        supervisor.getSupervisorConsultant(),
        supervisorDisplayName,
        supervisor.getSession() != null ? supervisor.getSession().getId() : null,
        TenantContext.getCurrentTenantData(),
        accessToken);

    if (supervisor.getSession() != null
        && supervisor.getSupervisorConsultant() != null
        && supervisor.getSupervisorConsultant().getId() != null) {
      eventNotificationService.createSupervisorAssignedNotification(
          supervisor.getSession(), supervisor.getSupervisorConsultant().getId());
    }
  }

  /**
   * Remove a supervisor from a session.
   *
   * @param sessionId the session ID
   * @param supervisorId the supervisor ID
   * @return no content
   */
  @DeleteMapping("/{sessionId}/supervisors/{supervisorId}")
  public ResponseEntity<Void> removeSupervisor(
      @PathVariable @NotNull Long sessionId, @PathVariable @NotNull Long supervisorId) {
    log.info("Remove supervisor request: sessionId={}, supervisorId={}", sessionId, supervisorId);
    Consultant currentConsultant = userAccountService.retrieveValidatedConsultant();
    if (currentConsultant == null) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    String accessToken = authenticatedUser.getAccessToken();
    Optional<SessionSupervisor> supervisorToRemove =
        sessionSupervisorFacade.getSupervisors(sessionId).stream()
            .filter(item -> item.getId().equals(supervisorId))
            .findFirst();
    sessionSupervisorFacade.removeSupervisor(sessionId, supervisorId, currentConsultant);
    sessionService
        .getSession(sessionId)
        .ifPresent(
            session -> {
              String supervisorDisplayName =
                  supervisorToRemove
                      .map(SessionSupervisor::getSupervisorConsultant)
                      .map(
                          consultant ->
                              consultant.getDisplayName() != null
                                  ? consultant.getDisplayName()
                                  : consultant.getFullName())
                      .orElse("A supervisor");
              if (session.getUser() != null && session.getUser().getUserId() != null) {
                eventNotificationService.createSupervisorRemovedNotification(
                    session, session.getUser().getUserId(), supervisorDisplayName);
              }
              supervisorToRemove.ifPresent(
                  item ->
                      supervisorAddedEmailNotificationService.notifySupervisorRemoved(
                          session.getUser(),
                          item.getSupervisorConsultant(),
                          supervisorDisplayName,
                          session.getId(),
                          TenantContext.getCurrentTenantData(),
                          accessToken));
            });
    return ResponseEntity.noContent().build();
  }

  /**
   * Get all active supervisors for a session.
   *
   * @param sessionId the session ID
   * @return list of supervisors
   */
  @GetMapping("/{sessionId}/supervisors")
  public ResponseEntity<List<SessionSupervisorResponseDTO>> getSupervisors(
      @PathVariable @NotNull Long sessionId) {
    log.info("Get supervisors request: sessionId={}", sessionId);
    List<SessionSupervisor> supervisors = sessionSupervisorFacade.getSupervisors(sessionId);
    List<SessionSupervisorResponseDTO> response =
        supervisors.stream().map(this::mapToDTO).collect(Collectors.toList());
    return ResponseEntity.ok(response);
  }

  /**
   * List the ADR-008 supervision reasons a client may choose from, mirroring how case-handover
   * serves its reasons. Static enum data — no session context needed.
   *
   * @return the reason list as {@link SupervisionReasonDTO}s
   */
  @GetMapping("/supervision/reasons")
  public ResponseEntity<List<SupervisionReasonDTO>> getSupervisionReasons() {
    List<SupervisionReasonDTO> reasons =
        Arrays.stream(SupervisionReason.values())
            .map(
                reason ->
                    new SupervisionReasonDTO(
                        reason.name(), reason.getLabelKey(), reason.isClientConsentRequired()))
            .collect(Collectors.toList());
    return ResponseEntity.ok(reasons);
  }

  private SessionSupervisorResponseDTO mapToDTO(SessionSupervisor supervisor) {
    SessionSupervisorResponseDTO dto = new SessionSupervisorResponseDTO();
    dto.setId(supervisor.getId());
    dto.setSessionId(supervisor.getSession().getId());
    dto.setSupervisorConsultantId(supervisor.getSupervisorConsultant().getId());
    dto.setSupervisorUsername(supervisor.getSupervisorConsultant().getUsername());
    dto.setAddedByConsultantId(supervisor.getAddedByConsultant().getId());
    dto.setAddedDate(supervisor.getAddedDate());
    dto.setMatrixRoomId(supervisor.getMatrixRoomId());
    // Expose the decoded, human-readable justification as `notes` — never the raw stored value,
    // which is JSON for rows written since ADR-008. Legacy free-text notes decode to themselves
    // (null reason, NOT_REQUIRED consent), so existing clients see unchanged output.
    SupervisionNotes.Payload payload = SupervisionNotes.decode(supervisor.getNotes());
    dto.setNotes(payload.justification);
    dto.setReasonCode(payload.reasonCode);
    dto.setJustification(payload.justification);
    dto.setConsent(payload.consent);
    return dto;
  }

  /** Request DTO for adding a supervisor. */
  public static class AddSupervisorRequestDTO {
    @NotNull private String supervisorConsultantId;

    /**
     * ADR-008 item 4 (DRAFT): optional supervision reason code (e.g. {@code SAFEGUARDING_U25}).
     * Intentionally OPTIONAL — the current supervisor modal does not send one yet, so requiring it
     * would 400 add-supervisor in the running app. Validated by the facade only when present. The
     * productionised version makes this required once the paired FE reason-picker ships.
     */
    private String reasonCode;

    /**
     * The justification for adding the supervisor (free text). This is the human-readable reason;
     * it is required by the facade only on the explicit path (when a {@link #reasonCode} is sent).
     */
    private String notes;

    public String getSupervisorConsultantId() {
      return supervisorConsultantId;
    }

    public void setSupervisorConsultantId(String supervisorConsultantId) {
      this.supervisorConsultantId = supervisorConsultantId;
    }

    public String getReasonCode() {
      return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
      this.reasonCode = reasonCode;
    }

    public String getNotes() {
      return notes;
    }

    public void setNotes(String notes) {
      this.notes = notes;
    }
  }

  /** Request DTO for the client's decision on a pending supervision-consent request. */
  public static class SupervisionConsentDecisionDTO {
    /** The decision: {@code APPROVED} or {@code DECLINED} (case-insensitive). */
    @NotNull private String decision;

    public String getDecision() {
      return decision;
    }

    public void setDecision(String decision) {
      this.decision = decision;
    }
  }

  /** Response DTO for an ADR-008 supervision reason option. */
  public static class SupervisionReasonDTO {
    private String code;
    private String labelKey;
    private boolean clientConsentRequired;

    public SupervisionReasonDTO() {}

    public SupervisionReasonDTO(String code, String labelKey, boolean clientConsentRequired) {
      this.code = code;
      this.labelKey = labelKey;
      this.clientConsentRequired = clientConsentRequired;
    }

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getLabelKey() {
      return labelKey;
    }

    public void setLabelKey(String labelKey) {
      this.labelKey = labelKey;
    }

    public boolean isClientConsentRequired() {
      return clientConsentRequired;
    }

    public void setClientConsentRequired(boolean clientConsentRequired) {
      this.clientConsentRequired = clientConsentRequired;
    }
  }

  /** Response DTO for supervisor information. */
  public static class SessionSupervisorResponseDTO {
    private Long id;
    private Long sessionId;
    private String supervisorConsultantId;
    private String supervisorUsername;
    private String addedByConsultantId;
    private java.time.LocalDateTime addedDate;
    private String matrixRoomId;
    private String notes;
    // ADR-008 item 4 (DRAFT): decoded structured fields from the stored note.
    private String reasonCode;
    private String justification;
    private String consent;

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public Long getSessionId() {
      return sessionId;
    }

    public void setSessionId(Long sessionId) {
      this.sessionId = sessionId;
    }

    public String getSupervisorConsultantId() {
      return supervisorConsultantId;
    }

    public void setSupervisorConsultantId(String supervisorConsultantId) {
      this.supervisorConsultantId = supervisorConsultantId;
    }

    public String getSupervisorUsername() {
      return supervisorUsername;
    }

    public void setSupervisorUsername(String supervisorUsername) {
      this.supervisorUsername = supervisorUsername;
    }

    public String getAddedByConsultantId() {
      return addedByConsultantId;
    }

    public void setAddedByConsultantId(String addedByConsultantId) {
      this.addedByConsultantId = addedByConsultantId;
    }

    public java.time.LocalDateTime getAddedDate() {
      return addedDate;
    }

    public void setAddedDate(java.time.LocalDateTime addedDate) {
      this.addedDate = addedDate;
    }

    public String getMatrixRoomId() {
      return matrixRoomId;
    }

    public void setMatrixRoomId(String matrixRoomId) {
      this.matrixRoomId = matrixRoomId;
    }

    public String getNotes() {
      return notes;
    }

    public void setNotes(String notes) {
      this.notes = notes;
    }

    public String getReasonCode() {
      return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
      this.reasonCode = reasonCode;
    }

    public String getJustification() {
      return justification;
    }

    public void setJustification(String justification) {
      this.justification = justification;
    }

    public String getConsent() {
      return consent;
    }

    public void setConsent(String consent) {
      this.consent = consent;
    }
  }
}
