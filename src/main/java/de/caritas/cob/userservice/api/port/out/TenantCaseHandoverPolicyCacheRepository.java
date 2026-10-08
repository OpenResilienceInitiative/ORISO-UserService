package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantCaseHandoverPolicyCacheRepository
    extends JpaRepository<TenantCaseHandoverPolicyCache, Long> {}
