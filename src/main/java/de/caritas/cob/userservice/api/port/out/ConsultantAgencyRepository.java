package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgency.ConsultantAgencyBase;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ConsultantAgencyRepository extends CrudRepository<ConsultantAgency, Long> {

  List<ConsultantAgency> findByAgencyIdAndDeleteDateIsNull(Long agencyId);

  List<ConsultantAgency> findByConsultantIdAndDeleteDateIsNull(String consultantId);

  List<ConsultantAgency> findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
      Long agencyId);

  List<ConsultantAgency> findByConsultantIdAndAgencyIdAndDeleteDateIsNull(
      String consultantId, Long agencyId);

  boolean existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(String consultantId, Long agencyId);

  /**
   * Support-target lookup (ADR-018 §5). Returns one row per consultant-agency pair, because support
   * is always requested for a consultant at one concrete agency — never for a consultant as such.
   * Only active, undeleted assignments are eligible.
   */
  @Query(
      """
      select ca from ConsultantAgency ca
       where ca.deleteDate is null
         and ca.consultant.deleteDate is null
         and (
           lower(ca.consultant.firstName) like lower(concat('%', :infix, '%'))
           or lower(ca.consultant.lastName) like lower(concat('%', :infix, '%'))
           or lower(ca.consultant.email) like lower(concat('%', :infix, '%'))
           or lower(ca.consultant.username) like lower(concat('%', :infix, '%'))
         )
       order by ca.consultant.lastName asc, ca.consultant.firstName asc, ca.agencyId asc
      """)
  org.springframework.data.domain.Page<ConsultantAgency> findSupportTargets(
      @Param("infix") String infix, org.springframework.data.domain.Pageable pageable);

  List<ConsultantAgency> findByConsultantId(String consultantId);

  List<ConsultantAgency> findByConsultantIdAndStatusAndDeleteDateIsNull(
      String consultantId, ConsultantAgencyStatus status);

  ConsultantAgency findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
      String consultantId, Long agencyId, ConsultantAgencyStatus status);

  List<ConsultantAgency> findByAgencyIdInAndDeleteDateIsNull(Collection<Long> agencyIds);

  List<ConsultantAgency> findByConsultantIdInAndDeleteDateIsNull(Set<String> consultantIds);

  // Explicit projection query with distinct aliases. The derived query would map both getId()
  // (ConsultantAgency.id) and getConsultantId() (the joined consultant.id) to the alias "id",
  // which Hibernate 6 rejects with an AliasCollisionException.
  @Query(
      "SELECT ca.id AS id, ca.agencyId AS agencyId, ca.consultant.id AS consultantId, "
          + "ca.deleteDate AS deleteDate "
          + "FROM ConsultantAgency ca "
          + "WHERE ca.consultant.id IN (:consultantIds) AND ca.deleteDate IS NULL")
  List<ConsultantAgencyBase> findByConsultantIdInAndDeleteDateIsNull(
      @Param("consultantIds") List<String> consultantIds);
}
