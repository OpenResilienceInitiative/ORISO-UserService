package de.caritas.cob.userservice.api.service.matrix;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Test double that only records whether a transaction is open right now. */
public class RecordingTransactionManager implements PlatformTransactionManager {
  private int open;
  private int started;

  @Override
  public TransactionStatus getTransaction(TransactionDefinition definition) {
    open++;
    started++;
    return new SimpleTransactionStatus(true);
  }

  @Override
  public void commit(TransactionStatus status) {
    open--;
  }

  @Override
  public void rollback(TransactionStatus status) {
    open--;
  }

  public boolean isOpen() {
    return open > 0;
  }

  public int started() {
    return started;
  }
}
