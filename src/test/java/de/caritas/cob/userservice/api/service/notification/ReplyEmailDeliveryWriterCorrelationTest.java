package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplyEmailDeliveryWriterCorrelationTest {
  @Mock ReplyEmailDeliveryRepository repository;
  @InjectMocks ReplyEmailDeliveryWriter writer;

  @Test
  void oneDeliveryKeepsAnOpaqueCorrelationIdForLaterReconciliation() {
    when(repository.saveAndFlush(any(ReplyEmailDelivery.class)))
        .thenAnswer(
            invocation -> {
              ReplyEmailDelivery delivery = invocation.getArgument(0);
              delivery.setId(17L);
              return delivery;
            });

    long id = writer.reserve("asker", "event-hash", 7L, 42L);

    assertThat(id).isEqualTo(17L);
    org.mockito.ArgumentCaptor<ReplyEmailDelivery> saved =
        org.mockito.ArgumentCaptor.forClass(ReplyEmailDelivery.class);
    org.mockito.Mockito.verify(repository).saveAndFlush(saved.capture());
    assertThat(UUID.fromString(saved.getValue().getCorrelationId())).isNotNull();
    assertThat(saved.getValue().getCorrelationId()).doesNotContain("asker", "event-hash");
  }
}
