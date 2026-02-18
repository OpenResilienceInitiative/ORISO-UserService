package de.caritas.cob.userservice.api.model;

import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Represents the relation between consultant and mobile token. */
@Entity
@Table(name = "consultant_mobile_token")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ConsultantMobileToken {

  @Id
  @SequenceGenerator(
      name = "id_seq",
      allocationSize = 1,
      sequenceName = "sequence_consultant_mobile_token")
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "id_seq")
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "consultant_id", nullable = false)
  private Consultant consultant;

  @Column(name = "mobile_app_token", nullable = false)
  @Lob
  private String mobileAppToken;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ConsultantMobileToken)) {
      return false;
    }
    ConsultantMobileToken that = (ConsultantMobileToken) o;
    return Objects.equals(mobileAppToken, that.mobileAppToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mobileAppToken);
  }
}
