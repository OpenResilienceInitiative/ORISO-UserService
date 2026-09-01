package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DpaSignedNoticeRepository extends JpaRepository<DpaSignedNotice, Long> {

  boolean existsByTenantIdAndDpaVersion(Long tenantId, String dpaVersion);
}
