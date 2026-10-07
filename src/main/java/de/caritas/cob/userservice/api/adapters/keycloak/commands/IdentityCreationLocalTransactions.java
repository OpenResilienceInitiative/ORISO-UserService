package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityCreationLocalTransactions {
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public <T> T execute(Supplier<T> operation) {
    return operation.get();
  }
}
