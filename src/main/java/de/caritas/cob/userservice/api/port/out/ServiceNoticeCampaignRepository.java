package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceNoticeCampaignRepository
    extends JpaRepository<ServiceNoticeCampaign, Long> {
  Optional<ServiceNoticeCampaign> findByCampaignKey(String campaignKey);

  /** Serializes two confirmations of the same campaign, e.g. a double click or two replicas. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ServiceNoticeCampaign c where c.campaignKey = :campaignKey")
  Optional<ServiceNoticeCampaign> findByCampaignKeyForUpdate(
      @Param("campaignKey") String campaignKey);
}
