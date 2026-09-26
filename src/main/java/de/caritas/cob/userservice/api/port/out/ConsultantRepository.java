package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Consultant.ConsultantBase;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultantRepository
    extends JpaRepository<Consultant, String>, JpaSpecificationExecutor<Consultant> {

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Consultant c where c.id = :id")
  Optional<Consultant> findPictureOwnerForUpdate(@Param("id") String id);

  @EntityGraph(attributePaths = {"consultantAgencies", "languages"})
  Optional<Consultant> findByIdAndDeleteDateIsNull(String id);

  Optional<Consultant> findByEmailAndDeleteDateIsNull(String email);

  Optional<Consultant> findByUsernameAndDeleteDateIsNull(String username);

  @EntityGraph(attributePaths = {"consultantAgencies", "languages"})
  Optional<Consultant> findByPublicSlugAndDeleteDateIsNull(String publicSlug);

  boolean existsByPublicSlugAndIdNotAndDeleteDateIsNull(String publicSlug, String id);

  boolean existsByPendingPublicSlugAndIdNotAndDeleteDateIsNull(String pendingPublicSlug, String id);

  Optional<Consultant> findByMatrixUserIdAndDeleteDateIsNull(String matrixUserId);

  /**
   * Every consultant row referencing the given chat (Matrix) identity, <em>including soft-deleted
   * ones</em>. Uniqueness here is by username, so a soft-deleted consultant keeps owning their chat
   * account while freeing the username — the repair has to see that row or it would adopt a
   * colleague's rooms.
   *
   * @param matrixUserId the full Matrix user id
   * @return the rows holding it, empty when it is free
   */
  List<Consultant> findByMatrixUserId(String matrixUserId);

  /**
   * Every active consultant that owns no chat (Matrix) identity. Such a record looks complete to an
   * administrator but is refused by every counselling room operation.
   *
   * @return the consultants whose {@code matrixUserId} is null or blank
   */
  @Query(
      "SELECT c FROM Consultant c WHERE c.deleteDate IS NULL "
          + "AND (c.matrixUserId IS NULL OR TRIM(c.matrixUserId) = '')")
  List<Consultant> findWithoutChatIdentity();

  List<Consultant> findByConsultantAgenciesAgencyIdInAndDeleteDateIsNull(List<Long> agencyIds);

  List<Consultant> findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(Long agencyId);

  List<Consultant> findAllByDeleteDateNotNull();

  List<Consultant> findByDeleteDateIsNull();

  List<Consultant> findByTenantIdAndDeleteDateIsNullOrderByFirstNameAscLastNameAsc(Long tenantId);

  List<Consultant> findAllByIdIn(List<String> ids);

  @Query("SELECT c.id FROM Consultant c WHERE c.id IN :ids AND c.deleteDate IS NULL")
  Set<String> findActiveIdsByIdIn(@Param("ids") Collection<String> ids);

  @Query(
      value =
          "SELECT c.id as id, c.firstName as firstName, c.lastName as lastName, c.email as email, "
              + "c.updateDate as updateDate, COALESCE(c.updateDate, c.createDate) as lastUpdated "
              + "FROM Consultant c "
              + "WHERE "
              + "  c.deleteDate IS NULL "
              + "  AND "
              + "  (?2 IS NULL OR ?2 = 0 OR c.tenantId = ?2) "
              + "  AND "
              + "  ("
              + "    ?1 = '*' "
              + "    OR ("
              + "    UPPER(c.id) = UPPER(?1)"
              + "    OR UPPER(c.firstName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(c.lastName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(c.email) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(c.internalDisplayName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    )"
              + "  )")
  Page<ConsultantBase> findAllByInfix(String infix, Long tenantId, Pageable pageable);

  @Query(
      value =
          "SELECT distinct c.id as id, c.firstName as firstName, c.lastName as lastName, "
              + "c.email as email, c.updateDate as updateDate, COALESCE(c.updateDate, c.createDate) as lastUpdated "
              + "FROM Consultant c "
              + "INNER JOIN ConsultantAgency ca ON c.id = ca.consultant.id "
              + "WHERE "
              + " c.deleteDate IS NULL "
              + " AND "
              + " ca.deleteDate IS NULL "
              + " AND "
              + " (?3 IS NULL OR ?3 = 0 OR c.tenantId = ?3) "
              + " AND "
              + " ca.agencyId IN (?2) "
              + " AND ("
              + "  ?1 = '*' "
              + "  OR ("
              + "    UPPER(c.id) = UPPER(?1)"
              + "    OR UPPER(c.firstName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(c.lastName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(c.email) LIKE CONCAT('%', UPPER(?1), '%')"
              + "    OR UPPER(c.internalDisplayName) LIKE CONCAT('%', UPPER(?1), '%')"
              + "  )"
              + ")")
  Page<ConsultantBase> findAllByInfixAndAgencyIds(
      String infix, Collection<Long> agencyIds, Long tenantId, Pageable pageable);

  long countByDeleteDateIsNull();

  long countByTenantIdAndDeleteDateIsNull(Long tenantId);
}
