package de.caritas.cob.userservice.api.adapters.web.mapping;

import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO.ViaEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO.ViewerRoleEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestStatus;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequesterDTO;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.chat.GroupChatJoinRequestService;
import de.caritas.cob.userservice.api.service.chat.GroupChatJoinRequestService.PendingForModerator;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Copies the database-backed join-request view before any remote name lookups. */
@Component
@RequiredArgsConstructor
public class GroupChatJoinRequestLocalLoader {

  private final ConsultantRepository consultantRepository;
  private final ChatPermissionVerifier chatPermissionVerifier;
  private final GroupChatJoinRequestService joinRequestService;

  public record LocalPending(GroupChatJoinRequestDTO dto, Long tenantId, List<Long> agencyIds) {}

  @Transactional(readOnly = true)
  public List<LocalPending> load(String moderatorConsultantId) {
    var pending = joinRequestService.findPendingForModerator(moderatorConsultantId);
    if (pending.isEmpty()) {
      return List.of();
    }
    var requesterIds =
        pending.stream().map(item -> item.request().getConsultantId()).distinct().toList();
    var requesters =
        consultantRepository.findAllWithAgenciesByIdIn(requesterIds).stream()
            .collect(Collectors.toMap(Consultant::getId, consultant -> consultant));
    return pending.stream().map(item -> snapshot(item, requesters)).toList();
  }

  private LocalPending snapshot(PendingForModerator pending, Map<String, Consultant> requesters) {
    var request = pending.request();
    var series = pending.series();
    var consultantId = request.getConsultantId();
    var requester = requesters.get(consultantId);
    var requesterDto = new GroupChatJoinRequesterDTO().consultantId(consultantId);
    Long tenantId = null;
    List<Long> agencyIds = List.of();
    if (requester == null) {
      requesterDto
          .displayName(null)
          .firstName(null)
          .lastName(null)
          .agencyName(null)
          .tenantName(null)
          .sameAgency(false)
          .sameTenant(false);
    } else {
      tenantId = requester.getTenantId();
      agencyIds =
          requester.getConsultantAgencies() == null
              ? List.of()
              : requester.getConsultantAgencies().stream()
                  .filter(agency -> agency.getDeleteDate() == null)
                  .map(ConsultantAgency::getAgencyId)
                  .filter(Objects::nonNull)
                  .sorted(Comparator.naturalOrder())
                  .toList();
      requesterDto
          .displayName(requester.getInternalDisplayNameOrFallback())
          .firstName(requester.getFirstName())
          .lastName(requester.getLastName())
          .agencyName(null)
          .tenantName(null)
          .sameAgency(chatPermissionVerifier.hasSameAgencyAssigned(series, requester))
          .sameTenant(
              series.getChatOwner() != null
                  && tenantId != null
                  && Objects.equals(tenantId, series.getChatOwner().getTenantId()));
    }
    var dto =
        new GroupChatJoinRequestDTO()
            .id(request.getId())
            .seriesId(request.getSeriesId())
            .groupTitle(series.getTopic())
            .status(GroupChatJoinRequestStatus.fromValue(request.getStatus().name()))
            .requestedAt(toUtc(request.getRequestedAt()))
            .decidedAt(toUtc(request.getDecidedAt()))
            .via(ViaEnum.fromValue(request.getVia().name()))
            .viewerRole(ViewerRoleEnum.fromValue(pending.viewerRole().name()))
            .requester(requesterDto);
    return new LocalPending(dto, tenantId, agencyIds);
  }

  private static OffsetDateTime toUtc(LocalDateTime utcDateTime) {
    return utcDateTime == null ? null : utcDateTime.atOffset(ZoneOffset.UTC);
  }
}
