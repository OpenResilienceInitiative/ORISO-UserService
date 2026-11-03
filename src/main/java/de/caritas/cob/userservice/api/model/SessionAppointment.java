package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * A 1:1 appointment of one conversation (ADR-020). Owned by UserService alone, independent of the
 * legacy {@link Appointment} and of the AppointmentService. Holds times and references only.
 */
@Entity
@Table(name = "session_appointment")
@Getter
@Setter
@NoArgsConstructor
public class SessionAppointment {

  public enum Status {
    SCHEDULED,
    CANCELLED
  }

  @Id
  @GeneratedValue(generator = "UUID")
  @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "session_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_session_appointment_session"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Session session;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private Status status;
}
