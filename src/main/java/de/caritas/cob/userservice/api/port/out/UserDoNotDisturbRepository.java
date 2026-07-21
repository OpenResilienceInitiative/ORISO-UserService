package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.UserDoNotDisturb;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDoNotDisturbRepository extends JpaRepository<UserDoNotDisturb, String> {

  Optional<UserDoNotDisturb> findByUserId(String userId);
}
