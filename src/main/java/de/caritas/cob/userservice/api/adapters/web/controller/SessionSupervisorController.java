package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import io.swagger.annotations.Api;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

  /**
   * Add a supervisor to a session.
   *
   * @param sessionId the session ID
   * @param request the add supervisor request
   * @return the created supervisor
   */
  @PostMapping("/{sessionId}/supervisors")
  public ResponseEntity<SessionSupervisorResponseDTO> addSupervisor(
      @PathVariable @NotNull Long sessionId,
      @Valid @RequestBody AddSupervisorRequestDTO request) {
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
            sessionId, request.getSupervisorConsultantId(), currentConsultant, request.getNotes());

    SessionSupervisorResponseDTO response = mapToDTO(supervisor);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
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

    sessionSupervisorFacade.removeSupervisor(sessionId, supervisorId, currentConsultant);
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

  private SessionSupervisorResponseDTO mapToDTO(SessionSupervisor supervisor) {
    SessionSupervisorResponseDTO dto = new SessionSupervisorResponseDTO();
    dto.setId(supervisor.getId());
    dto.setSessionId(supervisor.getSession().getId());
    dto.setSupervisorConsultantId(
        supervisor.getSupervisorConsultant().getId());
    dto.setSupervisorUsername(supervisor.getSupervisorConsultant().getUsername());
    dto.setAddedByConsultantId(supervisor.getAddedByConsultant().getId());
    dto.setAddedDate(supervisor.getAddedDate());
    dto.setMatrixRoomId(supervisor.getMatrixRoomId());
    dto.setNotes(supervisor.getNotes());
    return dto;
  }

  /** Request DTO for adding a supervisor. */
  public static class AddSupervisorRequestDTO {
    @NotNull private String supervisorConsultantId;
    private String notes;

    public String getSupervisorConsultantId() {
      return supervisorConsultantId;
    }

    public void setSupervisorConsultantId(String supervisorConsultantId) {
      this.supervisorConsultantId = supervisorConsultantId;
    }

    public String getNotes() {
      return notes;
    }

    public void setNotes(String notes) {
      this.notes = notes;
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
  }
}

