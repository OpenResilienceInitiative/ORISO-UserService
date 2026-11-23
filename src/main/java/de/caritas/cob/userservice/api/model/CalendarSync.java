package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A counsellor's calendar connections (ADR-032, ORISO-UserService#1645): the id of their
 * subscription feed and the address of their own calendar, read for busy times only.
 */
@Entity
@Table(name = "calendar_sync")
@Getter
@Setter
@NoArgsConstructor
public class CalendarSync {

  @Id
  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Column(name = "tenant_id")
  private Long tenantId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "feed_id", columnDefinition = "char(36)", nullable = false, unique = true)
  private UUID feedId;

  @Column(name = "feed_last_fetched_at")
  private Instant feedLastFetchedAt;

  /** A private address: never logged and never returned; only its host is shown. */
  @Column(name = "import_url", length = 2048)
  private String importUrl;

  @Column(name = "import_last_sync_at")
  private Instant importLastSyncAt;

  @Column(name = "import_error", length = 32)
  private String importError;
}
