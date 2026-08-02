package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SupportAdminProfile;
import de.caritas.cob.userservice.api.model.SupportAdminProfile.SupportAdminStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportAdminProfileRepository extends JpaRepository<SupportAdminProfile, String> {

  List<SupportAdminProfile> findAllByAdminIdIn(Collection<String> adminIds);

  List<SupportAdminProfile> findAllByStatus(SupportAdminStatus status);
}
