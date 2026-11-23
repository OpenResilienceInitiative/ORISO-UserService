package de.caritas.cob.userservice.api.admin.service.search;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountSearch {
  private final AccountSearchStore store;
  private final AccountSearchNames names;
  private static final SpelAwareProxyProjectionFactory PROJECTIONS =
      new SpelAwareProxyProjectionFactory();

  public Page<Consultant.ConsultantBase> consultants(
      String raw, Long tenant, Collection<Long> agencies, Pageable page) {
    if (agencies != null && agencies.isEmpty()) return Page.empty(page);
    var search = AdminSearchQuery.parse(raw);
    var scope = AccountSearchSpecification.consultants(tenant, agencies);
    var matches =
        search.tokens().isEmpty()
            ? Map.<String, AccountSearchNames.Matches>of()
            : names.resolve(
                search,
                store.agencyIds(Consultant.class, scope, true, agencies, tenant),
                java.util.Set.of());
    var specification =
        scope.and(AccountSearchSpecification.matching(search, matches, true, agencies, tenant));
    return store
        .page(Consultant.class, specification, page)
        .map(c -> PROJECTIONS.createProjection(Consultant.ConsultantBase.class, c));
  }

  public Page<Admin.AdminBase> admins(
      String raw, Admin.AdminType type, Long tenant, Collection<Long> agencies, Pageable page) {
    if (agencies != null && agencies.isEmpty()) return Page.empty(page);
    var search = AdminSearchQuery.parse(raw);
    var scope = AccountSearchSpecification.admins(type, tenant, agencies);
    var matches =
        search.tokens().isEmpty()
            ? Map.<String, AccountSearchNames.Matches>of()
            : names.resolve(search, java.util.Set.of(), store.tenantIds(Admin.class, scope));
    var specification =
        scope.and(AccountSearchSpecification.matching(search, matches, false, agencies, tenant));
    return store
        .page(Admin.class, specification, page)
        .map(a -> PROJECTIONS.createProjection(Admin.AdminBase.class, a));
  }
}
