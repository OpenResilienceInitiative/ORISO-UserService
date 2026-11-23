package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgencyEnquiryRoomAttemptRepository
    extends JpaRepository<AgencyEnquiryRoomAttempt, Long> {

  List<AgencyEnquiryRoomAttempt> findAllBySessionIdAndRoomId(Long sessionId, String roomId);
}
