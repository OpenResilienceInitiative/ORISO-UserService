package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.DeletionAuditLog;
import de.caritas.cob.userservice.api.port.out.DeletionAuditLogRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class DeletionAuditServiceTest {

  @InjectMocks private DeletionAuditService service;
  @Mock private DeletionAuditLogRepository repository;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private PlatformTransactionManager transactionManager;

  @Test
  void recordAsCurrentActor_Should_StoreWhoWhatWhenAndOutcome() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(true);

    service.recordAsCurrentActor(
        DeletionAuditTargetType.CONSULTANT, "consultant-9", 4L, DeletionAuditOutcome.SCHEDULED);

    var saved = savedEntry();
    assertThat(saved.getActorId()).isEqualTo("admin-1");
    assertThat(saved.getActorRole()).isEqualTo("AGENCY_ADMIN");
    assertThat(saved.getTargetType()).isEqualTo(DeletionAuditTargetType.CONSULTANT);
    assertThat(saved.getTargetId()).isEqualTo("consultant-9");
    assertThat(saved.getTenantId()).isEqualTo(4L);
    assertThat(saved.getOutcome()).isEqualTo(DeletionAuditOutcome.SCHEDULED);
    assertThat(saved.getCreateDate()).isNotNull();
  }

  @Test
  void recordAsCurrentActor_Should_NameThePlatformAdminAboveTheTenantAndAgencyRoles() {
    when(authenticatedUser.getUserId()).thenReturn("root");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    service.recordAsCurrentActor(
        DeletionAuditTargetType.TENANT_ADMIN, "t-admin", 2L, DeletionAuditOutcome.EXECUTED);

    assertThat(savedEntry().getActorRole()).isEqualTo("PLATFORM_ADMIN");
  }

  @Test
  void recordAsCurrentActor_Should_FallBackToUnknownRole_When_NoKnownRoleIsHeld() {
    when(authenticatedUser.getUserId()).thenReturn("someone");

    service.recordAsCurrentActor(
        DeletionAuditTargetType.AGENCY_ADMIN, "a-1", null, DeletionAuditOutcome.EXECUTED);

    assertThat(savedEntry().getActorRole()).isEqualTo("UNKNOWN");
  }

  @Test
  void recordAsCurrentActor_Should_NameTheUserAdminCaller() {
    when(authenticatedUser.getUserId()).thenReturn("svc");
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user-admin"));
    service.recordAsCurrentActor(
        DeletionAuditTargetType.CONSULTANT, "c-1", null, DeletionAuditOutcome.SCHEDULED);
    assertThat(savedEntry().getActorRole()).isEqualTo("USER_ADMIN");
  }

  @Test
  void recordAsCurrentActor_Should_NameTheTechnicalCaller() {
    when(authenticatedUser.getUserId()).thenReturn("svc");
    when(authenticatedUser.getRoles()).thenReturn(Set.of("technical"));
    service.recordAsCurrentActor(
        DeletionAuditTargetType.CONSULTANT, "c-1", null, DeletionAuditOutcome.SCHEDULED);
    assertThat(savedEntry().getActorRole()).isEqualTo("TECHNICAL");
  }

  @Test
  void record_Should_WaitForTheCallersCommit_And_SkipARolledBackDeletion() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.recordAsCurrentActor(
          DeletionAuditTargetType.CONSULTANT, "consultant-9", 4L, DeletionAuditOutcome.SCHEDULED);
      verify(repository, never()).save(any());

      // The caller's transaction rolls back: no "scheduled" entry may appear.
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      verify(repository, never()).save(any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void record_Should_WriteAfterTheCallersCommit() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.recordAsCurrentActor(
          DeletionAuditTargetType.CONSULTANT, "consultant-9", 4L, DeletionAuditOutcome.SCHEDULED);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertThat(savedEntry().getActorId()).isEqualTo("admin-1");
  }

  @Test
  void recordAsSystem_Should_NotTouchTheRequestScopedUser() {
    service.recordAsSystem(
        DeletionAuditTargetType.CONSULTANT, "consultant-9", 4L, DeletionAuditOutcome.EXECUTED);

    var saved = savedEntry();
    assertThat(saved.getActorId()).isNull();
    assertThat(saved.getActorRole()).isEqualTo("SYSTEM");
    verify(authenticatedUser, never()).getUserId();
  }

  @Test
  void recordAsSystem_Should_KeepTheTimeTheReportingServiceDeletedTheTarget() {
    var purgedAt = java.time.LocalDateTime.of(2026, 10, 30, 1, 0, 5);

    service.recordAsSystem(
        DeletionAuditTargetType.AGENCY, "268", 4L, DeletionAuditOutcome.EXECUTED, purgedAt);

    var saved = savedEntry();
    assertThat(saved.getCreateDate()).isEqualTo(purgedAt);
    assertThat(saved.getActorRole()).isEqualTo("SYSTEM");
  }

  @Test
  void recordAsSystem_Should_FallBackToNow_When_NoDeletionTimeIsKnown() {
    var before = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);

    service.recordAsSystem(
        DeletionAuditTargetType.AGENCY, "268", 4L, DeletionAuditOutcome.EXECUTED, null);

    assertThat(savedEntry().getCreateDate()).isAfter(before);
  }

  @Test
  void recordReported_Should_StoreTheActorTheTechnicalCallerNamed_NotTheTechnicalAccount() {
    service.recordReported(
        DeletionAuditTargetType.AGENCY,
        "268",
        4L,
        DeletionAuditOutcome.SCHEDULED,
        "admin-7",
        "AGENCY_ADMIN");

    var saved = savedEntry();
    assertThat(saved.getActorId()).isEqualTo("admin-7");
    assertThat(saved.getActorRole()).isEqualTo("AGENCY_ADMIN");
    assertThat(saved.getTargetType()).isEqualTo(DeletionAuditTargetType.AGENCY);
    assertThat(saved.getTargetId()).isEqualTo("268");
    assertThat(saved.getTenantId()).isEqualTo(4L);
    verify(authenticatedUser, never()).getUserId();
  }

  @Test
  void recordReported_Should_NeverBreakTheReportingService_When_TheAuditWriteFails() {
    when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

    service.recordReported(
        DeletionAuditTargetType.TENANT,
        "9",
        9L,
        DeletionAuditOutcome.EXECUTED,
        "a",
        "TENANT_ADMIN");

    verify(repository).save(any());
  }

  @Test
  void record_Should_NeverBreakTheDeletion_When_TheAuditWriteFails() {
    when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

    service.recordAsSystem(
        DeletionAuditTargetType.CONSULTANT, "c", null, DeletionAuditOutcome.EXECUTED);

    verify(repository).save(any());
  }

  @Test
  void record_Should_RunInItsOwnTransaction_SoAFailedInsertCannotPoisonTheCaller() {
    service.recordAsSystem(
        DeletionAuditTargetType.CONSULTANT, "c", null, DeletionAuditOutcome.EXECUTED);

    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void entity_Should_HoldOnlyOpaqueIdsAndTypes_NoPersonalData() {
    List<String> fields =
        Arrays.stream(DeletionAuditLog.class.getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(f -> f.getName())
            .sorted()
            .toList();

    assertThat(fields)
        .containsExactlyInAnyOrder(
            "actorId",
            "actorRole",
            "createDate",
            "id",
            "outcome",
            "targetId",
            "targetType",
            "tenantId");
  }

  private DeletionAuditLog savedEntry() {
    var captor = ArgumentCaptor.forClass(DeletionAuditLog.class);
    verify(repository).save(captor.capture());
    return captor.getValue();
  }
}
