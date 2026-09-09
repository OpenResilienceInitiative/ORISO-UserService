package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One join/leave interval. Rejoining creates another row instead of erasing prior attendance. */
@Entity
@Table(name = "call_attendance_interval")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallAttendanceInterval {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "call_lifecycle_id", nullable = false)
  private CallLifecycleProjection callLifecycle;

  @Column(name = "matrix_user_id", nullable = false, length = 255)
  private String matrixUserId;

  @Column(name = "domain_user_id", length = 64)
  private String domainUserId;

  @Column(name = "device_id", nullable = false, length = 255)
  private String deviceId;

  @Column(name = "joined_at", nullable = false, columnDefinition = "datetime(3)")
  private LocalDateTime joinedAt;

  @Column(name = "left_at", columnDefinition = "datetime(3)")
  private LocalDateTime leftAt;
}
