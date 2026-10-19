package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The lock row of one reserved Träger or Beratungsstelle number; it carries no data. */
@Entity
@Table(name = "id_reservation_lock")
@IdClass(IdReservationLock.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IdReservationLock {

  @Id
  @Enumerated(EnumType.STRING)
  @Column(name = "allocation_type", nullable = false, length = 16)
  private IdReservationReleaseType allocationType;

  @Id
  @Column(name = "reserved_id", nullable = false)
  private Long reservedId;

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class Key implements Serializable {
    private IdReservationReleaseType allocationType;
    private Long reservedId;
  }
}
