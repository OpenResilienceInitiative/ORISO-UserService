package de.caritas.cob.userservice.api.admin.service.search;

import de.caritas.cob.userservice.api.model.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Scalar candidates and paged rows share exactly the same eligibility specification. */
@Component
@RequiredArgsConstructor
public class AccountSearchStore {
  private final EntityManager entityManager;

  public <T> Set<Long> tenantIds(Class<T> type, Specification<T> scope) {
    var b = entityManager.getCriteriaBuilder();
    var q = b.createQuery(Long.class);
    var r = q.from(type);
    q.select(r.get("tenantId")).distinct(true).where(scope.toPredicate(r, q, b));
    var ids = new HashSet<>(entityManager.createQuery(q).getResultList());
    ids.remove(null);
    ids.remove(0L);
    return ids;
  }

  public <T> Set<Long> agencyIds(
      Class<T> type,
      Specification<T> scope,
      boolean consultant,
      Collection<Long> allowed,
      Long tenant) {
    var b = entityManager.getCriteriaBuilder();
    var q = b.createQuery(Long.class);
    var account = q.from(type);
    Class<?> relationType = consultant ? ConsultantAgency.class : AdminAgency.class;
    var relation = q.from(relationType);
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(scope.toPredicate(account, q, b));
    predicates.add(
        b.equal(relation.get(consultant ? "consultant" : "admin").get("id"), account.get("id")));
    if (consultant) {
      predicates.add(b.isNull(relation.get("deleteDate")));
      if (tenant != null && tenant != 0) predicates.add(b.equal(relation.get("tenantId"), tenant));
    }
    if (allowed != null)
      predicates.add(allowed.isEmpty() ? b.disjunction() : relation.get("agencyId").in(allowed));
    q.select(relation.get("agencyId")).distinct(true).where(predicates.toArray(Predicate[]::new));
    var ids = new HashSet<>(entityManager.createQuery(q).getResultList());
    ids.remove(null);
    return ids;
  }

  public <T> Page<T> page(Class<T> type, Specification<T> specification, Pageable pageable) {
    var b = entityManager.getCriteriaBuilder();
    var count = b.createQuery(Long.class);
    var counted = count.from(type);
    count.select(b.count(counted)).where(specification.toPredicate(counted, count, b));
    long total = entityManager.createQuery(count).getSingleResult();
    if (total == 0) return Page.empty(pageable);
    var q = b.createQuery(type);
    var r = q.from(type);
    q.select(r).where(specification.toPredicate(r, q, b));
    List<Order> orders = new ArrayList<>();
    boolean idSorted = false;
    for (var sort : pageable.getSort()) {
      Expression<?> field =
          sort.getProperty().equals("lastUpdated")
              ? b.coalesce(r.get("updateDate"), r.get("createDate"))
              : r.get(sort.getProperty());
      orders.add(sort.isAscending() ? b.asc(field) : b.desc(field));
      idSorted |= sort.getProperty().equals("id");
    }
    if (!idSorted) orders.add(b.asc(r.get("id")));
    q.orderBy(orders);
    var selected = entityManager.createQuery(q);
    selected.setFirstResult(Math.toIntExact(pageable.getOffset()));
    selected.setMaxResults(pageable.getPageSize());
    return new PageImpl<>(selected.getResultList(), pageable, total);
  }
}
