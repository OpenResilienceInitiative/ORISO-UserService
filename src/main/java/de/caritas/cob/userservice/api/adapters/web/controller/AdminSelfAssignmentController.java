package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentResult;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentRole;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignments;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-assignment of the calling admin (ORISO-Admin#1026, slice 3): "assign me as agency admin /
 * counsellor of this agency". Not an e-mail invite — the caller's existing account is assigned.
 *
 * <ul>
 *   <li>{@code POST /useradmin/self-assignments} {@code {role: COUNSELLOR|AGENCY_ADMIN, agencyId,
 *       topicIds?}} → 201 {@code {role, agencyId, userId, consultantIdentityCreated}}; 400 invalid
 *       input, 403 outside the caller's scope, 404 unknown or deleted agency, 409 with {@code
 *       X-Reason: SELF_ASSIGNMENT_ALREADY_EXISTS} (or {@code CONSULTANT_IDENTITY_ALREADY_GRANTED})
 *       when the caller already holds the role there.
 *   <li>{@code GET /useradmin/self-assignments} → 200 {@code {agencyAdminAgencyIds,
 *       counsellorAgencyIds}} of the caller.
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class AdminSelfAssignmentController {

  private static final String ADMIN_AUTH =
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')";

  private final @NonNull AdminSelfAssignmentService selfAssignmentService;

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping({"/useradmin/self-assignments", "/service/useradmin/self-assignments"})
  public ResponseEntity<SelfAssignmentResponseDTO> assign(
      @RequestBody(required = false) SelfAssignmentRequestDTO request) {
    SelfAssignmentRequestDTO safe = request == null ? new SelfAssignmentRequestDTO() : request;
    SelfAssignmentResult result =
        selfAssignmentService.assign(
            new SelfAssignmentCommand(parseRole(safe.role), safe.agencyId, safe.topicIds));
    return new ResponseEntity<>(SelfAssignmentResponseDTO.from(result), HttpStatus.CREATED);
  }

  @PreAuthorize(ADMIN_AUTH)
  @GetMapping({"/useradmin/self-assignments", "/service/useradmin/self-assignments"})
  public ResponseEntity<SelfAssignmentsResponseDTO> current() {
    SelfAssignments current = selfAssignmentService.current();
    SelfAssignmentsResponseDTO dto = new SelfAssignmentsResponseDTO();
    dto.agencyAdminAgencyIds = current.agencyAdminAgencyIds();
    dto.counsellorAgencyIds = current.counsellorAgencyIds();
    return ResponseEntity.ok(dto);
  }

  private static SelfAssignmentRole parseRole(String role) {
    if (role == null || role.isBlank()) {
      throw new BadRequestException("role is required");
    }
    try {
      return SelfAssignmentRole.valueOf(role.trim());
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException("Unknown role: " + role, exception);
    }
  }

  public static class SelfAssignmentRequestDTO {
    /** COUNSELLOR or AGENCY_ADMIN. */
    public String role;

    public Long agencyId;

    /** COUNSELLOR only; omitted → the agency's topic when it offers exactly one. */
    public List<Long> topicIds;
  }

  public static class SelfAssignmentResponseDTO {
    public String role;
    public Long agencyId;
    public String userId;

    /** True when the caller got a new consultant identity; false when one was extended. */
    public boolean consultantIdentityCreated;

    static SelfAssignmentResponseDTO from(SelfAssignmentResult result) {
      SelfAssignmentResponseDTO dto = new SelfAssignmentResponseDTO();
      dto.role = result.role().name();
      dto.agencyId = result.agencyId();
      dto.userId = result.userId();
      dto.consultantIdentityCreated = result.consultantIdentityCreated();
      return dto;
    }
  }

  public static class SelfAssignmentsResponseDTO {
    public List<Long> agencyAdminAgencyIds;
    public List<Long> counsellorAgencyIds;
  }
}
