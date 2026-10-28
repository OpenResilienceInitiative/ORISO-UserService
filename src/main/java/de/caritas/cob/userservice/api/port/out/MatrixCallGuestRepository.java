package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallGuestRepository extends JpaRepository<MatrixCallGuest, Long> {
  Optional<MatrixCallGuest> findByBindingIdAndMatrixUserId(Long bindingId, String matrixUserId);

  List<MatrixCallGuest> findByBindingIdAndRevokedAtIsNull(Long bindingId);

  boolean existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(Long bindingId, String matrixUserId);
}
