package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminBase;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends CrudRepository<Admin, String> {

  @Query(
      value =
          "SELECT a.id as id, a.firstName as firstName, a.lastName as lastName, a.email as email, a.tenantId as tenantId "
              + ", a.type as type, a.updateDate as updateDate "
              + "FROM Admin a "
              + "WHERE"
              + "  type = ?2 "
              + "AND ("
              + "  ?1 = '*' "
              + "  OR ("
              + "    UPPER(a.id) = UPPER(?1)"
              + "    OR UPPER(a.firstName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.lastName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.email) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR CONVERT(a.tenantId,char) LIKE CONCAT('%', UPPER(?1), '%')"
              + "  )"
              + " )")
  Page<AdminBase> findAllByInfix(String infix, Admin.AdminType type, Pageable pageable);

  /**
   * Same infix search as {@link #findAllByInfix}, but restricted to admins that are assigned to at
   * least one of the given agencies. Used to scope the agency-admin list for restricted agency
   * admins so they only ever see admins of their own agencies (and never other Träger's admins).
   */
  @Query(
      value =
          "SELECT a.id as id, a.firstName as firstName, a.lastName as lastName, a.email as email, a.tenantId as tenantId "
              + ", a.type as type, a.updateDate as updateDate "
              + "FROM Admin a "
              + "WHERE"
              + "  type = ?2 "
              + "AND a.id IN (SELECT aa.admin.id FROM AdminAgency aa WHERE aa.agencyId IN ?3) "
              + "AND ("
              + "  ?1 = '*' "
              + "  OR ("
              + "    UPPER(a.id) = UPPER(?1)"
              + "    OR UPPER(a.firstName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.lastName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.email) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR CONVERT(a.tenantId,char) LIKE CONCAT('%', UPPER(?1), '%')"
              + "  )"
              + " )")
  Page<AdminBase> findAllByInfixAndAgencyIds(
      String infix, Admin.AdminType type, Collection<Long> agencyIds, Pageable pageable);

  @Query(value = "SELECT a FROM Admin a WHERE id = ?1 AND type = ?2")
  Optional<Admin> findByIdAndType(String adminId, Admin.AdminType type);

  @Query(value = "SELECT a FROM Admin a WHERE tenantId = ?1 AND type = ?2")
  List<Admin> findByTenantIdAndType(Long tenantId, Admin.AdminType type);

  List<Admin> findByType(Admin.AdminType type);

  List<Admin> findAllByIdIn(Set<String> adminIds);

  @Query("SELECT a.id FROM Admin a WHERE a.id IN :ids")
  Set<String> findExistingIdsByIdIn(@Param("ids") Collection<String> ids);
}
