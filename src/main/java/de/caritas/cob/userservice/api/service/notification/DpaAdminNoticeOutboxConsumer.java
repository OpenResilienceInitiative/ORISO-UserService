package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Connect only to the matching authenticated TenantService outbox contract. */
final class DpaAdminNoticeOutboxConsumer {

  private final DpaSignedNoticeService notices;
  private final Function<Event, Optional<VerifiedSignature>> readExact;
  private final Consumer<Event> acknowledge;

  DpaAdminNoticeOutboxConsumer(
      DpaSignedNoticeService notices, TenantDpaSignatureReadClient client) {
    this(notices, client::readExact, client::acknowledge);
  }

  DpaAdminNoticeOutboxConsumer(
      DpaSignedNoticeService notices,
      Function<Event, Optional<VerifiedSignature>> readExact,
      Consumer<Event> acknowledge) {
    this.notices = notices;
    this.readExact = readExact;
    this.acknowledge = acknowledge;
  }

  boolean accept(Event event) {
    if (event == null
        || event.id() <= 0
        || event.tenantId() <= 0
        || event.signedAtEpochMillis() <= 0) {
      return false;
    }
    var signature = verifyExact(event);
    if (signature.isEmpty() || !notices.acceptExactAdminNotice(event, signature.get())) {
      return false;
    }
    acknowledge.accept(event);
    return true;
  }

  void recover() {
    notices.retryPendingExactAdminNotices(this::verifyExact);
  }

  private Optional<DpaSignatureDTO> verifyExact(Event event) {
    // Read retained legal audit independently of whether the producer marker was acknowledged.
    // The general list of a tenant's signatures cannot prove which producer row was signed.
    return readExact
        .apply(event)
        .filter(verified -> event.equals(verified.event()))
        .map(VerifiedSignature::signature);
  }

  record Event(long id, long tenantId, long signedAtEpochMillis) {}

  record VerifiedSignature(Event event, DpaSignatureDTO signature) {}
}
