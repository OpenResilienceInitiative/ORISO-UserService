package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "reserved_public_slug")
@Getter
@Setter
public class ReservedPublicSlug {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "slug", nullable = false, unique = true, length = 128)
  private String slug;

  @Column(name = "reason", length = 512)
  private String reason;

  @Column(name = "active", nullable = false, columnDefinition = "tinyint")
  private boolean active = true;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at", columnDefinition = "datetime")
  private LocalDateTime createdAt;

  @Column(name = "updated_by", length = 64)
  private String updatedBy;

  @Column(name = "updated_at", columnDefinition = "datetime")
  private LocalDateTime updatedAt;
}
