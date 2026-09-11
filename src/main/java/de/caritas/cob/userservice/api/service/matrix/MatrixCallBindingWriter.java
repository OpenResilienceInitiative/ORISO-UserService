package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatrixCallBindingWriter {
  private final MatrixCallBindingRepository bindings;

  /** A uniqueness race must not mark the caller's transaction rollback-only. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void insert(MatrixCallBinding binding) {
    bindings.saveAndFlush(binding);
  }
}
