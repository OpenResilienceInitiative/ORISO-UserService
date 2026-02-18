package de.caritas.cob.userservice.api.model;

import java.time.LocalDateTime;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a consultant supervising a session. Supervisors can observe the chat but cannot send
 * messages to the asker.
 */
@Entity
@Table(name = "session_supervisor")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class SessionSupervisor {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "session_id", nullable = false)
  private Session session;

  @ManyToOne
  @JoinColumn(name = "supervisor_consultant_id", nullable = false)
  private Consultant supervisorConsultant;

  @ManyToOne
  @JoinColumn(name = "added_by_consultant_id", nullable = false)
  private Consultant addedByConsultant;

  @Column(name = "added_date", nullable = false)
  private LocalDateTime addedDate;

  @Column(name = "removed_date")
  private LocalDateTime removedDate;

  @Column(name = "is_active", nullable = false, columnDefinition = "tinyint(4) default 1")
  private Boolean isActive;

  @Column(name = "matrix_room_id")
  private String matrixRoomId;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SessionSupervisor)) {
      return false;
    }
    SessionSupervisor that = (SessionSupervisor) o;
    return id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}

