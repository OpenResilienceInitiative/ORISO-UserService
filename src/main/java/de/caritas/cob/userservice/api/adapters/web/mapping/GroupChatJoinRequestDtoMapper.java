package de.caritas.cob.userservice.api.adapters.web.mapping;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO.ViaEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO.ViewerRoleEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestStatus;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestStatusDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequesterDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.GroupChatJoinRequestService;
import de.caritas.cob.userservice.api.service.chat.GroupChatJoinRequestService.PendingForModerator;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps join requests to their two views. The requester view deliberately carries no group data;
 * agency and tenant names in the moderator view are best-effort and fall back to null.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupChatJoinRequestDtoMapper {

  private final ConsultantRepository consultantRepository;
  private final AgencyService agencyService;
  private final TenantService tenantService;
  private final ChatPermissionVerifier chatPermissionVerifier;
  private final GroupChatJoinRequestService joinRequestService;

  public GroupChatJoinRequestStatusDTO toStatusDto(GroupChatJoinRequest request) {
    return new GroupChatJoinRequestStatusDTO()
        .id(request.getId())
        .status(GroupChatJoinRequestStatus.fromValue(request.getStatus().name()))
        .requestedAt(toUtc(request.getRequestedAt()))
        .decidedAt(toUtc(request.getDecidedAt()));
  }

  /**
   * Pending requests for the moderator view. Runs in one read transaction because the requester's
   * agencies and the Series' agencies are lazy associations.
   */
  @Transactional(readOnly = true)
  public List<GroupChatJoinRequestDTO> pendingRequestsFor(String moderatorConsultantId) {
    var pending = joinRequestService.findPendingForModerator(moderatorConsultantId);
    if (pending.isEmpty()) {
      return List.of();
    }
    var requesterIds =
        pending.stream().map(item -> item.request().getConsultantId()).distinct().toList();
    var requesters =
        consultantRepository.findAllWithAgenciesByIdIn(requesterIds).stream()
            .collect(Collectors.toMap(Consultant::getId, consultant -> consultant));
    var tenantIds =
        requesters.values().stream()
            .map(Consultant::getTenantId)
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
        .map(item -> toModeratorDto(item, requesters, tenants, agencies))
        .toList();
  }

  private GroupChatJoinRequestDTO toModeratorDto(
      PendingForModerator pending,
      Map<String, Consultant> requesters,
      Map<Long, String> tenants,
      Map<Long, String> agencies) {
    var request = pending.request();
    var series = pending.series();
    return new GroupChatJoinRequestDTO()
        .id(request.getId())
        .seriesId(request.getSeriesId())
        .groupTitle(series.getTopic())
        .status(GroupChatJoinRequestStatus.fromValue(request.getStatus().name()))
        .requestedAt(toUtc(request.getRequestedAt()))
        .decidedAt(toUtc(request.getDecidedAt()))
        .via(ViaEnum.fromValue(request.getVia().name()))
        .viewerRole(ViewerRoleEnum.fromValue(pending.viewerRole().name()))
        .requester(
            toRequesterDto(request.getConsultantId(), series, requesters, tenants, agencies));
  }

  private GroupChatJoinRequesterDTO toRequesterDto(
      String consultantId,
      Chat series,
      Map<String, Consultant> requesters,
      Map<Long, String> tenants,
      Map<Long, String> agencies) {
    var dto = new GroupChatJoinRequesterDTO().consultantId(consultantId);
    var requester = requesters.get(consultantId);
    if (requester == null) {
      return dto.displayName(null)
          .firstName(null)
          .lastName(null)
          .agencyName(null)
          .tenantName(null)
          .sameAgency(false)
          .sameTenant(false);
    }
    return dto.displayName(requester.getInternalDisplayNameOrFallback())
        .firstName(requester.getFirstName())
        .lastName(requester.getLastName())
        .agencyName(agencyNameOf(requester, agencies))
        .tenantName(tenants.get(requester.getTenantId()))
        .sameAgency(chatPermissionVerifier.hasSameAgencyAssigned(series, requester))
        .sameTenant(
            series.getChatOwner() != null
                && requester.getTenantId() != null
                && Objects.equals(requester.getTenantId(), series.getChatOwner().getTenantId()));
  }

  private String agencyNameOf(Consultant requester, Map<Long, String> agencyNames) {
    if (requester.getConsultantAgencies() == null) {
      return null;
    }
    return requester.getConsultantAgencies().stream()
        .filter(consultantAgency -> consultantAgency.getDeleteDate() == null)
        .map(ConsultantAgency::getAgencyId)
        .filter(Objects::nonNull)
        .sorted(Comparator.naturalOrder())
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
