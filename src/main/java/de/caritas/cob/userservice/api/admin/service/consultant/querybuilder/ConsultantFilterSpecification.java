package de.caritas.cob.userservice.api.admin.service.consultant.querybuilder;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/** Builds a JPA {@link Specification} for filtering {@link Consultant} entities. */
public class ConsultantFilterSpecification {

  private ConsultantFilterSpecification() {}

  public static Specification<Consultant> of(ConsultantFilter filter) {
    return (root, query, cb) -> {
      var predicates = new ArrayList<Predicate>();
      predicates.add(cb.isNull(root.get("deleteDate")));
      if (nonNull(filter)) {
        if (isNotBlank(filter.getUsername())) {
          predicates.add(
              cb.like(cb.lower(root.<String>get("username")), contains(filter.getUsername())));
        }
        if (isNotBlank(filter.getLastname())) {
          predicates.add(
              cb.like(cb.lower(root.<String>get("lastName")), contains(filter.getLastname())));
        }
        if (isNotBlank(filter.getEmail())) {
          predicates.add(cb.like(cb.lower(root.<String>get("email")), contains(filter.getEmail())));
        }
        if (nonNull(filter.getAbsent())) {
          predicates.add(cb.equal(root.get("absent"), filter.getAbsent()));
        }
        if (nonNull(filter.getAgencyId())) {
          var agencyJoin = root.join("consultantAgencies", JoinType.INNER);
          predicates.add(cb.equal(agencyJoin.get("agencyId"), filter.getAgencyId()));
          predicates.add(cb.isNull(agencyJoin.get("deleteDate")));
          query.distinct(true);
        }
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * Consultants with an active (not deleted) relation to at least one of the given agencies.
   *
   * @param agencyIds the agencies; an empty set matches no consultant
   * @return the consultant {@link Specification}
   */
  public static Specification<Consultant> withActiveRelationToAnyOf(Set<Long> agencyIds) {
    return (root, query, cb) -> {
      if (agencyIds == null || agencyIds.isEmpty()) {
        return cb.disjunction();
      }
      var relation = query.subquery(String.class);
      var consultantAgency = relation.from(ConsultantAgency.class);
      relation
          .select(consultantAgency.get("consultant").get("id"))
          .where(
              consultantAgency.get("agencyId").in(agencyIds),
              cb.isNull(consultantAgency.get("deleteDate")));
      return root.get("id").in(relation);
    };
  }

  private static String contains(String value) {
    return "%" + value.toLowerCase() + "%";
  }
}
