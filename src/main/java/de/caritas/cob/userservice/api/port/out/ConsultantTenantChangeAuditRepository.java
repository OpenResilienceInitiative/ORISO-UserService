package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ConsultantTenantChangeAudit;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface ConsultantTenantChangeAuditRepository
    extends CrudRepository<ConsultantTenantChangeAudit, Long> {

  List<ConsultantTenantChangeAudit> findByConsultantIdOrderByCreateDateAsc(String consultantId);
}
