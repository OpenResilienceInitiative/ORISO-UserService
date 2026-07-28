package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the Matrix rooms an application account has actually joined.
 *
 * <p>Session-list callers use this only for structural membership flags. Message content and read
 * state stay with the crypto-enabled Matrix client in the frontend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixRoomMembershipProvider {

  private final MatrixSynapseService matrixSynapseService;
  private final ConsultantRepository consultantRepository;
  private final UserRepository userRepository;

  public Set<String> joinedRoomsForConsultant(Consultant consultant) {
    return consultant == null ? Set.of() : joinedRoomsForMatrixUser(consultant.getMatrixUserId());
  }

  public Set<String> joinedRoomsForAccount(String accountId) {
    if (isBlank(accountId)) {
      return Set.of();
    }

    var consultant = consultantRepository.findById(accountId);
    if (consultant.isPresent()) {
      return joinedRoomsForMatrixUser(consultant.get().getMatrixUserId());
    }

    return userRepository
        .findById(accountId)
        .map(user -> joinedRoomsForMatrixUser(user.getMatrixUserId()))
        .orElseGet(Set::of);
  }

  private Set<String> joinedRoomsForMatrixUser(String matrixUserId) {
    if (isBlank(matrixUserId)) {
      return Set.of();
    }

    try {
      return Set.copyOf(matrixSynapseService.getJoinedRoomsForMatrixUser(matrixUserId));
    } catch (Exception e) {
      log.warn("Could not load joined Matrix rooms for {}: {}", matrixUserId, e.getMessage());
      return Set.of();
    }
  }
}
