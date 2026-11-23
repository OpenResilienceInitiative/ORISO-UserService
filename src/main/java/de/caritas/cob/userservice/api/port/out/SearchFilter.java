package de.caritas.cob.userservice.api.port.out;

import java.util.Collection;
import java.util.List;

/**
 * Optional Träger (tenant) and Beratungsstelle (agency) filters of the admin/consultant infix
 * searches. A filter only ever narrows the caller's own scope, it never widens it.
 *
 * @param tenantId only rows of this tenant, or {@code null} for no tenant filter
 * @param agencyIds only rows of any of these agencies, or {@code null} for no agency filter
 */
public record SearchFilter(Long tenantId, List<Long> agencyIds) {

  public static final SearchFilter NONE = new SearchFilter(null, null);

  public SearchFilter {
    agencyIds = agencyIds == null || agencyIds.isEmpty() ? null : List.copyOf(agencyIds);
  }

  public boolean hasAgencyIds() {
    return agencyIds != null;
  }

  /** A requested tenant other than the caller's own lies outside the caller's scope. */
  public boolean isTenantOutside(Long callerTenantId) {
    return tenantId != null && !tenantId.equals(callerTenantId);
  }

  /** The caller's agencies narrowed to the requested ones; all of them when none are requested. */
  public List<Long> narrowAgencies(Collection<Long> callerAgencyIds) {
    var callerAgencies = callerAgencyIds == null ? List.<Long>of() : List.copyOf(callerAgencyIds);
    return hasAgencyIds()
        ? callerAgencies.stream().filter(agencyIds::contains).toList()
        : callerAgencies;
  }
}
