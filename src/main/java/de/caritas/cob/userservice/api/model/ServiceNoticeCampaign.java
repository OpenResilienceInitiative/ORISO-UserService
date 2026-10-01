package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A platform operator's planned-notice draft. No recipient or delivery is represented here. */
@Entity
@Table(
    name = "service_notice_campaign",
    uniqueConstraints =
        @UniqueConstraint(name = "uq_service_notice_campaign_key", columnNames = "campaign_key"))
@Getter
@Setter
@NoArgsConstructor
public class ServiceNoticeCampaign {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "campaign_key", nullable = false, length = 80)
  private String campaignKey;

  @Column(name = "status", nullable = false, length = 16)
  private String status = "DRAFT";

  @Column(name = "maintenance_date", nullable = false)
  private LocalDate maintenanceDate;

  @Column(name = "maintenance_start", nullable = false)
  private LocalTime maintenanceStart;

  @Column(name = "maintenance_end", nullable = false)
  private LocalTime maintenanceEnd;

  @Column(name = "status_url", nullable = false, length = 2048)
  private String statusUrl;

  @Column(name = "created_by_user_id", nullable = false, length = 100)
  private String createdByUserId;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}
