package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages one-time invite links that auto-register an anonymous user for a specific agency. Admin
 * scope is enforced on create/list: super admin (tenant-super) sees all tenants; tenant admin is
 * pinned to their tenant; restricted agency admin is pinned to the agencies listed in their
 * admin_agency rows.
 */
@Service
@RequiredArgsConstructor
public class AgencyInviteLinkService {

  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String STATUS_USED = "USED";
  private static final String STATUS_EXPIRED = "EXPIRED";
  private static final int TOKEN_BYTES = 24;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final @NonNull AgencyInviteLinkRepository repository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  public AgencyInviteLink create(Long agencyId, Long expiresInDays) {
    if (agencyId == null) {
      throw new BadRequestException("agencyId is required");
    }
    var agency = agencyService.getAgencyWithoutCaching(agencyId);
    if (agency == null) {
      throw new NotFoundException("Agency %s not found", agencyId);
    }
    Long agencyTenantId = agency.getTenantId();
    assertCallerMayManageAgency(agencyId, agencyTenantId);

    LocalDateTime now = LocalDateTime.now();
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token(generateToken())
            .tenantId(agencyTenantId)
            .agencyId(agencyId)
            .consultingTypeId(agency.getConsultingType())
            .createdByUserId(authenticatedUser.getUserId())
            .createdByUsername(authenticatedUser.getUsername())
            .createDate(now)
            .expiresAt(expiresInDays != null && expiresInDays > 0 ? now.plusDays(expiresInDays) : null)
            .status(STATUS_ACTIVE)
            .build();
    return repository.save(link);
  }

  public List<AgencyInviteLink> list() {
    List<AgencyInviteLink> results;
    if (authenticatedUser.isTenantSuperAdmin()) {
      results = (List<AgencyInviteLink>) repository.findAllByOrderByCreateDateDesc();
    } else if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      List<Long> agencyIds = getAdminAgencyIds();
      if (agencyIds.isEmpty()) {
        return List.of();
      }
      results = repository.findAllByAgencyIdInOrderByCreateDateDesc(agencyIds);
    } else {
      Long tenantId = resolveTenantIdFromUser();
      if (tenantId == null) {
        return List.of();
      }
      results = repository.findAllByTenantIdOrderByCreateDateDesc(tenantId);
    }
    return results.stream().map(this::autoExpireIfNeeded).sorted(Comparator.comparing(AgencyInviteLink::getCreateDate).reversed()).collect(Collectors.toList());
  }

  /**
   * Redeem the token — validate it, mark USED, return agency info the frontend needs to run the
   * standard asker registration. Client is responsible for registration + auto-login; this keeps
   * the redeem endpoint authentication-free and avoids duplicating the anonymous-user creation
   * path.
   */
  @Transactional
  public RedeemResult redeem(String token) {
    AgencyInviteLink link =
        repository
            .findByToken(token)
            .orElseThrow(() -> new NotFoundException("Invite link not found"));

    if (STATUS_USED.equals(link.getStatus())) {
      throw new BadRequestException("Invite link already used");
    }
    if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      link.setStatus(STATUS_EXPIRED);
      repository.save(link);
      throw new BadRequestException("Invite link expired");
    }
    if (!STATUS_ACTIVE.equals(link.getStatus())) {
      throw new BadRequestException("Invite link is not active");
    }

    link.setStatus(STATUS_USED);
    link.setUsedAt(LocalDateTime.now());
    repository.save(link);

    return new RedeemResult(link.getTenantId(), link.getAgencyId(), link.getConsultingTypeId());
  }

  /** Plain carrier for the redeem response. */
  public static class RedeemResult {
    private final Long tenantId;
    private final Long agencyId;
    private final Integer consultingTypeId;

    public RedeemResult(Long tenantId, Long agencyId, Integer consultingTypeId) {
      this.tenantId = tenantId;
      this.agencyId = agencyId;
      this.consultingTypeId = consultingTypeId;
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
  }

  private AgencyInviteLink autoExpireIfNeeded(AgencyInviteLink link) {
    if (STATUS_ACTIVE.equals(link.getStatus())
        && link.getExpiresAt() != null
        && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      link.setStatus(STATUS_EXPIRED);
      repository.save(link);
    }
    return link;
  }

  private void assertCallerMayManageAgency(Long agencyId, Long agencyTenantId) {
    if (authenticatedUser.isTenantSuperAdmin()) {
      return;
    }
    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      if (!getAdminAgencyIds().contains(agencyId)) {
        throw new ForbiddenException(
            "Agency admin may only create invite links for their own agencies");
      }
      return;
    }
    Long callerTenantId = resolveTenantIdFromUser();
    if (callerTenantId == null || !callerTenantId.equals(agencyTenantId)) {
      throw new ForbiddenException("Agency is outside caller's tenant");
    }
  }

  private List<Long> getAdminAgencyIds() {
    List<AdminAgency> rows = adminAgencyRepository.findByAdminId(authenticatedUser.getUserId());
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream().map(AdminAgency::getAgencyId).collect(Collectors.toList());
  }

  private Long resolveTenantIdFromUser() {
    try {
      Object tenantClaim =
          de.caritas.cob.userservice.api.tenant.TenantContext.getCurrentTenant();
      if (tenantClaim == null) {
        return null;
      }
      return Long.valueOf(tenantClaim.toString());
    } catch (Exception ex) {
      return null;
    }
  }

  private String generateToken() {
    byte[] buf = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }
}
