package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ErstantwortTextOverride;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErstantwortTextOverrideRepository
    extends JpaRepository<ErstantwortTextOverride, ErstantwortTextOverride.Key> {

  List<ErstantwortTextOverride> findByScopeAndScopeId(String scope, Long scopeId);
}
