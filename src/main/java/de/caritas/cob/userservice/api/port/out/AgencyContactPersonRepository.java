package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyContactPerson;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgencyContactPersonRepository extends JpaRepository<AgencyContactPerson, Long> {

  List<AgencyContactPerson> findByAgencyIdIn(Collection<Long> agencyIds);

  /** A deleted contact person takes the deputy along: a deputy needs a contact person. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE AgencyContactPerson c SET c.contactAdminId = NULL, c.deputyAdminId = NULL "
          + "WHERE c.contactAdminId = :adminId")
  int clearContact(@Param("adminId") String adminId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE AgencyContactPerson c SET c.deputyAdminId = NULL WHERE c.deputyAdminId = :adminId")
  int clearDeputy(@Param("adminId") String adminId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM AgencyContactPerson c "
          + "WHERE c.contactAdminId IS NULL AND c.deputyAdminId IS NULL")
  int deleteEmpty();
}
