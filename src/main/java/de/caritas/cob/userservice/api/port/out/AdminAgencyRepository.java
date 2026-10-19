package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.AdminAgency.AdminAgencyBase;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AdminAgencyRepository extends CrudRepository<AdminAgency, Long> {

  List<AdminAgency> findByAdminId(String adminId);

  List<AdminAgency> findByAdminIdAndAgencyId(String adminId, Long agencyId);

  @Transactional
  void deleteByAdminIdAndAgencyId(String adminId, Long agencyId);

  @Transactional
  void deleteByAdminId(String adminId);

  // Distinct aliases: a derived query maps two columns to "id". Admin is a query root because
  // Hibernate does not apply the tenant filter to a to-one join.
  @Query(
      "SELECT a.id AS id, a.agencyId AS agencyId, ad.id AS adminId "
          + "FROM Admin ad, AdminAgency a WHERE a.admin = ad AND ad.id IN :adminIds")
  List<AdminAgencyBase> findByAdminIdIn(@Param("adminIds") Set<String> adminIds);
}
