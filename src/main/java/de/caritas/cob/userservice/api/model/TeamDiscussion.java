package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * US#473 / ADR-016: the Team-Besprechung side room attached to one open enquiry. The room lives
 * entirely outside the client's conversation; it is archived (hard close) the moment the enquiry is
 * accepted and is reachable read-only afterwards.
 */
@Entity
@Table(name = "team_discussion")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
@FilterDef(
    name = "tenantFilter",
    parameters = {@ParamDef(name = "tenantId", type = Long.class)})
@Filter(
    name = "tenantFilter",
    condition = "(tenant_id = :tenantId OR (:tenantId = 1 AND tenant_id IS NULL))")
public class TeamDiscussion implements TenantAware {

  public enum Status {
    OPEN,
    ARCHIVED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "session_id", nullable = false, unique = true)
  private Long sessionId;

  @Column(name = "matrix_room_id", nullable = false, length = 255)
  private String matrixRoomId;

  @Enumerated(EnumType.STRING)
  @Column(name = "discussion_status", nullable = false, length = 20)
  @Builder.Default
  private Status status = Status.OPEN;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;

  @Column(name = "archive_date")
  private LocalDateTime archiveDate;

  @Column(name = "created_by_consultant_id", length = 36)
  private String createdByConsultantId;

  @Column(name = "is_first_notified", nullable = false)
  @Builder.Default
  private boolean firstNotified = false;

  @Column(name = "tenant_id")
  private Long tenantId;
}
