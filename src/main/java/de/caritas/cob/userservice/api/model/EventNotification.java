package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

/** Persistent in-app notification event for a concrete recipient user. */
@Entity
@Table(name = "event_notification")
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
public class EventNotification implements TenantAware {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "recipient_user_id", nullable = false, length = 64)
  private String recipientUserId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Column(name = "category", nullable = false, length = 20)
  private String category;

  @Column(name = "title", nullable = false, length = 255)
  private String title;

  @Column(name = "text", columnDefinition = "text")
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  private String text;

  /**
   * WP-06 / ADR-AT-01: structured params (JSON) the client uses to render the event's visible
   * strings from i18n templates instead of server-stored text. Additive for now — populated
   * alongside {@code title}/{@code text} until the frontend renders purely from params and the
   * display-text columns are dropped.
   */
  @Column(name = "params", columnDefinition = "text")
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  private String params;

  @Column(name = "action_path", length = 512)
  private String actionPath;

  @Column(name = "source_session_id")
  private Long sourceSessionId;

  @Column(name = "read_date", columnDefinition = "datetime")
  private LocalDateTime readDate;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EventNotification)) {
      return false;
    }
    EventNotification that = (EventNotification) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
