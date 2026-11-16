package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgency.ConsultantAgencyBase;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whose calendar a counsellor may look at (ADR-032). The Träger setting decides: {@code AGENCY} is
 * a colleague of the same Träger who shares a counselling centre, {@code TENANT} any colleague of
 * the same Träger, {@code PLATFORM} also a colleague of another Träger, provided that Träger opens
 * its calendars to the platform as well (the reciprocity internal group chats apply). The same rule
 * answers the list and the direct request, so nothing can be read that is not listed.
 */
@Component
@RequiredArgsConstructor
class CalendarColleagueScope {

  enum Relation {
    SAME_AGENCY,
    SAME_TENANT,
    OTHER_TENANT
  }

  /**
   * @param agencyId the centre to name next to the colleague: one shared with the caller, else the
   *     colleague's first; null when the colleague has none
   * @param showAskerUsernames the setting of the colleague's own Träger, whose askers they are
   */
  record Colleague(
      Consultant consultant,
      String name,
      Relation relation,
      Long agencyId,
      boolean showAskerUsernames) {}

  record Listing(CalendarPolicy policy, List<Colleague> colleagues) {}

  private static final int RELATION_CHUNK = 500;

  private final @NonNull ConsultantRepository consultants;
  private final @NonNull ConsultantAgencyRepository consultantAgencies;
  private final @NonNull CalendarPolicyResolver policies;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancy = true;

  /** The colleague, if the caller may look at their calendar; empty for the caller themselves. */
  Optional<Colleague> find(String callerId, String colleagueId) {
    if (colleagueId == null || colleagueId.equals(callerId)) {
      return Optional.empty();
    }
    var me = consultants.findByIdAndDeleteDateIsNull(callerId).orElse(null);
    if (me == null) {
      return Optional.empty();
    }
    // The colleague may belong to another Träger; whether that is allowed is decided below.
    var colleague =
        TenantContext.supplyAcrossTenants(
                () -> consultants.findByIdAndDeleteDateIsNull(colleagueId))
            .orElse(null);
    if (colleague == null) {
      return Optional.empty();
    }
    var policy = policies.resolve(me.getTenantId());
    return Optional.ofNullable(
        colleague(me, agencyIdsOf(me), policy, colleague, agencyIdsOf(colleague), new HashMap<>()));
  }

  /** Everyone whose calendar the caller may look at, by name, at most {@code limit}. */
  Listing list(String callerId, String search, int limit) {
    var me = consultants.findByIdAndDeleteDateIsNull(callerId).orElse(null);
    if (me == null) {
      return new Listing(CalendarPolicy.CLOSED, List.of());
    }
    var policy = policies.resolve(me.getTenantId());
    var myAgencies = agencyIdsOf(me);
    var needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
    var candidates = new LinkedHashMap<String, Consultant>();
    for (var candidate : candidates(me, myAgencies, policy.visibilityScope())) {
      if (!candidate.getId().equals(me.getId())
          && nameOf(candidate).toLowerCase(Locale.ROOT).contains(needle)) {
        candidates.putIfAbsent(candidate.getId(), candidate);
      }
    }
    var agenciesByConsultant = activeAgencies(List.copyOf(candidates.keySet()));
    var policiesByTenant = new HashMap<Long, CalendarPolicy>();
    var colleagues =
        candidates.values().stream()
            .map(
                candidate ->
                    colleague(
                        me,
                        myAgencies,
                        policy,
                        candidate,
                        agenciesByConsultant.getOrDefault(candidate.getId(), Set.of()),
                        policiesByTenant))
            .filter(Objects::nonNull)
            .sorted(
                Comparator.comparing(Colleague::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(found -> found.consultant().getId()))
            .limit(limit)
            .toList();
    return new Listing(policy, colleagues);
  }

  /** A superset of the colleagues in scope; {@link #colleague} makes the decision for each. */
  private List<Consultant> candidates(
      Consultant me, Set<Long> myAgencies, CalendarVisibilityScope scope) {
    return switch (scope) {
      case AGENCY ->
          myAgencies.isEmpty()
              ? List.of()
              : consultants.findByConsultantAgenciesAgencyIdInAndDeleteDateIsNull(
                  List.copyOf(myAgencies));
      case TENANT ->
          me.getTenantId() == null
              ? (multitenancy ? List.of() : consultants.findByDeleteDateIsNull())
              : consultants.findByTenantIdAndDeleteDateIsNullOrderByFirstNameAscLastNameAsc(
                  me.getTenantId());
      case PLATFORM -> TenantContext.supplyAcrossTenants(consultants::findByDeleteDateIsNull);
    };
  }

  /** The one rule: null when {@code colleague} is outside the caller's scope. */
  private Colleague colleague(
      Consultant me,
      Set<Long> myAgencies,
      CalendarPolicy policy,
      Consultant colleague,
      Set<Long> theirAgencies,
      Map<Long, CalendarPolicy> policiesByTenant) {
    var sameTenant = sameTenant(me, colleague);
    var shared = new TreeSet<>(myAgencies);
    shared.retainAll(theirAgencies);
    // Only a colleague of another Träger under PLATFORM needs that Träger's own setting.
    var theirPolicy =
        sameTenant || policy.visibilityScope() != CalendarVisibilityScope.PLATFORM
            ? policy
            : policiesByTenant.computeIfAbsent(colleague.getTenantId(), policies::resolve);
    var inScope =
        switch (policy.visibilityScope()) {
          case AGENCY -> sameTenant && !shared.isEmpty();
          case TENANT -> sameTenant;
          case PLATFORM ->
              sameTenant || theirPolicy.visibilityScope() == CalendarVisibilityScope.PLATFORM;
        };
    if (!inScope) {
      return null;
    }
    Relation relation;
    Long agencyId;
    if (sameTenant && !shared.isEmpty()) {
      relation = Relation.SAME_AGENCY;
      agencyId = shared.first();
    } else {
      relation = sameTenant ? Relation.SAME_TENANT : Relation.OTHER_TENANT;
      agencyId = theirAgencies.isEmpty() ? null : Collections.min(theirAgencies);
    }
    return new Colleague(
        colleague, nameOf(colleague), relation, agencyId, theirPolicy.showAskerUsernames());
  }

  /**
   * With multitenancy an unknown tenant never matches another one; without it nobody carries a
   * tenant and everybody is of the same Träger.
   */
  private boolean sameTenant(Consultant me, Consultant colleague) {
    if (multitenancy && me.getTenantId() == null) {
      return false;
    }
    return Objects.equals(me.getTenantId(), colleague.getTenantId());
  }

  private Map<String, Set<Long>> activeAgencies(List<String> consultantIds) {
    var result = new HashMap<String, Set<Long>>();
    for (int from = 0; from < consultantIds.size(); from += RELATION_CHUNK) {
      var chunk =
          new ArrayList<>(
              consultantIds.subList(from, Math.min(from + RELATION_CHUNK, consultantIds.size())));
      List<ConsultantAgencyBase> relations =
          TenantContext.supplyAcrossTenants(
              () -> consultantAgencies.findByConsultantIdInAndDeleteDateIsNull(chunk));
      for (var relation : relations) {
        if (relation.getAgencyId() != null) {
          result
              .computeIfAbsent(relation.getConsultantId(), id -> new TreeSet<>())
              .add(relation.getAgencyId());
        }
      }
    }
    return result;
  }

  private static Set<Long> agencyIdsOf(Consultant consultant) {
    var ids = new TreeSet<Long>();
    if (consultant.getConsultantAgencies() != null) {
      for (ConsultantAgency relation : consultant.getConsultantAgencies()) {
        if (relation.getDeleteDate() == null && relation.getAgencyId() != null) {
          ids.add(relation.getAgencyId());
        }
      }
    }
    return ids;
  }

  /** The name colleagues see: the internal display name, else the public one, else the real. */
  static String nameOf(Consultant consultant) {
    var internal = consultant.getInternalDisplayNameOrFallback();
    return internal != null && !internal.isBlank() ? internal : consultant.getFullName();
  }
}
