package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ReservedPublicSlug;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservedPublicSlugRepository extends JpaRepository<ReservedPublicSlug, Long> {

  boolean existsBySlugAndActiveTrue(String slug);
}
