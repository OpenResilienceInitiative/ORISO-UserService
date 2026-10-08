package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Last Matrix batch whose reply-email claims were durably recorded. */
@Entity
@Table(name = "matrix_email_sync_cursor")
@Getter
@Setter
@NoArgsConstructor
public class MatrixEmailSyncCursor {
  @Id private Long id;

  @Column(name = "batch_token", columnDefinition = "TEXT")
  private String batchToken;

  @Column(name = "activation_epoch_millis", nullable = false)
  private long activationEpochMillis;
}
