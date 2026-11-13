package de.caritas.cob.userservice.api.admin.service.asker;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerSearchResultDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Agencies;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Platform;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Reach;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AskerSearchRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists advice seekers for the admin interface, within the caller's reach. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AskerAdminSearchService {

  static final int MAX_PER_PAGE = 100;

  /** Shown for a row marked before the lifecycle column existed. */
  static final String MARKED_FOR_DELETION = "MARKED_FOR_DELETION";

  private static final String ACTIVE = "ACTIVE";

  private static final DateTimeFormatter ISO_SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private final @NonNull AskerSearchRepository askerSearchRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull UserAgencyRepository userAgencyRepository;
  private final @NonNull AdminScope adminScope;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull GroupChatSystemAccounts groupChatSystemAccounts;

  @Transactional(readOnly = true)
  public AskerSearchResultDTO findAskers(Integer page, Integer perPage, AskerFilter filter) {
    var askerFilter = filter == null ? new AskerFilter() : filter;
    var reach = adminScope.current();
    var requestedAgencyIds = effectiveAgencyIds(reach, askerFilter);
    if (requestedAgencyIds == null) {
      return new AskerSearchResultDTO().total(0L).embedded(List.of());
    }
    var specification =
        adminScope.narrow(
            AskerAdminSpecification.matching(
                usernameVariants(askerFilter.getUsername()),
                requestedAgencyIds,
                effectiveTenantId(reach, askerFilter)),
            AskerAdminSpecification::inAgencies);
    var pageRequest =
        PageRequest.of(
            Math.max(nullToZero(page) - 1, 0),
            Math.clamp(nullToZero(perPage), 1, MAX_PER_PAGE),
            Sort.by(Sort.Order.desc("createDate"), Sort.Order.asc("userId")));

    var result = askerSearchRepository.findAll(specification, pageRequest);
    var agencyIds = visibleAgencyIds(agencyIdsByUser(result.getContent()), reach);
    var presentTenants = tenantsOfTechnicalAccounts(result.getContent());

    return new AskerSearchResultDTO()
        .total(result.getTotalElements())
        .embedded(
            result.getContent().stream()
                .map(asker -> toResponse(asker, agencyIds.get(asker.getUserId()), presentTenants))
                .toList());
  }

  /**
   * Which Träger of the page's technical accounts still exist; {@code null} when that could not be
   * checked, so those accounts are shown as not deletable (fail closed) instead of failing the
   * list.
   */
  private Set<Long> tenantsOfTechnicalAccounts(List<User> askers) {
    var tenantIds =
        askers.stream()
            .filter(asker -> GroupChatSystemAccounts.isTechnical(asker.getUserId()))
            .map(User::getTenantId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (tenantIds.isEmpty()) {
      return Set.of();
    }
    try {
      return groupChatSystemAccounts.tenantsStillPresent(tenantIds);
    } catch (RuntimeException unavailable) {
      log.warn("Could not check the Träger of {} technical accounts", tenantIds.size());
      return null;
    }
  }

  private static boolean deletable(User asker, Set<Long> presentTenants) {
    if (!GroupChatSystemAccounts.isTechnical(asker.getUserId())) {
      return true;
    }
    return presentTenants != null
        && !GroupChatSystemAccounts.isPlatformTenant(asker.getTenantId())
        && !presentTenants.contains(asker.getTenantId());
  }

  /**
   * An agency admin sees only their own agencies of an advice seeker: that the person also writes
   * to another Beratungsstelle is not theirs to know.
   */
  static Map<String, Set<Long>> visibleAgencyIds(Map<String, Set<Long>> agencyIds, Reach reach) {
    if (!(reach instanceof Agencies agencies)) {
      return agencyIds;
    }
    agencyIds.values().forEach(ids -> ids.retainAll(agencies.ids()));
    return agencyIds;
  }

  /**
   * An agency admin may only filter by their own agencies; asking for another one would tell them
   * which of their advice seekers also write there. {@code null} when none of the requested
   * agencies is theirs, so the search finds nobody.
   */
  static List<Long> effectiveAgencyIds(Reach reach, AskerFilter filter) {
    var requested = filter.getAgencyId() == null ? List.<Long>of() : filter.getAgencyId();
    if (requested.isEmpty() || !(reach instanceof Agencies agencies)) {
      return requested;
    }
    var own = requested.stream().filter(agencies.ids()::contains).toList();
    return own.isEmpty() ? null : own;
  }

  /** Only a platform admin chooses the Träger; everybody else searches their own. */
  static Long effectiveTenantId(Reach reach, AskerFilter filter) {
    return reach instanceof Platform ? filter.getTenantId() : reach.tenantId();
  }

  /** Usernames are stored encoded, older rows in plain text: match both forms exactly. */
  Set<String> usernameVariants(String username) {
    if (StringUtils.isBlank(username)) {
      return Set.of();
    }
    var trimmed = username.trim();
    return Set.of(
        usernameTranscoder.encodeUsername(trimmed), usernameTranscoder.decodeUsername(trimmed));
  }

  private Map<String, Set<Long>> agencyIdsByUser(List<User> askers) {
    Map<String, Set<Long>> agencyIds = new HashMap<>();
    if (askers.isEmpty()) {
      return agencyIds;
    }
    var userIds = askers.stream().map(User::getUserId).toList();
    sessionRepository
        .findAgencyIdsByUserIds(userIds)
        .forEach(row -> add(agencyIds, row.getUserId(), row.getAgencyId()));
    userAgencyRepository
        .findByUserUserIdIn(userIds)
        .forEach(
            membership ->
                add(agencyIds, membership.getUser().getUserId(), membership.getAgencyId()));
    return agencyIds;
  }

  private static void add(Map<String, Set<Long>> agencyIds, String userId, Long agencyId) {
    if (agencyId != null) {
      agencyIds.computeIfAbsent(userId, id -> new TreeSet<>()).add(agencyId);
    }
  }

  private AskerAdminResponseDTO toResponse(
      User asker, Set<Long> agencyIds, Set<Long> presentTenants) {
    return new AskerAdminResponseDTO()
        .id(asker.getUserId())
        .username(usernameTranscoder.decodeUsername(asker.getUsername()))
        .tenantId(asker.getTenantId())
        .agencyIds(agencyIds == null ? List.of() : List.copyOf(agencyIds))
        .createDate(format(asker.getCreateDate()))
        .deleteDate(format(asker.getDeleteDate()))
        .deletionState(deletionState(asker))
        .deletionFailureReason(asker.getDeletionFailureReason())
        .deletionFailedAt(format(asker.getDeletionFailedAt()))
        .technical(GroupChatSystemAccounts.isTechnical(asker.getUserId()))
        .deletable(deletable(asker, presentTenants));
  }

  private static String deletionState(User asker) {
    if (asker.getDeletionLifecycleState() != null) {
      return asker.getDeletionLifecycleState().name();
    }
    return asker.getDeleteDate() == null ? ACTIVE : MARKED_FOR_DELETION;
  }

  /** Always with seconds, so the admin interface parses every value the same way. */
  private static String format(LocalDateTime dateTime) {
    return dateTime == null ? null : dateTime.truncatedTo(ChronoUnit.SECONDS).format(ISO_SECONDS);
  }

  private static int nullToZero(Integer value) {
    return value == null ? 0 : value;
  }
}
