package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverReasonPolicy;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseHandoverReasonPolicyRepository
    extends JpaRepository<CaseHandoverReasonPolicy, String> {

  List<CaseHandoverReasonPolicy> findAllByOrderByDisplayOrderAscCodeAsc();

  List<CaseHandoverReasonPolicy> findByEnabledTrueOrderByDisplayOrderAscCodeAsc();
}
