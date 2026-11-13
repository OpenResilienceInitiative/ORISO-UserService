package de.caritas.cob.userservice.api.admin.service.asker;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/** Which advice seekers an admin search returns. */
public final class AskerAdminSpecification {

  private AskerAdminSpecification() {}

  /**
   * @param usernames stored forms of the searched username (plain and encoded); empty = any
   * @param agencyId only advice seekers of this agency; {@code null} = any
   * @param tenantId only advice seekers of this Träger; {@code null} = any
   */
  public static Specification<User> matching(
      Collection<String> usernames, Long agencyId, Long tenantId) {
    return (root, query, cb) -> {
      var predicates = new ArrayList<Predicate>();
      if (usernames != null && !usernames.isEmpty()) {
        predicates.add(root.get("username").in(usernames));
      }
      if (tenantId != null) {
        predicates.add(cb.equal(root.get("tenantId"), tenantId));
      }
      if (agencyId != null) {
        predicates.add(inAgencies(root, query, cb, Set.of(agencyId)));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  /**
   * An advice seeker belongs to an agency through a session (enquiry) there or a membership, the
   * same rule {@code AdminScope} uses for a single advice seeker.
   */
  public static Predicate inAgencies(
      Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb, Set<Long> agencyIds) {
    var sessions = query.subquery(String.class);
    var session = sessions.from(Session.class);
    sessions.select(session.get("user").get("userId")).where(session.get("agencyId").in(agencyIds));

    var memberships = query.subquery(String.class);
    var membership = memberships.from(UserAgency.class);
    memberships
        .select(membership.get("user").get("userId"))
        .where(membership.get("agencyId").in(agencyIds));

    return cb.or(root.get("userId").in(sessions), root.get("userId").in(memberships));
  }
}
