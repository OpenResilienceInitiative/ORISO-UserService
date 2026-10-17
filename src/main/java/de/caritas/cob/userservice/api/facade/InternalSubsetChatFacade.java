package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.InternalSubsetChatDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.chat.GroupChatFeatureGate;
import de.caritas.cob.userservice.api.service.chat.InternalGroupChatRecipientScopeResolver;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A message to a subset of colleagues goes into its own encrypted room that holds exactly the
 * caller and the selected colleagues (ORISO-UserService#1418, decision on ORISO-Frontend#1269). It
 * never falls back to a broader room: a colleague outside the Träger's recipient scope refuses the
 * whole request, and a room is only reused when its members are, in the database and live in
 * Matrix, exactly the selection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalSubsetChatFacade {

  /** The room to write to, and whether this call created it. */
  public record OpenedRoom(String matrixRoomId, Long chatId, boolean created) {}

  private final @NonNull MatrixConfig matrixConfig;
  private final @NonNull GroupChatFeatureGate featureGate;
  private final @NonNull InternalGroupChatRecipientScopeResolver recipientScopes;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ChatRepository chatRepository;
  private final @NonNull GroupChatParticipantRepository participantRepository;
  private final @NonNull GroupChatMembershipService membershipService;
  private final @NonNull CreateChatFacade createChatFacade;

  public OpenedRoom open(InternalSubsetChatDTO request, Consultant creator) {
    requireEncryption();
    var colleagueIds = colleagueIdsOf(request, creator);
    var agencyId = resolveAgencyId(request, creator);
    featureGate.requireEnabled(creator, ChatDTO.builder().agencyId(agencyId).build());

    var colleagues = resolveColleagues(colleagueIds);
    requireInsideScope(creator, colleagues, request.getAgencyId());

    var memberIds = new LinkedHashSet<String>();
    memberIds.add(creator.getId());
    memberIds.addAll(colleagueIds);

    var reusable = findReusableRoom(creator, memberIds);
    if (reusable.isPresent()) {
      return new OpenedRoom(reusable.get().getMatrixRoomId(), reusable.get().getId(), false);
    }

    var chatDto =
        ChatDTO.builder()
            .topic(topicOf(request, creator, colleagues))
            .agencyId(agencyId)
            .hintMessage("")
            .build();
    var created = createChatFacade.createMembersOnlyChat(chatDto, creator, colleagues);
    return new OpenedRoom(created.response().getMatrixRoomId(), created.chatId(), true);
  }

  private void requireEncryption() {
    if (!matrixConfig.isEncryptionEnabled()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_ENCRYPTION_REQUIRED,
          HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private static Set<String> colleagueIdsOf(InternalSubsetChatDTO request, Consultant creator) {
    var ids = new LinkedHashSet<String>();
    if (request.getConsultantIds() != null) {
      request.getConsultantIds().stream()
          .filter(id -> id != null && !id.isBlank())
          .filter(id -> !id.equals(creator.getId()))
          .forEach(ids::add);
    }
    if (ids.isEmpty()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENTS_REQUIRED);
    }
    return ids;
  }

  private static Long resolveAgencyId(InternalSubsetChatDTO request, Consultant creator) {
    if (request.getAgencyId() != null) {
      if (!creator.isInAgency(request.getAgencyId())) {
        throw new ForbiddenException("The caller is not a counsellor of this counselling centre");
      }
      return request.getAgencyId();
    }
    return creator.getConsultantAgencies() == null
        ? null
        : creator.getConsultantAgencies().stream()
            .map(agency -> agency.getAgencyId())
            .findFirst()
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "The caller is not assigned to any counselling centre"));
  }

  /** Every selected id must be an active colleague with a chat identity, in any Träger. */
  private List<Consultant> resolveColleagues(Set<String> colleagueIds) {
    var colleagues = new ArrayList<Consultant>();
    for (String id : colleagueIds) {
      var colleague =
          TenantContext.supplyAcrossTenants(
              () -> consultantRepository.findByIdAndDeleteDateIsNull(id));
      if (colleague.isEmpty() || !MatrixIds.isUserId(colleague.get().getMatrixUserId())) {
        throw new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_UNKNOWN);
      }
      colleagues.add(colleague.get());
    }
    return colleagues;
  }

  private void requireInsideScope(
      Consultant creator, List<Consultant> colleagues, Long requestedAgencyId) {
    var scope = recipientScopes.resolve(creator.getTenantId());
    for (Consultant colleague : colleagues) {
      if (!scope.permits(creator, colleague, requestedAgencyId)) {
        log.info("Colleague {} is outside the recipient scope {}", colleague.getId(), scope);
        throw new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE, HttpStatus.FORBIDDEN);
      }
    }
  }

  /**
   * A members-only internal chat whose participants are exactly {@code memberIds} <em>and</em>
   * whose Matrix room holds exactly those people right now. Matrix is the source of truth for who
   * can read; the participants table is only a snapshot, so a room that gained or lost anyone since
   * is not reused.
   */
  private Optional<Chat> findReusableRoom(Consultant creator, Set<String> memberIds) {
    var seriesIds =
        participantRepository.findSeriesIdsWithExactParticipants(
            creator.getId(), memberIds, memberIds.size());
    if (seriesIds.isEmpty()) {
      return Optional.empty();
    }
    var candidates = TenantContext.supplyAcrossTenants(() -> chatRepository.findAllById(seriesIds));
    for (Chat chat : candidates) {
      if (chat.isMembersOnly()
          && chat.isActive()
          && ChatConverter.conversationTypeOf(chat) == ConversationType.INTERNAL_GROUP
          && MatrixIds.isRoomId(chat.getMatrixRoomId())
          && roomHoldsExactly(chat.getMatrixRoomId(), memberIds)) {
        return Optional.of(chat);
      }
    }
    return Optional.empty();
  }

  private boolean roomHoldsExactly(String matrixRoomId, Set<String> memberIds) {
    var present =
        TenantContext.supplyAcrossTenants(
            () -> membershipService.resolveHumanMembers(matrixRoomId));
    return present.stream().allMatch(ResolvedRoomMember::consultant)
        && present.stream()
            .map(ResolvedRoomMember::accountId)
            .collect(Collectors.toSet())
            .equals(memberIds);
  }

  /** The given topic, else the members' names, within the length a chat topic may have. */
  private static String topicOf(
      InternalSubsetChatDTO request, Consultant creator, List<Consultant> colleagues) {
    if (request.getTopic() != null && !request.getTopic().isBlank()) {
      return request.getTopic().strip();
    }
    var names = new ArrayList<String>();
    names.add(nameOf(creator));
    colleagues.forEach(colleague -> names.add(nameOf(colleague)));
    var joined = String.join(", ", names);
    return joined.length() <= UserHelper.CHAT_TOPIC_MAX_LENGTH
        ? joined
        : joined.substring(0, UserHelper.CHAT_TOPIC_MAX_LENGTH - 1) + "…";
  }

  private static String nameOf(Consultant consultant) {
    var internal = consultant.getInternalDisplayNameOrFallback();
    return internal != null && !internal.isBlank() ? internal : consultant.getFullName();
  }
}
