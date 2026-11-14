package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.User;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/**
 * Filtered, paged reads of advice seekers for the admin interface. Kept apart from {@link
 * UserRepository} so its specification deletes do not overload that repository's methods.
 */
public interface AskerSearchRepository
    extends Repository<User, String>, JpaSpecificationExecutor<User> {}
