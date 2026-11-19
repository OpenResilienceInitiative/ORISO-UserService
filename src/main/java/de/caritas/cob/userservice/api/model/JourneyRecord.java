package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One stored journey (ADR-036 part E). {@code scope} names the level: {@link #PLATFORM} (scope id
 * 0) holds a whole journey; {@link #TENANT} and {@link #AGENCY} hold the layer a Träger or a
 * Beratungsstelle put on the platform journey with the same id (decision 37). {@code body} is the
 * JSON the Admin edits; {@code version} is raised on every write and checked against the version
 * the writer read.
 */
@Entity
@Table(name = "journey")
@IdClass(JourneyRecord.Key.class)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class JourneyRecord {

  public static final String PLATFORM = "PLATFORM";
  public static final String TENANT = "TENANT";
  public static final String AGENCY = "AGENCY";
  public static final long PLATFORM_SCOPE_ID = 0L;

  @Id
  @Column(name = "scope", length = 16, nullable = false)
  private String scope;

  @Id
  @Column(name = "scope_id", nullable = false)
  private Long scopeId;

  @Id
  @Column(name = "journey_id", length = 64, nullable = false)
  private String journeyId;

  @Column(name = "conversation_type", length = 32, nullable = false)
  private String conversationType;

  @Column(name = "body", columnDefinition = "longtext", nullable = false)
  private String body;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "updated_by", length = 255)
  private String updatedBy;

  @LastModifiedDate
  @Column(name = "update_date", columnDefinition = "datetime")
  private LocalDateTime updateDate;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {
    private String scope;
    private Long scopeId;
    private String journeyId;
  }
}
