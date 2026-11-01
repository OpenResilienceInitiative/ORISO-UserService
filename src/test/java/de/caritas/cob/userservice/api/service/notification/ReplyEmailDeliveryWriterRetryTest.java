package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplyEmailDeliveryWriterRetryTest {
  @Mock ReplyEmailDeliveryRepository repository;
  @InjectMocks ReplyEmailDeliveryWriter writer;

  @Test
  void aYoungMailIsRetriedLaterHoweverOftenItFailed() {
    // A platform outage shorter than the retry window must recover without operator action.
    var delivery = sending(ReplyEmailDeliveryWriter.MAX_RETRY_AGE.minusHours(1), 200);

    writer.retryLater(5L);

    assertThat(delivery.getStatus()).isEqualTo(Status.PENDING);
    assertThat(delivery.getNextAttemptAt()).isAfter(LocalDateTime.now());
  }

  @Test
  void aMailOlderThanTheRetryWindowIsQuarantinedInsteadOfRetriedForever() {
    var delivery = sending(ReplyEmailDeliveryWriter.MAX_RETRY_AGE.plusMinutes(1), 1);

    writer.retryLater(5L);

    assertThat(delivery.getStatus()).isEqualTo(Status.QUARANTINED);
  }

  @Test
  void selectionLoadsOnlyIdsAndNoMoreThanTheBatchNeedsPerTenant() {
    var tenants = java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList();
    when(repository.findDueTenantIds(
            org.mockito.ArgumentMatchers.eq(Status.PENDING), org.mockito.ArgumentMatchers.any()))
        .thenReturn(tenants);
    when(repository.findDueIds(
            org.mockito.ArgumentMatchers.eq(Status.PENDING),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            i -> {
              long tenant = i.getArgument(1);
              int max = ((org.springframework.data.domain.Limit) i.getArgument(3)).max();
              return java.util.stream.LongStream.range(0, max)
                  .mapToObj(n -> tenant * 1000 + n)
                  .toList();
            });

    var batch = writer.pendingIds();

    assertThat(batch).hasSize(100).doesNotHaveDuplicates();
    var limits = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Limit.class);
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.atMost(100))
        .findDueIds(
            org.mockito.ArgumentMatchers.eq(Status.PENDING),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            limits.capture());
    assertThat(
            limits.getAllValues().stream()
                .mapToInt(org.springframework.data.domain.Limit::max)
                .sum())
        .isLessThanOrEqualTo(200);
  }

  private ReplyEmailDelivery sending(Duration age, int attemptCount) {
    var delivery = new ReplyEmailDelivery();
    delivery.setCreatedAt(LocalDateTime.now().minus(age));
    delivery.setId(5L);
    delivery.setTenantId(7L);
    delivery.setStatus(Status.SENDING);
    delivery.setAttemptCount(attemptCount);
    delivery.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
    when(repository.findById(5L)).thenReturn(Optional.of(delivery));
    return delivery;
  }
}
