package de.caritas.cob.userservice.api.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

@Entity
@Table(name = "session_data")
@Getter
@Setter
@NoArgsConstructor
public class SessionData {

  @AllArgsConstructor
  @Getter
  @JsonFormat(shape = JsonFormat.Shape.NUMBER)
  public enum SessionDataType {
    REGISTRATION(0);
    private final int value;
  }

  public SessionData(
      @NonNull Session session,
      @NonNull SessionDataType sessionDataType,
      @NonNull String key,
      String value) {
    this.session = session;
    this.sessionDataType = sessionDataType;
    this.key = key;
    this.value = value;
  }

  @Id
  @SequenceGenerator(name = "id_seq", allocationSize = 1, sequenceName = "sequence_session_data")
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "id_seq")
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "session_id", nullable = false)
  @NonNull
  private Session session;

  @Column(name = "type", updatable = false, nullable = false, columnDefinition = "tinyint(4)")
  @NonNull
  private SessionDataType sessionDataType;

  @Column(name = "key_name")
  @NonNull
  private String key;

  @Column(name = "value")
  @Size(max = 255)
  private String value;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SessionData)) {
      return false;
    }
    SessionData that = (SessionData) o;
    return id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
