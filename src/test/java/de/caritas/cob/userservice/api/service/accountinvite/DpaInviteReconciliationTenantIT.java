package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.scheduler.ScheduledLogger;
import de.caritas.cob.userservice.api.service.dpa.TenantDpaGateReadClient;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaGateStatusDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ActiveProfiles("testing")
@TestPropertySource(properties = "multitenancy.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ScheduledTaskClaimService.class,
  ScheduledTaskClaimWriter.class,
  DpaInviteReconciliationTenantIT.ClockConfiguration.class
})
class DpaInviteReconciliationTenantIT {

  @Autowired private AdminRepository admins;
  @Autowired private AccountInviteRepository invites;
  @Autowired private ScheduledTaskClaimService claims;
  @Autowired private ScheduledTaskClaimRepository claimRepository;

  @Test
  void scheduledPassReconcilesExactTenantsAndRecordsItsExecution() {
    Admin firstAdmin = admin(42L);
    Admin secondAdmin = admin(43L);
    Long first = acceptedInvite(42L, firstAdmin).getId();
    Long second = acceptedInvite(43L, secondAdmin).getId();
    Long foreign = acceptedInvite(44L, firstAdmin).getId();
    var owner = mock(TenantDpaGateReadClient.class);
    when(owner.read(42L)).thenReturn(signedGate("2026-08-03T10:11:12Z"));
    when(owner.read(43L)).thenReturn(signedGate("2026-08-04T10:11:12Z"));

    var metrics = new SimpleMeterRegistry();
    var worker = new DpaInviteReconciliation(invites, owner, claims);
    var factory = new AspectJProxyFactory(worker);
    factory.addAspect(new ScheduledLogger(metrics));
    DpaInviteReconciliation scheduledWorker = factory.getProxy();

    TenantContext.runIn(
        99L,
        () -> {
          scheduledWorker.reconcile();
          assertThat(TenantContext.getCurrentTenant()).isEqualTo(99L);
        });

    assertThat(invites.findById(first).orElseThrow().getDpaSignedAt())
        .isEqualTo(LocalDateTime.parse("2026-08-03T10:11:12"));
    assertThat(invites.findById(second).orElseThrow().getDpaSignedAt())
        .isEqualTo(LocalDateTime.parse("2026-08-04T10:11:12"));
    assertThat(invites.findById(foreign).orElseThrow().getDpaSignedAt()).isNull();
    verify(owner).read(42L);
    verify(owner).read(43L);
    verifyNoMoreInteractions(owner);
    assertThat(claimRepository.findById("account-invite-dpa-reconciliation")).isEmpty();

    String task = "DpaInviteReconciliation.reconcile()";
    assertThat(
            metrics
                .get("userservice.scheduler.executions")
                .tags("task", task, "outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            metrics
                .get("userservice.scheduler.duration")
                .tags("task", task, "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  private Admin admin(Long tenantId) {
    String id = UUID.randomUUID().toString();
    return admins.saveAndFlush(
        Admin.builder()
            .id(id)
            .tenantId(tenantId)
            .username(id)
            .firstName("Synthetic")
            .lastName("Admin")
            .email(id + "@example.org")
            .type(AdminType.TENANT)
            .build());
  }

  private AccountInvite acceptedInvite(Long tenantId, Admin actor) {
    return invites.saveAndFlush(
        AccountInvite.builder()
            .tenantId(tenantId)
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .status(AccountInviteStatus.ACCEPTED)
            .acceptedByUserId(actor.getId())
            .recipientEmail(actor.getEmail())
            .createDate(LocalDateTime.parse("2026-08-02T08:00:00"))
            .build());
  }

  private static DpaGateStatusDTO signedGate(String timestamp) {
    return new DpaGateStatusDTO()
        .dpaPublished(true)
        .dpaSigned(true)
        .dpaStatus("VALID")
        .currentDpaVersion("2026-08-01T08:00:00")
        .dpaSignedAt(timestamp);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
