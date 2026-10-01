package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface ServiceNoticeRecipientRepository
    extends CrudRepository<ServiceNoticeRecipient, Long> {

  List<ServiceNoticeRecipient> findByCampaignIdOrderById(Long campaignId);
}
