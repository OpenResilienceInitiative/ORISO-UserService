package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceNoticeCampaignRepository
    extends JpaRepository<ServiceNoticeCampaign, Long> {
  Optional<ServiceNoticeCampaign> findByCampaignKey(String campaignKey);
}
