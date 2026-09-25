package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminBase;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository
    extends JpaRepository<Admin, String>, JpaSpecificationExecutor<Admin> {

  @Query(
      value =
          "SELECT a.id as id, a.firstName as firstName, a.lastName as lastName, a.email as email, a.tenantId as tenantId "
              + ", a.type as type, a.updateDate as updateDate, COALESCE(a.updateDate, a.createDate) as lastUpdated "
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
              + "    OR CAST(a.tenantId AS string) LIKE CONCAT('%', UPPER(?1), '%')"
              + "  )"
              + " )")
  Page<AdminBase> findAllByInfix(String infix, Admin.AdminType type, Pageable pageable);

  /**
   * Same infix search as {@link #findAllByInfix}, but restricted to the given tenant. Used to scope
   * the tenant-admin and agency-admin lists for a caller whose authority is tenant-bound, so a
   * single tenant admin cannot enumerate admins of other tenants via the search endpoints (#968).
   */
  @Query(
      value =
          "SELECT a.id as id, a.firstName as firstName, a.lastName as lastName, a.email as email, a.tenantId as tenantId "
              + ", a.type as type, a.updateDate as updateDate, COALESCE(a.updateDate, a.createDate) as lastUpdated "
              + "FROM Admin a "
              + "WHERE"
              + "  type = ?2 "
              + "AND a.tenantId = ?3 "
              + "AND ("
              + "  ?1 = '*' "
              + "  OR ("
              + "    UPPER(a.id) = UPPER(?1)"
              + "    OR UPPER(a.firstName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.lastName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.email) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR CAST(a.tenantId AS string) LIKE CONCAT('%', UPPER(?1), '%')"
              + "  )"
              + " )")
  Page<AdminBase> findAllByInfixAndTenantId(
      String infix, Admin.AdminType type, Long tenantId, Pageable pageable);

  /**
   * Same infix search as {@link #findAllByInfix}, restricted to admins of any of the given agencies
   * and, when {@code tenantId} is not null, to that tenant. Backs the Träger/Beratungsstelle filter
   * of the agency-admin list (#1263).
   */
  @Query(
      value =
          "SELECT a.id as id, a.firstName as firstName, a.lastName as lastName, a.email as email, a.tenantId as tenantId "
              + ", a.type as type, a.updateDate as updateDate, COALESCE(a.updateDate, a.createDate) as lastUpdated "
              + "FROM Admin a "
              + "WHERE"
              + "  type = ?2 "
              + "AND (?3 IS NULL OR a.tenantId = ?3) "
              + "AND a.id IN (SELECT aa.admin.id FROM AdminAgency aa WHERE aa.agencyId IN ?4) "
              + "AND ("
              + "  ?1 = '*' "
              + "  OR ("
              + "    UPPER(a.id) = UPPER(?1)"
              + "    OR UPPER(a.firstName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.lastName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(a.email) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR CAST(a.tenantId AS string) LIKE CONCAT('%', UPPER(?1), '%')"
              + "  )"
              + " )")
  Page<AdminBase> findAllByInfixAndTenantIdAndAgencyIds(
      String infix,
      Admin.AdminType type,
      Long tenantId,
      Collection<Long> agencyIds,
      Pageable pageable);

  @Query(value = "SELECT a FROM Admin a WHERE id = ?1 AND type = ?2")
  Optional<Admin> findByIdAndType(String adminId, Admin.AdminType type);

  @Query(value = "SELECT a FROM Admin a WHERE tenantId = ?1 AND type = ?2")
  List<Admin> findByTenantIdAndType(Long tenantId, Admin.AdminType type);

  List<Admin> findByType(Admin.AdminType type);

  List<Admin> findAllByIdIn(Set<String> adminIds);

  Optional<Admin> findFirstByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);

  @Query("SELECT a.id, a.type FROM Admin a WHERE a.id IN :ids")
  List<Object[]> findIdAndTypeByIdIn(@Param("ids") Collection<String> ids);
}
