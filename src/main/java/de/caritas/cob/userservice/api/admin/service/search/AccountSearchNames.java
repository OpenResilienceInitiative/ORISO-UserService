package de.caritas.cob.userservice.api.admin.service.search;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class AccountSearchNames {
  private final AgencyService agencies;
  private final TenantService tenants;

  public record Matches(Set<Long> agencies, Set<Long> tenants) {}

  public Map<String, Matches> resolve(
      AdminSearchQuery query, Set<Long> agencyIds, Set<Long> tenantIds) {
    if (query.tokens().isEmpty()) return Map.of();
    Map<Long, String> agencyNames = new HashMap<>();
    Map<Long, String> tenantNames = new HashMap<>();
    try {
      for (var batch : Lists.partition(new ArrayList<>(agencyIds), 100)) {
        var result = Objects.requireNonNull(agencies.getAgenciesWithoutCaching(batch));
        result.stream()
            .filter(a -> a != null && agencyIds.contains(a.getId()))
            .forEach(a -> agencyNames.put(a.getId(), AdminSearchQuery.normalize(a.getName())));
      }
      if (!tenantIds.isEmpty())
        Objects.requireNonNull(tenants.getRestrictedTenantData(tenantIds)).stream()
            .filter(t -> t != null && tenantIds.contains(t.getId()))
            .forEach(t -> tenantNames.put(t.getId(), AdminSearchQuery.normalize(t.getName())));
    } catch (RuntimeException unavailable) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Account search names unavailable", unavailable);
    }
    Map<String, Matches> matches = new HashMap<>();
    for (String token : query.tokens())
      matches.put(token, new Matches(matching(agencyNames, token), matching(tenantNames, token)));
    return Map.copyOf(matches);
  }

  private static Set<Long> matching(Map<Long, String> names, String token) {
    Set<Long> ids = new HashSet<>();
    names.forEach(
        (id, name) -> {
          if (name.contains(token)) ids.add(id);
        });
    return Set.copyOf(ids);
  }
}
