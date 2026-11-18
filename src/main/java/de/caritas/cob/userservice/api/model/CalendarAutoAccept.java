package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One colleague whose appointments a counsellor accepts automatically (ORISO-Frontend#2305): an
 * appointment from {@code colleagueId} to {@code consultantId} is fixed at once.
 */
@Entity
@Table(
    name = "calendar_auto_accept",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_calendar_auto_accept",
            columnNames = {"consultant_id", "colleague_id"}))
@Getter
@Setter
@NoArgsConstructor
public class CalendarAutoAccept {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Column(name = "colleague_id", length = 36, nullable = false)
  private String colleagueId;
}
