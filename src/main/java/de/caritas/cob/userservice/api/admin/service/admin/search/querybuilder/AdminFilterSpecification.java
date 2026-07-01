package de.caritas.cob.userservice.api.admin.service.admin.search.querybuilder;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminFilter;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import org.springframework.data.jpa.domain.Specification;

/** Builds a JPA {@link Specification} for filtering {@link Admin} entities. */
public class AdminFilterSpecification {

  private AdminFilterSpecification() {}

  public static Specification<Admin> of(AdminFilter filter) {
    return (root, query, cb) -> {
      var predicates = new ArrayList<Predicate>();
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
        if (nonNull(filter.getAgencyId())) {
          var adminAgency = query.subquery(String.class);
          var adminAgencyRoot = adminAgency.from(AdminAgency.class);
          adminAgency
              .select(adminAgencyRoot.get("admin").get("id"))
              .where(cb.equal(adminAgencyRoot.get("agencyId"), filter.getAgencyId()));
          predicates.add(root.get("id").in(adminAgency));
        }
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  private static String contains(String value) {
    return "%" + value.toLowerCase() + "%";
  }
}
