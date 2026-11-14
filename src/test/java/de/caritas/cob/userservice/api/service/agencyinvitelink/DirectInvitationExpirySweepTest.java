package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ORISO-Frontend#2252: paging, per-id isolation and the removal retries of the sweep. */
class DirectInvitationExpirySweepTest {

  private final DirectInvitationExpiryService service = mock(DirectInvitationExpiryService.class);
  private final DirectInvitationExpirySweep sweep = new DirectInvitationExpirySweep(service);

  @BeforeEach
  void setUp() {
    when(service.dueIds(anyLong(), anyInt())).thenReturn(List.of());
    when(service.pendingRemovalIds(anyLong(), anyInt())).thenReturn(List.of());
  }

  @Test
  void sweep_Should_ReadTheNextPage_AfterTheLastIdOfAFullPage() {
    var full = LongStream.rangeClosed(1, DirectInvitationExpirySweep.PAGE_SIZE).boxed().toList();
    when(service.dueIds(0L, DirectInvitationExpirySweep.PAGE_SIZE)).thenReturn(full);
    when(service.dueIds(
            (long) DirectInvitationExpirySweep.PAGE_SIZE, DirectInvitationExpirySweep.PAGE_SIZE))
        .thenReturn(List.of(500L));

    sweep.sweep();

    verify(service).expire(500L);
    verify(service).expire((long) DirectInvitationExpirySweep.PAGE_SIZE);
  }

  @Test
  void sweep_Should_GoOn_After_OneInvitationFails() {
    when(service.dueIds(eq(0L), anyInt())).thenReturn(List.of(1L, 2L));
    when(service.expire(1L)).thenThrow(new IllegalStateException("boom"));

    sweep.sweep();

    verify(service).expire(2L);
  }

  @Test
  void sweep_Should_ReadTheNextRemovalPage_AfterTheLastIdOfAFullPage() {
    var full =
        LongStream.rangeClosed(1, DirectInvitationExpirySweep.PAGE_SIZE)
            .map(id -> id * 10)
            .boxed()
            .toList();
    when(service.pendingRemovalIds(0L, DirectInvitationExpirySweep.PAGE_SIZE)).thenReturn(full);
    when(service.pendingRemovalIds(
            DirectInvitationExpirySweep.PAGE_SIZE * 10L, DirectInvitationExpirySweep.PAGE_SIZE))
        .thenReturn(List.of(5_000L));

    sweep.sweep();

    verify(service).retryRemoval(DirectInvitationExpirySweep.PAGE_SIZE * 10L);
    verify(service).retryRemoval(5_000L);
  }

  @Test
  void sweep_Should_RetryEveryDueRemoval_AndGoOnAfterAFailure() {
    when(service.pendingRemovalIds(eq(0L), anyInt())).thenReturn(List.of(7L, 8L));
    when(service.retryRemoval(7L)).thenThrow(new IllegalStateException("boom"));

    sweep.sweep();

    verify(service).retryRemoval(8L);
  }
}
