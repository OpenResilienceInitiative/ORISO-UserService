package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.model.IdReservationReleaseTask;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class IdReservationReleaseProcessorTest {

  @InjectMocks private IdReservationReleaseProcessor processor;

  @Mock private IdReservationReleaseTaskRepository taskRepository;
  @Mock private TenantIdAllocationClient tenantIdAllocationClient;
  @Mock private AgencyIdAllocationClient agencyIdAllocationClient;

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void pendingTaskIds_ShouldSelectOnlyBoundedBackedOffAttempts() {
    setField(processor, "maxAttempts", 8);
    setField(processor, "retryBackoff", java.time.Duration.ofMinutes(3));
    IdReservationReleaseTask task = task(IdReservationReleaseType.TENANT, 41L, null);
    when(taskRepository.findRetryable(
            org.mockito.ArgumentMatchers.eq(8),
            org.mockito.ArgumentMatchers.any(LocalDateTime.class),
            org.mockito.ArgumentMatchers.eq(PageRequest.of(0, 100))))
        .thenReturn(java.util.List.of(task));

    assertThat(processor.pendingTaskIds()).containsExactly(1L);
  }

  @Test
  void process_ShouldDeleteTask_WhenReleaseSucceeds() {
    IdReservationReleaseTask task = task(IdReservationReleaseType.TENANT, 41L, null);
    when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
    when(tenantIdAllocationClient.release(41L)).thenReturn(true);

    assertThat(processor.process(1L)).isTrue();

    verify(taskRepository).delete(task);
    verify(taskRepository, never()).save(task);
  }

  @Test
  void process_ShouldRetainTaskAndRestoreTenantContext_WhenReleaseFails() {
    IdReservationReleaseTask task = task(IdReservationReleaseType.AGENCY, 73L, 12L);
    TenantContext.setCurrentTenantData(new TenantData(99L, "original"));
    when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
    when(agencyIdAllocationClient.release(73L))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenant()).isEqualTo(12L);
              return false;
            });

    assertThat(processor.process(1L)).isFalse();

    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastAttemptAt()).isNotNull();
    assertThat(TenantContext.getCurrentTenantData()).isEqualTo(new TenantData(99L, "original"));
    verify(taskRepository).save(task);
    verify(taskRepository, never()).delete(task);
  }

  @Test
  void process_ShouldSucceedWithoutWrites_WhenTaskIsAlreadyGone() {
    when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

    assertThat(processor.process(1L)).isTrue();

    verify(taskRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    verify(taskRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void process_ShouldRetainTaskAndClearTenantContext_WhenClientThrows() {
    IdReservationReleaseTask task = task(IdReservationReleaseType.TENANT, 41L, 12L);
    when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
    when(tenantIdAllocationClient.release(41L))
        .thenThrow(new IllegalStateException("ledger unreachable"));

    assertThat(processor.process(1L)).isFalse();

    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastAttemptAt()).isNotNull();
    assertThat(TenantContext.contextIsSet()).isFalse();
    verify(taskRepository).save(task);
  }

  private IdReservationReleaseTask task(
      IdReservationReleaseType type, Long reservedId, Long tenantContextId) {
    return IdReservationReleaseTask.builder()
        .id(1L)
        .allocationType(type)
        .reservedId(reservedId)
        .tenantContextId(tenantContextId)
        .createDate(LocalDateTime.now())
        .build();
  }
}
