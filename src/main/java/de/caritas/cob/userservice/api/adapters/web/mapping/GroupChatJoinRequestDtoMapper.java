package de.caritas.cob.userservice.api.adapters.web.mapping;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestStatus;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestStatusDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Maps join requests to their two views. The requester view deliberately carries no group data;
 * agency and tenant names in the moderator view are best-effort and fall back to null.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupChatJoinRequestDtoMapper {

  private final AgencyService agencyService;
  private final TenantService tenantService;
  private final GroupChatJoinRequestLocalLoader localLoader;

  public GroupChatJoinRequestStatusDTO toStatusDto(GroupChatJoinRequest request) {
    return new GroupChatJoinRequestStatusDTO()
        .id(request.getId())
        .status(GroupChatJoinRequestStatus.fromValue(request.getStatus().name()))
        .requestedAt(toUtc(request.getRequestedAt()))
        .decidedAt(toUtc(request.getDecidedAt()));
  }

  /** Resolve remote names only after the local loader's read transaction has returned. */
  public List<GroupChatJoinRequestDTO> pendingRequestsFor(String moderatorConsultantId) {
    var pending = localLoader.load(moderatorConsultantId);
    if (pending.isEmpty()) {
      return List.of();
    }
    var tenantIds =
        pending.stream()
            .map(GroupChatJoinRequestLocalLoader.LocalPending::tenantId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    var tenants = new HashMap<Long, String>();
    if (!tenantIds.isEmpty()) {
      try {
        tenantService.getRestrictedTenantData(tenantIds).stream()
            .filter(Objects::nonNull)
            .filter(tenant -> tenant.getId() != null)
            .forEach(tenant -> tenants.put(tenant.getId(), tenant.getName()));
      } catch (RuntimeException exception) {
        log.warn("Could not resolve join requester tenant names: {}", exception.getMessage());
      }
    }
    var agencies = new HashMap<Long, String>();
    return pending.stream()
        .map(
            item -> {
              var requester = item.dto().getRequester();
              requester
                  .tenantName(tenants.get(item.tenantId()))
                  .agencyName(agencyNameOf(item.agencyIds(), agencies));
              return item.dto();
            })
        .toList();
  }

  private String agencyNameOf(List<Long> agencyIds, Map<Long, String> agencyNames) {
    return agencyIds.stream()
        .map(agencyId -> cachedAgencyName(agencyId, agencyNames))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private String cachedAgencyName(Long agencyId, Map<Long, String> agencyNames) {
    if (!agencyNames.containsKey(agencyId)) {
      agencyNames.put(
          agencyId,
          resolve(
              () ->
                  Optional.ofNullable(agencyService.getAgency(agencyId))
                      .map(AgencyDTO::getName)
                      .orElse(null),
              agencyId));
    }
    return agencyNames.get(agencyId);
  }

  private String resolve(Supplier<String> lookup, Long id) {
    try {
      return lookup.get();
    } catch (RuntimeException e) {
      log.warn("Could not resolve name for id {} of a join requester: {}", id, e.getMessage());
      return null;
    }
  }

  private static OffsetDateTime toUtc(LocalDateTime utcDateTime) {
    return utcDateTime == null ? null : utcDateTime.atOffset(ZoneOffset.UTC);
  }
}
