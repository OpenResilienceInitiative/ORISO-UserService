package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseHandoverRequestRepository extends JpaRepository<CaseHandoverRequest, Long> {

  List<CaseHandoverRequest> findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
      Long sessionId, String requesterConsultantId);

  List<CaseHandoverRequest> findBySessionIdAndStatusOrderByCreatedAtDesc(
      Long sessionId, CaseHandoverRequest.Status status);

  Optional<CaseHandoverRequest> findByIdAndSessionId(Long id, Long sessionId);
}
