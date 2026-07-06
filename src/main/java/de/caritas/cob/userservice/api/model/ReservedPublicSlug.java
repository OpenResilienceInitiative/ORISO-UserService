package de.caritas.cob.userservice.api.model;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
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

  @Column(name = "active", nullable = false, columnDefinition = "tinyint default 1")
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
