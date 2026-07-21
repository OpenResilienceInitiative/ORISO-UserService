package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TutorialProgress;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface TutorialProgressRepository extends CrudRepository<TutorialProgress, Long> {

  Optional<TutorialProgress> findByUserIdAndSurfaceAndTourIdAndTourVersion(
      String userId, String surface, String tourId, Integer tourVersion);

  List<TutorialProgress> findByUserIdAndSurface(String userId, String surface);

  /** Number of progress rows a user owns — used to cap per-user row growth. */
  long countByUserId(String userId);
}
