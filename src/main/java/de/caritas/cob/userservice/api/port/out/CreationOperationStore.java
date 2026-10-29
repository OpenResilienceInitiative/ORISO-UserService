package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Receipt;
import java.util.Optional;
import java.util.UUID;

/** Conditional persistence for the existing account-creation attempt store. */
public interface CreationOperationStore {
  int insert(Receipt receipt);

  Optional<Receipt> findByKey(UUID operationKey);

  Optional<Receipt> findById(UUID receiptId);

  int compareAndSet(Receipt receipt, long expectedVersion);
}
