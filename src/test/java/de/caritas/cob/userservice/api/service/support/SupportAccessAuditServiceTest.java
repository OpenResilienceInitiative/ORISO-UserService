package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class SupportAccessAuditServiceTest {

  private static final PageRequest PAGE = PageRequest.of(0, 20);

  @InjectMocks private SupportAccessAuditService auditService;

  @Mock private HandshakeAuditEventRepository auditRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private AuthenticatedUser authenticatedUser;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void find_Should_ReturnEverythingForAPlatformAdmin() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(auditRepository.findAllByOrderByCreateDateDesc(PAGE))
        .thenReturn(new PageImpl<>(List.of(event(1L, 5L, 7L))));

    var result = auditService.find(PAGE);

    assertThat(result.getContent())
        .singleElement()
        .satisfies(i -> assertThat(i.getId()).isEqualTo(1L));
    verify(auditRepository, never()).findAllByTenantIdOrderByCreateDateDesc(anyLong(), any());
    verify(auditRepository, never()).findAllByAgencyIdInOrderByCreateDateDesc(any(), any());
  }

  @Test
  void find_Should_RestrictATenantAdminToTheirOwnTenant() {
    TenantContext.setCurrentTenant(5L);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    when(auditRepository.findAllByTenantIdOrderByCreateDateDesc(5L, PAGE))
        .thenReturn(new PageImpl<>(List.of(event(1L, 5L, 7L))));

    auditService.find(PAGE);

    // The tenant comes from the request context, never from a client-supplied scope.
    verify(auditRepository).findAllByTenantIdOrderByCreateDateDesc(eq(5L), any());
    verify(auditRepository, never()).findAllByOrderByCreateDateDesc(any());
  }

  @Test
  void find_Should_RestrictAnAgencyAdminToTheirOwnAgencies() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(false);
    when(authenticatedUser.isAgencySuperAdmin()).thenReturn(false);
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("agency-admin-1");
    when(adminAgencyRepository.findByAdminId("agency-admin-1"))
        .thenReturn(List.of(adminAgency(7L), adminAgency(9L)));
    when(auditRepository.findAllByAgencyIdInOrderByCreateDateDesc(List.of(7L, 9L), PAGE))
        .thenReturn(new PageImpl<>(List.of(event(1L, 5L, 7L))));

    auditService.find(PAGE);

    verify(auditRepository).findAllByAgencyIdInOrderByCreateDateDesc(eq(List.of(7L, 9L)), any());
  }

  @Test
  void find_Should_ReturnNothingForAnAgencyAdminWithoutAgencies() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(false);
    when(authenticatedUser.isAgencySuperAdmin()).thenReturn(false);
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("agency-admin-1");
    when(adminAgencyRepository.findByAdminId("agency-admin-1")).thenReturn(List.of());

    assertThat(auditService.find(PAGE).getContent()).isEmpty();
    verify(auditRepository, never()).findAllByAgencyIdInOrderByCreateDateDesc(any(), any());
  }

  @Test
  void find_Should_RefuseAnyOtherRole() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(false);
    when(authenticatedUser.isAgencySuperAdmin()).thenReturn(false);
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(false);

    assertThatThrownBy(() -> auditService.find(PAGE)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void find_Should_RefuseATenantAdminWithoutATenantContext() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);

    // Fail closed: no resolvable tenant must never fall through to an unfiltered query.
    assertThatThrownBy(() -> auditService.find(PAGE)).isInstanceOf(AccessDeniedException.class);
    verify(auditRepository, never()).findAllByOrderByCreateDateDesc(any());
  }

  private AdminAgency adminAgency(Long agencyId) {
    var adminAgency = new AdminAgency();
    adminAgency.setAgencyId(agencyId);
    return adminAgency;
  }

  private HandshakeAuditEvent event(Long id, Long tenantId, Long agencyId) {
    return HandshakeAuditEvent.builder()
        .id(id)
        .handshakeId("hs-1")
        .purpose("SUPPORT_ACCESS")
        .event("CONFIRMED")
        .tenantId(tenantId)
        .agencyId(agencyId)
        .createDate(nowInUtc())
        .build();
  }
}
