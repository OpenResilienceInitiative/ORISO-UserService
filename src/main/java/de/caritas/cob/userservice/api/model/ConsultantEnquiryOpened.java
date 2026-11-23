package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * "This counsellor has opened this waiting enquiry" (UserService#1549). Personal data: it records
 * who looked at which enquiry and when. The row goes with the session or the counsellor.
 */
@Entity
@Table(
    name = "consultant_enquiry_opened",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_consultant_enquiry_opened",
            columnNames = {"consultant_id", "session_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ConsultantEnquiryOpened {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "consultant_id", nullable = false, updatable = false, length = 36)
  private String consultantId;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "opened_at", nullable = false, updatable = false)
  private LocalDateTime openedAt;

  /** Read-only; carries the cascading foreign key into schemas Hibernate generates. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "consultant_id", insertable = false, updatable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Consultant consultant;

  /** Read-only; carries the cascading foreign key into schemas Hibernate generates. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", insertable = false, updatable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Session session;

  public ConsultantEnquiryOpened(String consultantId, Long sessionId, LocalDateTime openedAt) {
    this.consultantId = consultantId;
    this.sessionId = sessionId;
    this.openedAt = openedAt;
  }
}
