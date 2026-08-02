package de.caritas.cob.userservice.api.service.support;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role-scoped audit view (ADR-018 §6). The caller never supplies a scope: a Platform Admin sees
 * everything, a Tenant Admin only their tenant, an Agency Admin only the agencies they administer.
 * Filtering happens in the query, so there is no scope id a client could tamper with.
 */
@Service
@RequiredArgsConstructor
public class SupportAccessAuditService {

  private final @NonNull HandshakeAuditEventRepository auditRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @Transactional(readOnly = true)
  public Page<SupportAccessAuditItem> find(Pageable pageable) {
    return scopedPage(pageable).map(SupportAccessAuditItem::of);
  }

  private Page<HandshakeAuditEvent> scopedPage(Pageable pageable) {
    if (authenticatedUser.isPlatformAdmin()) {
      return auditRepository.findAllByOrderByCreateDateDesc(pageable);
    }
    if (authenticatedUser.isTenantSuperAdmin() || authenticatedUser.isSingleTenantAdmin()) {
      var tenantId = TenantContext.getCurrentTenant();
      if (tenantId == null) {
        throw new AccessDeniedException("Tenant scope is required to read the support audit");
      }
      return auditRepository.findAllByTenantIdOrderByCreateDateDesc(tenantId, pageable);
    }
    if (authenticatedUser.isAgencySuperAdmin() || authenticatedUser.isRestrictedAgencyAdmin()) {
      var agencyIds =
          adminAgencyRepository.findByAdminId(authenticatedUser.getUserId()).stream()
              .map(adminAgency -> adminAgency.getAgencyId())
              .toList();
      if (agencyIds.isEmpty()) {
        return Page.empty(pageable);
      }
      return auditRepository.findAllByAgencyIdInOrderByCreateDateDesc(agencyIds, pageable);
    }
    throw new AccessDeniedException("This role may not read the support audit");
  }

  /**
   * Deliberately IDs, timestamps and outcomes only — no message content, no secrets, no Matrix
   * tokens ever reach the audit surface.
   */
  @Getter
  public static class SupportAccessAuditItem {
    private Long id;
    private String handshakeId;
    private String purpose;
    private String event;
    private String actorId;
    private String counterpartId;
    private Long tenantId;
    private Long agencyId;
    private LocalDateTime createDate;

    static SupportAccessAuditItem of(HandshakeAuditEvent entity) {
      var item = new SupportAccessAuditItem();
      item.id = entity.getId();
      item.handshakeId = entity.getHandshakeId();
      item.purpose = entity.getPurpose();
      item.event = entity.getEvent();
      item.actorId = entity.getActorId();
      item.counterpartId = entity.getCounterpartId();
      item.tenantId = entity.getTenantId();
      item.agencyId = entity.getAgencyId();
      item.createDate = entity.getCreateDate();
      return item;
    }
  }

  /** Convenience for tests and callers that only need the list. */
  public List<SupportAccessAuditItem> findList(Pageable pageable) {
    return find(pageable).getContent();
  }
}
