package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgencyInviteLinkController {

  private final @NonNull AgencyInviteLinkService agencyInviteLinkService;

  @PreAuthorize(
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')")
  @PostMapping("/useradmin/invitelinks")
  public ResponseEntity<AgencyInviteLinkResponseDTO> create(
      @RequestBody CreateInviteLinkRequestDTO request) {
    AgencyInviteLink link =
        agencyInviteLinkService.create(
            request == null ? null : request.getAgencyId(),
            request == null ? null : request.getExpiresInDays());
    return new ResponseEntity<>(AgencyInviteLinkResponseDTO.from(link), HttpStatus.CREATED);
  }

  @PreAuthorize(
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')")
  @GetMapping("/useradmin/invitelinks")
  public ResponseEntity<List<AgencyInviteLinkResponseDTO>> list() {
    List<AgencyInviteLinkResponseDTO> out =
        agencyInviteLinkService.list().stream()
            .map(AgencyInviteLinkResponseDTO::from)
            .collect(Collectors.toList());
    return ResponseEntity.ok(out);
  }

  /**
   * Public — no auth. Redeems the token and returns the agency info the client needs to run the
   * standard asker registration. Path is under {@code /users/} so the existing {@code
   * /service/users/*} ingress covers it without adding a new nginx route.
   */
  @PostMapping("/users/invitelinks/{token}/redeem")
  public ResponseEntity<AgencyInviteLinkService.RedeemResult> redeem(@PathVariable String token) {
    return ResponseEntity.ok(agencyInviteLinkService.redeem(token));
  }

  public static class CreateInviteLinkRequestDTO {
    private Long agencyId;
    private Long expiresInDays;

    public Long getAgencyId() {
      return agencyId;
    }

    public void setAgencyId(Long agencyId) {
      this.agencyId = agencyId;
    }

    public Long getExpiresInDays() {
      return expiresInDays;
    }

    public void setExpiresInDays(Long expiresInDays) {
      this.expiresInDays = expiresInDays;
    }
  }

  public static class AgencyInviteLinkResponseDTO {
    private Long id;
    private String token;
    private Long tenantId;
    private Long agencyId;
    private Integer consultingTypeId;
    private String createdByUserId;
    private String createdByUsername;
    private LocalDateTime createDate;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private Long usedBySessionId;
    private String status;

    public static AgencyInviteLinkResponseDTO from(AgencyInviteLink link) {
      AgencyInviteLinkResponseDTO dto = new AgencyInviteLinkResponseDTO();
      dto.id = link.getId();
      dto.token = link.getToken();
      dto.tenantId = link.getTenantId();
      dto.agencyId = link.getAgencyId();
      dto.consultingTypeId = link.getConsultingTypeId();
      dto.createdByUserId = link.getCreatedByUserId();
      dto.createdByUsername = link.getCreatedByUsername();
      dto.createDate = link.getCreateDate();
      dto.expiresAt = link.getExpiresAt();
      dto.usedAt = link.getUsedAt();
      dto.usedBySessionId = link.getUsedBySessionId();
      dto.status = link.getStatus();
      return dto;
    }

    public Long getId() {
      return id;
    }

    public String getToken() {
      return token;
    }

    public Long getTenantId() {
      return tenantId;
    }

    public Long getAgencyId() {
      return agencyId;
    }

    public Integer getConsultingTypeId() {
      return consultingTypeId;
    }

    public String getCreatedByUserId() {
      return createdByUserId;
    }

    public String getCreatedByUsername() {
      return createdByUsername;
    }

    public LocalDateTime getCreateDate() {
      return createDate;
    }

    public LocalDateTime getExpiresAt() {
      return expiresAt;
    }

    public LocalDateTime getUsedAt() {
      return usedAt;
    }

    public Long getUsedBySessionId() {
      return usedBySessionId;
    }

    public String getStatus() {
      return status;
    }
  }
}
