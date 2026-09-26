package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the unique claim transaction separate from Matrix event processing. */
@Service
@RequiredArgsConstructor
public class ReplyEmailDeliveryWriter {
  private final @NonNull ReplyEmailDeliveryRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long reserve(String recipientUserId, String eventKey, long tenantId) {
    var delivery = new ReplyEmailDelivery();
    delivery.setRecipientUserId(recipientUserId);
    delivery.setEventKey(eventKey);
    delivery.setTenantId(tenantId);
    delivery.setStatus(ReplyEmailDelivery.Status.RESERVED);
    delivery.setCreatedAt(LocalDateTime.now());
    return repository.saveAndFlush(delivery).getId();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(long id, ReplyEmailDelivery.Status status) {
    var delivery = repository.findById(id).orElseThrow();
    delivery.setStatus(status);
    if (status == ReplyEmailDelivery.Status.SENT) {
      delivery.setSentAt(LocalDateTime.now());
    }
  }
}
