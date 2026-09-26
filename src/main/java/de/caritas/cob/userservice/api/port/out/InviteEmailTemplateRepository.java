package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteEmailTemplateRepository extends JpaRepository<InviteEmailTemplate, Long> {

  List<InviteEmailTemplate> findByKindOrderByCreateDateDesc(InviteEmailTemplateKind kind);

  /**
   * The system's own template lookup (e.g. the signed-DPA notice), which runs without a caller.
   * Restricted to platform templates so a Träger cannot place a row that the platform sends in
   * everybody's name.
   */
  List<InviteEmailTemplate> findByKindAndActiveTrueAndTenantIdIsNullOrderByCreateDateDesc(
      InviteEmailTemplateKind kind);

  /**
   * What one Träger may see: its own templates plus the platform's. A {@code null} {@code kind}
   * means "every kind".
   */
  @Query(
      """
      select t from InviteEmailTemplate t
      where (:kind is null or t.kind = :kind)
        and (t.tenantId is null or t.tenantId = :tenantId)
      order by t.createDate desc
      """)
  List<InviteEmailTemplate> findVisibleForTenant(
      @Param("kind") InviteEmailTemplateKind kind, @Param("tenantId") Long tenantId);

  /** What the platform admin sees: everything. A {@code null} {@code kind} means "every kind". */
  @Query(
      """
      select t from InviteEmailTemplate t
      where (:kind is null or t.kind = :kind)
      order by t.createDate desc
      """)
  List<InviteEmailTemplate> findAllVisible(@Param("kind") InviteEmailTemplateKind kind);
}
