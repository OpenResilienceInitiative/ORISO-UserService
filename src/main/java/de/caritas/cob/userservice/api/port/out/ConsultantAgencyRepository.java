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

  /**
   * Ids of the counsellors with an active relation to the Beratungsstelle, without loading the
   * relations and their eagerly fetched counsellors (ORISO-Admin#1524).
   */
  @Query(
      "select distinct ca.consultant.id from ConsultantAgency ca"
          + " where ca.agencyId = :agencyId and ca.deleteDate is null")
  List<String> findActiveConsultantIdsByAgencyId(@Param("agencyId") Long agencyId);

  List<ConsultantAgency> findByConsultantIdAndDeleteDateIsNull(String consultantId);

  /** The relations removed together with the consultant, i.e. stamped with its delete date. */
  List<ConsultantAgency> findByConsultantIdAndDeleteDate(
      String consultantId, java.time.LocalDateTime deleteDate);

  List<ConsultantAgency> findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
      Long agencyId);

  List<ConsultantAgency> findByConsultantIdAndAgencyIdAndDeleteDateIsNull(
      String consultantId, Long agencyId);

  @Query(
      """
      select removed.consultant.id as consultantId, removed.id as relationId
      from ConsultantAgency removed
      where removed.agencyId = :agencyId
        and removed.consultant.id in :consultantIds
        and removed.deleteDate is not null
        and not exists (
          select 1 from ConsultantAgency active
          where active.agencyId = removed.agencyId
            and active.consultant.id = removed.consultant.id
            and active.deleteDate is null
        )
        and not exists (
          select 1 from ConsultantAgency newer
          where newer.agencyId = removed.agencyId
            and newer.consultant.id = removed.consultant.id
            and (newer.deleteDate > removed.deleteDate
              or (newer.deleteDate = removed.deleteDate and newer.id > removed.id))
        )
        and not exists (
          select 1 from AgencyEnquiryRevocationTask pending
          where pending.consultantAgencyId = removed.id
            and pending.sessionId = :sessionId
            and pending.roomId = :roomId
        )
      """)
  List<PublicationRevocationCandidate> findPublicationRevocationCandidates(
      @Param("agencyId") Long agencyId,
      @Param("consultantIds") Collection<String> consultantIds,
      @Param("sessionId") Long sessionId,
      @Param("roomId") String roomId);

  interface PublicationRevocationCandidate {
    String getConsultantId();

    Long getRelationId();
  }

  boolean existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(String consultantId, Long agencyId);

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
