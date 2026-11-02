package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.DeletionAuditLogRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyDeletionAuditOutboxClient;
import de.caritas.cob.userservice.api.service.agency.AgencyDeletionAuditOutboxClient.PendingEntry;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyDeletionAuditPullTest {

  private static final DeletionAuditTargetType AGENCY = DeletionAuditTargetType.AGENCY;
  private static final DeletionAuditOutcome EXECUTED = DeletionAuditOutcome.EXECUTED;
  private static final java.time.LocalDateTime PURGED_AT =
      java.time.LocalDateTime.of(2026, 10, 30, 1, 0);

  @Mock private AgencyDeletionAuditOutboxClient outbox;
  @Mock private DeletionAuditService deletionAuditService;
  @Mock private DeletionAuditLogRepository deletionAuditLogRepository;
  @InjectMocks private AgencyDeletionAuditPull pull;

  private static PendingEntry row(long id, long agencyId, Long tenantId) {
    return new PendingEntry(id, agencyId, tenantId, "EXECUTED", "2026-10-30T01:00:00");
  }

  @Test
  void recordsEachPurgedAgencyAsASystemExecutionAndAcknowledgesIt() {
    when(outbox.fetchPending(anyInt())).thenReturn(List.of(row(1, 268, 4L), row(2, 269, null)));
    when(deletionAuditLogRepository.existsByTargetTypeAndTargetIdAndOutcome(any(), any(), any()))
        .thenReturn(false, true, false, true);

    assertThat(pull.pullOnce()).isEqualTo(2);

    verify(deletionAuditService).recordAsSystem(AGENCY, "268", 4L, EXECUTED, PURGED_AT);
    verify(deletionAuditService).recordAsSystem(AGENCY, "269", null, EXECUTED, PURGED_AT);
    verify(outbox).acknowledge(List.of(1L, 2L));
  }

  @Test
  void anEntryAlreadyInTheLogFromAnEarlierUnacknowledgedRunIsNotRecordedTwice() {
    when(outbox.fetchPending(anyInt())).thenReturn(List.of(row(1, 268, 4L)));
    when(deletionAuditLogRepository.existsByTargetTypeAndTargetIdAndOutcome(
            AGENCY, "268", EXECUTED))
        .thenReturn(true);

    pull.pullOnce();

    verify(deletionAuditService, never()).recordAsSystem(any(), any(), any(), any(), any());
    verify(outbox).acknowledge(List.of(1L));
  }

  @Test
  void aRowWhoseLogWriteDidNotPersistStaysInTheOutbox() {
    // recordAsSystem is best effort and swallows its own failure; only a persisted entry is acked.
    when(outbox.fetchPending(anyInt())).thenReturn(List.of(row(1, 268, 4L), row(2, 269, 4L)));
    when(deletionAuditLogRepository.existsByTargetTypeAndTargetIdAndOutcome(
            AGENCY, "268", EXECUTED))
        .thenReturn(false, false);
    when(deletionAuditLogRepository.existsByTargetTypeAndTargetIdAndOutcome(
            AGENCY, "269", EXECUTED))
        .thenReturn(false, true);

    pull.pullOnce();

    verify(outbox).acknowledge(List.of(2L));
  }

  @Test
  void unknownOutcomesAndMalformedRowsAreNeitherRecordedNorAcknowledged() {
    when(outbox.fetchPending(anyInt()))
        .thenReturn(
            List.of(
                new PendingEntry(1L, 268L, 4L, "SCHEDULED", null),
                new PendingEntry(2L, null, 4L, "EXECUTED", null),
                new PendingEntry(null, 270L, 4L, "EXECUTED", null)));

    assertThat(pull.pullOnce()).isZero();

    verify(deletionAuditService, never()).recordAsSystem(any(), any(), any(), any(), any());
    verify(outbox, never()).acknowledge(anyList());
  }

  @Test
  void anEmptyOutboxNeedsNoAcknowledgement() {
    when(outbox.fetchPending(anyInt())).thenReturn(List.of());

    assertThat(pull.pullOnce()).isZero();

    verify(outbox, never()).acknowledge(anyList());
  }

  @Test
  void aFailedAcknowledgementSurfacesAndTheNextRunDoesNotDoubleRecord() {
    when(outbox.fetchPending(anyInt())).thenReturn(List.of(row(1, 268, 4L)));
    when(deletionAuditLogRepository.existsByTargetTypeAndTargetIdAndOutcome(
            AGENCY, "268", EXECUTED))
        .thenReturn(false, true, true);
    doThrow(new IllegalStateException("ack failed")).when(outbox).acknowledge(List.of(1L));

    assertThatThrownBy(() -> pull.pullOnce()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> pull.pullOnce()).isInstanceOf(IllegalStateException.class);

    verify(deletionAuditService, times(1)).recordAsSystem(AGENCY, "268", 4L, EXECUTED, PURGED_AT);
  }

  @Test
  void anUnreadablePurgeTimeIsLoggedWithTheTimeOfThePull() {
    when(outbox.fetchPending(anyInt()))
        .thenReturn(List.of(new PendingEntry(1L, 268L, 4L, "EXECUTED", "yesterday")));
    when(deletionAuditLogRepository.existsByTargetTypeAndTargetIdAndOutcome(
            AGENCY, "268", EXECUTED))
        .thenReturn(false, true);

    pull.pullOnce();

    verify(deletionAuditService).recordAsSystem(AGENCY, "268", 4L, EXECUTED, null);
    verify(outbox).acknowledge(List.of(1L));
  }
}
