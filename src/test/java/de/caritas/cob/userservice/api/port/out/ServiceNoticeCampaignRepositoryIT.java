package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class ServiceNoticeCampaignRepositoryIT {

  @Autowired private ServiceNoticeCampaignRepository campaigns;

  @Test
  void persistsAndReadsTheOnlyDraftStateWithoutRecipientsOrDeliveryFields() {
    var draft = new ServiceNoticeCampaign();
    draft.setCampaignKey("planned-outage-1");
    draft.setMaintenanceDate(LocalDate.of(2026, 10, 2));
    draft.setMaintenanceStart(LocalTime.of(14, 0));
    draft.setMaintenanceEnd(LocalTime.of(15, 0));
    draft.setStatusUrl("https://status.operator.dev/maintenance");
    draft.setCreatedByUserId("platform-operator-1");
    draft.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 0));

    campaigns.saveAndFlush(draft);

    var stored = campaigns.findByCampaignKey("planned-outage-1").orElseThrow();
    assertThat(stored.getStatus()).isEqualTo("DRAFT");
    assertThat(stored.getMaintenanceDate()).isEqualTo(draft.getMaintenanceDate());
    assertThat(stored.getMaintenanceStart()).isEqualTo(draft.getMaintenanceStart());
    assertThat(stored.getMaintenanceEnd()).isEqualTo(draft.getMaintenanceEnd());
    assertThat(stored.getStatusUrl()).isEqualTo(draft.getStatusUrl());
    assertThat(stored.getCreatedByUserId()).isEqualTo("platform-operator-1");
  }
}
