package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.Objects;
import java.util.Optional;

/**
 * FE#2209 round 10 (F1): consultant.matrix_user_id is not unique, so the single-row lookup throws
 * once two not deleted accounts share a Matrix id, and a call-room pass that throws held up the
 * whole Matrix sync. The call code reads every row and fails closed: a Matrix id stands for a
 * counsellor of a Träger only when exactly one not deleted account of that Träger holds it. An
 * ambiguous id is treated as no counsellor, so it never grants authority or counts as presence.
 */
final class SoleCounsellor {
  private SoleCounsellor() {}

  static Optional<Consultant> of(
      ConsultantRepository consultants, String matrixUserId, Long tenantId) {
    if (matrixUserId == null || matrixUserId.isBlank()) return Optional.empty();
    var matches =
        consultants.findAllByMatrixUserIdAndDeleteDateIsNull(matrixUserId).stream()
            .filter(Objects::nonNull)
            .filter(consultant -> Objects.equals(consultant.getTenantId(), tenantId))
            .limit(2)
            .toList();
    return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
  }
}
