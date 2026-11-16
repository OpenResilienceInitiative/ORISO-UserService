package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

  /**
   * Whether {@code matrixUserId} stands for exactly the consultant {@code consultantId} of {@code
   * tenantId} (CodeRabbit 4585785683): the same rule as {@link #of}, so a counsellor whose Matrix
   * id is shared with another not deleted account of the Träger vouches for nobody.
   */
  static boolean is(
      ConsultantRepository consultants, String matrixUserId, Long tenantId, String consultantId) {
    return consultantId != null
        && of(consultants, matrixUserId, tenantId)
            .map(Consultant::getId)
            .filter(consultantId::equals)
            .isPresent();
  }

  /**
   * {@link #of} for many Matrix ids in one query (CodeRabbit 4585785733): the sole counsellor of
   * {@code tenantId} per id, in the order of {@code matrixUserIds}. Ids held by no or by more than
   * one not deleted account of the Träger are left out (fail closed).
   */
  static Map<String, Consultant> allOf(
      ConsultantRepository consultants, Collection<String> matrixUserIds, Long tenantId) {
    var wanted = new LinkedHashSet<String>();
    for (String id : matrixUserIds) if (id != null && !id.isBlank()) wanted.add(id);
    var result = new LinkedHashMap<String, Consultant>();
    if (wanted.isEmpty() || tenantId == null) return result;
    var byId = new HashMap<String, List<Consultant>>();
    for (var consultant : consultants.findAllByMatrixUserIdInAndDeleteDateIsNull(wanted)) {
      if (consultant == null
          || !Objects.equals(consultant.getTenantId(), tenantId)
          || !wanted.contains(consultant.getMatrixUserId())) continue;
      byId.computeIfAbsent(consultant.getMatrixUserId(), key -> new ArrayList<>()).add(consultant);
    }
    for (String id : wanted) {
      var matches = byId.get(id);
      if (matches != null && matches.size() == 1) result.put(id, matches.get(0));
    }
    return result;
  }
}
