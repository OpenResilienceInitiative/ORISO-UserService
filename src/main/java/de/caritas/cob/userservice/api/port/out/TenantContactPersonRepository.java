package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TenantContactPerson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantContactPersonRepository extends JpaRepository<TenantContactPerson, Long> {

  /** A deleted contact person takes the deputy along: a deputy needs a contact person. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE TenantContactPerson c SET c.contactAdminId = NULL, c.deputyAdminId = NULL "
          + "WHERE c.contactAdminId = :adminId")
  int clearContact(@Param("adminId") String adminId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE TenantContactPerson c SET c.deputyAdminId = NULL WHERE c.deputyAdminId = :adminId")
  int clearDeputy(@Param("adminId") String adminId);

  /** The general contact e-mail keeps a row alive even without a contact person. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM TenantContactPerson c WHERE c.contactAdminId IS NULL "
          + "AND c.deputyAdminId IS NULL AND c.generalContactEmail IS NULL")
  int deleteEmpty();
}
