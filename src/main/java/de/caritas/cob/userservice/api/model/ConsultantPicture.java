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

  /**
   * Issue #1049: the picture is internal by default. Only an explicit publish switch on the stored
   * row lets an advice seeker retrieve the bytes. A replacement image is a new picture, so it
   * starts internal again and has to be published deliberately.
   */
  @Column(name = "internal_only", nullable = false)
  private boolean internalOnly = true;

  public ConsultantPicture(String id, byte[] bytes, String contentType) {
    this.consultantId = id;
    this.bytes = bytes;
    this.contentType = contentType;
    this.internalOnly = true;
    this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
  }

  /** Publish or withdraw immediately; nothing else about the stored image changes. */
  public void setInternalOnly(boolean internalOnly) {
    this.internalOnly = internalOnly;
    this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
  }
}
