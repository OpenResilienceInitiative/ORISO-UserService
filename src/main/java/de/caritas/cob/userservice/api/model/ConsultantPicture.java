package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/** Internal, consultant-owned bytes. Never attached to public or list DTOs. */
@Entity
@Table(name = "consultant_picture")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultantPicture {
  @Id
  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Lob
  @Column(name = "image_bytes", nullable = false, columnDefinition = "MEDIUMBLOB")
  private byte[] bytes;

  @Column(name = "content_type", nullable = false, length = 10)
  private String contentType;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public ConsultantPicture(String id, byte[] bytes, String contentType) {
    this.consultantId = id;
    this.bytes = bytes;
    this.contentType = contentType;
    this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
  }
}
