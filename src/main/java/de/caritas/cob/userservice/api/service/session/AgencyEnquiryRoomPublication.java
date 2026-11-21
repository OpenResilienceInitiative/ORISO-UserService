package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.InitialRoomContact;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes the holding-room binding together with any initial roster corrections. */
@Service
@RequiredArgsConstructor
public class AgencyEnquiryRoomPublication {

  private final ConsultantRepository consultants;
  private final SessionRepository sessions;
  private final SessionService sessionService;
  private final AgencyEnquiryRevocations revocations;
  private final AgencyEnquiryRoomAttempts roomAttempts;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<String> publish(
      Session expected, String roomId, List<InitialRoomContact> contacts) {
    var ordered =
        contacts.stream()
            .distinct()
            .sorted(Comparator.comparing(InitialRoomContact::consultantId))
            .toList();
    // A detach that already won this lock is reconciled below. A later detach sees the committed
    // binding in its current room read. No Matrix operation runs in this transaction.
    ordered.stream()
        .map(InitialRoomContact::consultantId)
        .distinct()
        .forEach(consultants::lockConsultant);

    var current = sessions.lockFresh(expected.getId()).orElse(null);
    if (current == null
        || !Objects.equals(current.getAgencyId(), expected.getAgencyId())
        || !Objects.equals(current.getTenantId(), expected.getTenantId())
        || current.getStatus() != expected.getStatus()
        || current.getOwnershipRevision() != expected.getOwnershipRevision()
        || Boolean.TRUE.equals(current.getIsConsultantDirectlySet())
            != Boolean.TRUE.equals(expected.getIsConsultantDirectlySet())) {
      return Optional.empty();
    }
    if (isNotBlank(current.getMatrixRoomId())) {
      if (roomId.equals(current.getMatrixRoomId())) {
        roomAttempts.completePublication(current);
      }
      return Optional.of(current.getMatrixRoomId());
    }

    current.setMatrixRoomId(roomId);
    if (!ordered.isEmpty()) {
      revocations.stageDuringPublication(current, ordered);
    }
    sessionService.saveSession(current);
    roomAttempts.completePublication(current);
    return Optional.of(roomId);
  }
}
