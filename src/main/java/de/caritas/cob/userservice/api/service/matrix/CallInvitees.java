package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The other people of a conversation a registered call invites, from live membership, with the
 * invite path's identity rule (CodeRabbit 4585785768/4585785779): one query per table for the whole
 * room, only rows of the conversation's tenant count, and a Matrix id held by more than one not
 * deleted account of the tenant stands for nobody (SoleCounsellor; fail closed).
 */
final class CallInvitees {
  private CallInvitees() {}

  static Set<String> of(
      UserRepository users,
      ConsultantRepository consultants,
      Collection<String> members,
      String caller,
      Long tenantId) {
    var others = new LinkedHashSet<String>();
    for (String member : members) if (member != null && !member.equals(caller)) others.add(member);
    if (others.isEmpty() || tenantId == null) return Set.of();
    var askerRows = new HashMap<String, Integer>();
    for (var user : users.findAllByMatrixUserIdInAndDeleteDateIsNull(others)) {
      if (user == null
          || !Objects.equals(user.getTenantId(), tenantId)
          || !others.contains(user.getMatrixUserId())) continue;
      askerRows.merge(user.getMatrixUserId(), 1, Integer::sum);
    }
    var result = new HashSet<String>();
    askerRows.forEach(
        (matrixId, rows) -> {
          if (rows == 1) result.add(matrixId);
        });
    var rest = new LinkedHashSet<>(others);
    rest.removeAll(askerRows.keySet());
    if (!rest.isEmpty()) result.addAll(SoleCounsellor.allOf(consultants, rest, tenantId).keySet());
    return result;
  }
}
