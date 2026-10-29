package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgencyInviteLinkRepository extends JpaRepository<AgencyInviteLink, Long> {

  Optional<AgencyInviteLink> findByToken(String token);

  /** A counsellor's own direct links, newest first (#1475). */
  List<AgencyInviteLink> findAllByConsultantIdAndLinkKindAndChatTypeInOrderByCreateDateDesc(
      String consultantId, String linkKind, Collection<String> chatTypes);

  Optional<AgencyInviteLink> findByIdAndConsultantIdAndLinkKindAndChatTypeIn(
      Long id, String consultantId, String linkKind, Collection<String> chatTypes);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AgencyInviteLink> findByTokenAndStatus(String token, String status);

  /** The share link of one running call (#1487); at most one per call. */
  Optional<AgencyInviteLink> findByCallBindingId(Long callBindingId);

  /** Locked: knocks through one share link are serialised on the link row (#1487). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT l FROM AgencyInviteLink l WHERE l.id = :id")
  Optional<AgencyInviteLink> findByIdForUpdate(@Param("id") Long id);

  /** Active links of one kind after {@code id}, oldest first, for the expiry sweep (#1487). */
  List<AgencyInviteLink> findByLinkKindAndStatusAndIdGreaterThanOrderByIdAsc(
      String linkKind, String status, Long id, Pageable pageable);

  /*
   * Admin list finders below never return call share links (#1487): their token opens a running
   * call and belongs only to the counsellors in that call.
   */

  /**
   * Tenant-scoped, filtered, paged finder for the new invite-links list endpoint. The {@code
   * tenantId} predicate is always enforced — a tenant admin must never see another tenant's links.
   * The other parameters are optional: pass {@code null} to skip a filter.
   */
  @Query(
      "SELECT l FROM AgencyInviteLink l"
          + " WHERE l.tenantId = :tenantId"
          + " AND l.linkKind <> 'CALL'"
          + " AND (:linkKind IS NULL OR l.linkKind = :linkKind)"
          + " AND (:topicId  IS NULL OR l.topicId  = :topicId)"
          + " AND (:chatType IS NULL OR l.chatType = :chatType)"
          + " AND (:status   IS NULL OR l.status   = :status)"
          + " ORDER BY l.createDate DESC")
  Page<AgencyInviteLink> findAllByTenantIdAndFilters(
      @Param("tenantId") Long tenantId,
      @Param("linkKind") String linkKind,
      @Param("topicId") Long topicId,
      @Param("chatType") String chatType,
      @Param("status") String status,
      Pageable pageable);

  @Query(
      "SELECT l FROM AgencyInviteLink l"
          + " WHERE l.tenantId = :tenantId"
          + " AND l.agencyId IN :agencyIds"
          + " AND l.linkKind <> 'CALL'"
          + " AND (:linkKind IS NULL OR l.linkKind = :linkKind)"
          + " AND (:topicId  IS NULL OR l.topicId  = :topicId)"
          + " AND (:chatType IS NULL OR l.chatType = :chatType)"
          + " AND (:status   IS NULL OR l.status   = :status)"
          + " ORDER BY l.createDate DESC")
  Page<AgencyInviteLink> findAllByTenantIdAndAgencyIdsAndFilters(
      @Param("tenantId") Long tenantId,
      @Param("agencyIds") java.util.Collection<Long> agencyIds,
      @Param("linkKind") String linkKind,
      @Param("topicId") Long topicId,
      @Param("chatType") String chatType,
      @Param("status") String status,
      Pageable pageable);

  /**
   * Cross-tenant variant used by super-admins (tenant-super). {@code tenantId} is optional — pass
   * {@code null} to span every tenant.
   */
  @Query(
      "SELECT l FROM AgencyInviteLink l"
          + " WHERE (:tenantId IS NULL OR l.tenantId = :tenantId)"
          + " AND l.linkKind <> 'CALL'"
          + " AND (:linkKind IS NULL OR l.linkKind = :linkKind)"
          + " AND (:topicId  IS NULL OR l.topicId  = :topicId)"
          + " AND (:chatType IS NULL OR l.chatType = :chatType)"
          + " AND (:status   IS NULL OR l.status   = :status)"
          + " ORDER BY l.createDate DESC")
  Page<AgencyInviteLink> findAllByFilters(
      @Param("tenantId") Long tenantId,
      @Param("linkKind") String linkKind,
      @Param("topicId") Long topicId,
      @Param("chatType") String chatType,
      @Param("status") String status,
      Pageable pageable);
}
