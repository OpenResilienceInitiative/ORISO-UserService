package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Applies the shared owner decision only to external group counselling. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupCounsellingDpaPolicy {
  private final NewCounsellingDpaPolicy newCounselling;
  private final ChatAgencyRepository chatAgencies;
  private final AgencyService agencies;
  private final de.caritas.cob.userservice.api.service.matrixgroup.MatrixGroupParticipationHistory
      history;

  public void requireCreation(ChatDTO request, Long servingAgencyId, Consultant creator) {
    if (ChatConverter.conversationTypeOf(request) == ConversationType.SELF_HELP) {
      var ownerTenant = creator == null ? null : creator.getTenantId();
      if (ownerTenant == null || ownerTenant <= 0) throw unavailable();
      de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO agency;
      try {
        agency = agencies.getAgencyWithoutCaching(servingAgencyId);
      } catch (CustomValidationHttpStatusException refusal) {
        throw refusal;
      } catch (RuntimeException failure) {
        throw unavailable(failure);
      }
      if (agency == null || !ownerTenant.equals(agency.getTenantId())) throw unavailable();
      newCounselling.requireForAgency(agency);
    }
  }

  public void requireFirstStart(Chat chat) {
    if (chat.getCurrentOccurrenceIndex() == 0) {
      requireNewEnrolment(chat);
    }
  }

  /** Called only after ordinary invite/tenant/role permission validation for this stored actor. */
  public void requireAuthorizedEnrolment(Chat chat, String matrixUserId) {
    if (chat.getCurrentOccurrenceIndex() == 0
        && ChatConverter.conversationTypeOf(chat) == ConversationType.SELF_HELP
        && history.commenced(chat, matrixUserId)) return;
    requireNewEnrolment(chat);
  }

  /** Preserve the single pure owner decision outside enabled first-room actor classification. */
  public void requireAuthorizedEnrolments(Chat chat, List<String> matrixUserIds) {
    if (matrixUserIds.isEmpty()) return;
    if (historicalReturnEnabled()
        && chat.getCurrentOccurrenceIndex() == 0
        && ChatConverter.conversationTypeOf(chat) == ConversationType.SELF_HELP) {
      for (String matrixUserId : matrixUserIds) requireAuthorizedEnrolment(chat, matrixUserId);
    } else {
      requireNewEnrolment(chat);
    }
  }

  public boolean historicalReturnEnabled() {
    return history.enabled();
  }

  public void requireAuthorizedFirstEntry(Chat chat, String matrixUserId) {
    if (chat.getCurrentOccurrenceIndex() == 0) requireAuthorizedEnrolment(chat, matrixUserId);
  }

  public void requireNewEnrolment(Chat chat) {
    // Keep later-occurrence classification while its continuation policy remains undefined.
    var type =
        chat.getCurrentOccurrenceIndex() == 0
            ? ChatConverter.conversationTypeOf(chat)
            : chat.getConversationType();
    if (type != ConversationType.SELF_HELP) return;
    Long ownerTenant = chat.getChatOwner() == null ? null : chat.getChatOwner().getTenantId();
    if (ownerTenant == null || ownerTenant <= 0) throw unavailable();
    // Invitations may cross tenants; the recipient never becomes the group's serving owner.
    // Available agency ownership facts must agree with the persisted Series owner.
    try {
      for (var relation : chatAgencies.findByChat_Id(chat.getId())) {
        var agency = agencies.getAgencyWithoutCaching(relation.getAgencyId());
        if (agency == null || !ownerTenant.equals(agency.getTenantId())) throw unavailable();
      }
    } catch (CustomValidationHttpStatusException refusal) {
      throw refusal;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    newCounselling.requireForConcreteTenant(ownerTenant);
  }

  private CustomValidationHttpStatusException unavailable() {
    return unavailable(null);
  }

  private CustomValidationHttpStatusException unavailable(RuntimeException failure) {
    // The HTTP exception may contain credentials or private response data.
    // Match the shared AVV policy's diagnostic contract: classify, never print it.
    log.warn(
        "Group AVV policy unavailable: type={}",
        failure == null ? "InvalidContract" : failure.getClass().getSimpleName());
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.DPA_POLICY_UNAVAILABLE, HttpStatus.BAD_GATEWAY);
  }
}
