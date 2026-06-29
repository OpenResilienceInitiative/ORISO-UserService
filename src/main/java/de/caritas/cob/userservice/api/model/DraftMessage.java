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
import org.hibernate.annotations.ParamDef;

@Entity
@Table(name = "draft_message")
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
public class DraftMessage implements TenantAware {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  @Column(name = "scope_key", nullable = false, length = 255)
  private String scopeKey;

  @Column(name = "text", columnDefinition = "text")
  private String text;

  @Column(name = "action_path", length = 512)
  private String actionPath;

  @Column(name = "title", length = 255)
  private String title;

  @Column(name = "source_session_id")
  private Long sourceSessionId;

  @Column(name = "room_ref", length = 255)
  private String roomRef;

  @Column(name = "thread_root_id", length = 255)
  private String threadRootId;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @Column(name = "update_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime updateDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DraftMessage)) {
      return false;
    }
    DraftMessage that = (DraftMessage) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
