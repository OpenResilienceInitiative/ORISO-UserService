package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient.MailStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ServiceNoticeRecipientRepository
    extends CrudRepository<ServiceNoticeRecipient, Long> {

  List<ServiceNoticeRecipient> findByCampaignIdOrderById(Long campaignId);

  /** Due mail of confirmed campaigns only, oldest first; the page size is the rate limit. */
  @Query(
      "select r from ServiceNoticeRecipient r where r.mailStatus = :pending "
          + "and r.nextAttemptAtUtc <= :now and r.campaignId in "
          + "(select c.id from ServiceNoticeCampaign c where c.status = 'CONFIRMED') "
          + "order by r.id")
  List<ServiceNoticeRecipient> findDue(
      @Param("pending") MailStatus pending, @Param("now") LocalDateTime now, Pageable page);

  @Modifying
  @Query(
      "update ServiceNoticeRecipient r set r.mailStatus = :claimed, r.claimedAt = :claimedAt "
          + "where r.id = :id and r.mailStatus = :pending")
  int claim(
      @Param("id") Long id,
      @Param("pending") MailStatus pending,
      @Param("claimed") MailStatus claimed,
      @Param("claimedAt") LocalDateTime claimedAt);
}
