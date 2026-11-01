package de.caritas.cob.userservice.api.admin.service.search;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import jakarta.persistence.criteria.Predicate;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;

public final class InviteSearchSpecification {
  private InviteSearchSpecification() {}

  public static Specification<AccountInvite> matching(
      Long tenant,
      AccountInviteTargetRole role,
      AccountInviteStatus status,
      String raw,
      Collection<Long> agencies) {
    var search = AdminSearchQuery.parse(raw);
    return (r, q, b) -> {
      List<Predicate> all = new ArrayList<>();
      if (tenant != null) all.add(b.equal(r.get("tenantId"), tenant));
      if (role != null) all.add(b.equal(r.get("targetRole"), role));
      if (status != null) all.add(b.equal(r.get("status"), status));
      if (agencies != null)
        all.add(agencies.isEmpty() ? b.disjunction() : r.get("agencyId").in(agencies));
      for (String token : search.tokens()) {
        List<Predicate> fields = new ArrayList<>();
        for (String field : List.of("recipientEmail", "firstName", "lastName"))
          fields.add(AccountSearchSpecification.contains(b, r.get(field), token));
        if (token.matches("[0-9]+"))
          try {
            fields.add(b.equal(r.get("tenantId"), Long.parseLong(token)));
          } catch (NumberFormatException ignored) {
            /* Oversized numeric text still matches names/email. */
          }
        all.add(b.or(fields.toArray(Predicate[]::new)));
      }
      q.orderBy(b.desc(r.get("createDate")), b.desc(r.get("id")));
      return b.and(all.toArray(Predicate[]::new));
    };
  }
}
