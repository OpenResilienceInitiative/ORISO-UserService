package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.IdentityTombstone;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityTombstoneRepository extends JpaRepository<IdentityTombstone, Long> {
  Optional<IdentityTombstone> findFirstBySubjectIdOrderByHardDeletedAtDesc(String subjectId);
}
