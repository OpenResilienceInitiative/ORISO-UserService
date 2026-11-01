package de.caritas.cob.userservice.api.admin.service.search;

import de.caritas.cob.userservice.api.model.*;
import jakarta.persistence.criteria.*;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;

public final class AccountSearchSpecification {
  private AccountSearchSpecification() {}

  public static Specification<Consultant> consultants(Long tenant, Collection<Long> agencies) {
    return (r, q, b) ->
        b.and(
            b.isNull(r.get("deleteDate")),
            tenant == null || tenant == 0 ? b.conjunction() : b.equal(r.get("tenantId"), tenant),
            agencies == null ? b.conjunction() : relation(r, q, b, true, agencies, tenant));
  }

  public static Specification<Admin> admins(
      Admin.AdminType type, Long tenant, Collection<Long> agencies) {
    return (r, q, b) ->
        b.and(
            b.equal(r.get("type"), type),
            tenant == null ? b.conjunction() : b.equal(r.get("tenantId"), tenant),
            agencies == null ? b.conjunction() : relation(r, q, b, false, agencies, tenant));
  }

  public static <T> Specification<T> matching(
      AdminSearchQuery search,
      Map<String, AccountSearchNames.Matches> names,
      boolean consultant,
      Collection<Long> visibleAgencies,
      Long tenant) {
    return (r, q, b) -> {
      List<Predicate> words = new ArrayList<>();
      for (String token : search.tokens()) {
        List<Predicate> fields = new ArrayList<>();
        fields.add(b.equal(b.lower(r.get("id")), token));
        for (String field : List.of("firstName", "lastName", "email"))
          fields.add(contains(b, r.get(field), token));
        if (consultant) fields.add(contains(b, r.get("internalDisplayName"), token));
        else fields.add(contains(b, r.get("tenantId").as(String.class), token));
        var ids = names.get(token);
        if (ids != null) {
          if (!consultant && !ids.tenants().isEmpty())
            fields.add(r.get("tenantId").in(ids.tenants()));
          Set<Long> agencyIds = new HashSet<>(ids.agencies());
          if (visibleAgencies != null) agencyIds.retainAll(visibleAgencies);
          if (consultant && !agencyIds.isEmpty())
            fields.add(relation(r, q, b, consultant, agencyIds, tenant));
        }
        words.add(b.or(fields.toArray(Predicate[]::new)));
      }
      return b.and(words.toArray(Predicate[]::new));
    };
  }

  static Predicate contains(CriteriaBuilder b, Expression<String> field, String token) {
    return b.like(
        b.function("admin_search_text", String.class, field), AdminSearchQuery.pattern(token), '!');
  }

  static Predicate relation(
      Root<?> account,
      CriteriaQuery<?> query,
      CriteriaBuilder b,
      boolean consultant,
      Collection<Long> agencies,
      Long tenant) {
    if (agencies.isEmpty()) return b.disjunction();
    var sub = query.subquery(Long.class);
    Class<?> relationType = consultant ? ConsultantAgency.class : AdminAgency.class;
    var relation = sub.from(relationType);
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(
        b.equal(relation.get(consultant ? "consultant" : "admin").get("id"), account.get("id")));
    predicates.add(relation.get("agencyId").in(agencies));
    if (consultant) {
      predicates.add(b.isNull(relation.get("deleteDate")));
      if (tenant != null && tenant != 0) predicates.add(b.equal(relation.get("tenantId"), tenant));
    }
    sub.select(relation.get("id")).where(predicates.toArray(Predicate[]::new));
    return b.exists(sub);
  }
}
