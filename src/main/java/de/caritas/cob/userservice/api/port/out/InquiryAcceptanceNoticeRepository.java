package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice.DeliveryState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface InquiryAcceptanceNoticeRepository
    extends CrudRepository<InquiryAcceptanceNotice, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select notice from InquiryAcceptanceNotice notice where notice.sessionId = :id")
  Optional<InquiryAcceptanceNotice> findByIdForUpdate(@Param("id") Long id);

  List<InquiryAcceptanceNotice>
      findTop100ByDeliveryStateAndNextAttemptAtUtcLessThanEqualOrderByNextAttemptAtUtcAscSessionIdAsc(
          DeliveryState state, java.time.LocalDateTime dueBy);
}
