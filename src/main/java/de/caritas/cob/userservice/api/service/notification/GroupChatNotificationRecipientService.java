package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves every identity already participating in a self-help group Series. */
@Service
@RequiredArgsConstructor
public class GroupChatNotificationRecipientService {

  private final GroupChatParticipantRepository participantRepository;
  private final UserChatRepository userChatRepository;
  private final ConsultantRepository consultantRepository;
  private final AccountInactivityService accountInactivityService;

  public List<String> resolveRecipientIds(Chat series) {
    if (series == null || series.getId() == null) {
      return List.of();
    }

    var recipientIds = new LinkedHashSet<String>();
    var consultantIds =
        participantRepository.findBySeriesId(series.getId()).stream()
            .map(participant -> participant.getConsultantId())
            .filter(GroupChatNotificationRecipientService::isPresent)
            .distinct()
            .toList();
    // Admitted consultants may belong to another tenant; only these membership IDs are read.
    var currentConsultantIds =
        consultantIds.isEmpty()
            ? Set.<String>of()
            : TenantContext.supplyAcrossTenants(
                () -> consultantRepository.findActiveIdsByIdIn(consultantIds));
    consultantIds.stream().filter(currentConsultantIds::contains).forEach(recipientIds::add);
    userChatRepository.findByChat(series).stream()
        .filter(relation -> relation.getUser() != null)
        .filter(relation -> relation.getUser().getDeleteDate() == null)
        .map(relation -> relation.getUser().getUserId())
        .filter(GroupChatNotificationRecipientService::isPresent)
        .forEach(recipientIds::add);
    return recipientIds.stream().filter(this::isCurrentlyActive).toList();
  }

  private boolean isCurrentlyActive(String identityId) {
    return accountInactivityService
        .snapshot(identityId)
        .map(state -> state.status() == AccountInactivityService.Status.ACTIVE)
        .orElse(true);
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
