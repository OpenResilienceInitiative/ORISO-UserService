package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AdminListPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface AdminListPreferenceRepository extends CrudRepository<AdminListPreference, Long> {

  List<AdminListPreference> findByUserId(String userId);

  Optional<AdminListPreference> findByUserIdAndTab(String userId, String tab);
}
