package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Server-owned room provenance; a room name, invitation or assignment is not this evidence. */
@Entity
@Table(
    name = "chat_matrix_room",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_chat_matrix_room_occurrence",
            columnNames = {"series_id", "occurrence_index"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMatrixRoom {
  @Id
  @Column(name = "room_id", length = 512, nullable = false)
  private String roomId;

  @ManyToOne(optional = false)
  @JoinColumn(name = "series_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Chat series;

  @Column(name = "occurrence_index", nullable = false)
  private int occurrenceIndex;

  @Column(name = "owner_tenant_id", nullable = false)
  private Long ownerTenantId;
}
