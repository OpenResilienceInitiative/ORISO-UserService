package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AccountRoleAddition;
import de.caritas.cob.userservice.api.service.accountinvite.AccountRoleAddition.AddRoleCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountRoleAddition.AddedRole;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Adds a role to an existing counsellor account (ORISO-Admin#1026); removing stays in Users. */
@RestController
@RequiredArgsConstructor
public class ConsultantRoleController {

  private static final String ADMIN_AUTH =
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')";

  private final @NonNull AccountRoleAddition roleAddition;

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/consultants/{consultantId}/roles")
  public ResponseEntity<AddedRoleResponseDTO> addRole(
      @PathVariable String consultantId, @RequestBody(required = false) AddRoleRequestDTO request) {
    AddRoleRequestDTO safe = request == null ? new AddRoleRequestDTO() : request;
    AddedRole added =
        roleAddition.add(consultantId, new AddRoleCommand(roleOf(safe.role), safe.agencyId));
    AddedRoleResponseDTO response = new AddedRoleResponseDTO();
    response.consultantId = added.consultantId();
    response.role = added.role().name();
    response.agencyIds = added.agencyIds();
    return ResponseEntity.ok(response);
  }

  private static AccountInviteTargetRole roleOf(String value) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException("role is required");
    }
    try {
      return AccountInviteTargetRole.valueOf(value.trim());
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException("Unknown role: " + value, exception);
    }
  }

  public static class AddRoleRequestDTO {
    public String role;
    public Long agencyId;
  }

  public static class AddedRoleResponseDTO {
    public String consultantId;
    public String role;

    /** Every agency the account administers now. */
    public List<Long> agencyIds;
  }
}
