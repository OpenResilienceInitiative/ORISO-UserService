package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
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
import de.caritas.cob.userservice.api.service.chat.InternalGroupChatRecipientScope;
import de.caritas.cob.userservice.api.service.chat.InternalGroupChatRecipientScopeResolver;
import de.caritas.cob.userservice.api.service.chat.MemberSetLock;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MemberSetLock memberSetLock;
  private final @NonNull CreateChatFacade createChatFacade;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancy;

  public OpenedRoom open(InternalSubsetChatDTO request, Consultant creator) {
    requireEncryption();
    var colleagueIds = colleagueIdsOf(request, creator);
    var looked = lookUp(colleagueIds);
    var scope = recipientScopes.resolve(creator.getTenantId());
    var agencyId = resolveAgencyId(request, creator, looked, scope);
    featureGate.requireEnabled(creator, ChatDTO.builder().agencyId(agencyId).build());

    requireInsideScope(creator, looked, scope, agencyId);
    var colleagues = requireChatIdentity(looked);

    var memberIds = new LinkedHashSet<String>();
    memberIds.add(creator.getId());
    memberIds.addAll(colleagueIds);
    var matrixIds = new LinkedHashSet<String>();
    matrixIds.add(creator.getMatrixUserId());
    colleagues.forEach(colleague -> matrixIds.add(colleague.getMatrixUserId()));

    // Find-or-create is one step per member set, across replicas: of two simultaneous requests for
    // the same people the second one finds the first one's room.
    try (var ignored = memberSetLock.acquire(memberIds)) {
      var reusable = findReusableRoom(creator, memberIds, matrixIds);
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
      for (String id : request.getConsultantIds()) {
        // A selection is never shortened silently: an entry that names nobody refuses the request.
        if (id == null || id.isBlank()) {
          throw new CustomValidationHttpStatusException(
              HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_UNKNOWN);
        }
        if (!id.equals(creator.getId())) {
          ids.add(id);
        }
      }
    }
    if (ids.isEmpty()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENTS_REQUIRED);
    }
    return ids;
  }

  /**
   * The centre the chat is created for: the named one, else the lowest-numbered centre of the
   * caller that the whole selection is within the recipient scope for, else the lowest-numbered
   * centre (which the scope check then refuses). The scope is always checked against this centre.
   */
  private Long resolveAgencyId(
      InternalSubsetChatDTO request,
      Consultant creator,
      Map<String, Consultant> looked,
      InternalGroupChatRecipientScope scope) {
    if (request.getAgencyId() != null) {
      if (!creator.isInAgency(request.getAgencyId())) {
        throw new ForbiddenException("The caller is not a counsellor of this counselling centre");
      }
      return request.getAgencyId();
    }
    var centres =
        creator.getConsultantAgencies() == null
            ? List.<Long>of()
            : creator.getConsultantAgencies().stream()
                .map(agency -> agency.getAgencyId())
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
    if (centres.isEmpty()) {
      throw new BadRequestException("The caller is not assigned to any counselling centre");
    }
    return centres.stream()
        .filter(
            centre ->
                looked.values().stream()
                    .allMatch(c -> c != null && scope.permits(creator, c, centre, multitenancy)))
        .findFirst()
        .orElse(centres.get(0));
  }

  /** The selected consultants by id; {@code null} for an id that is not an active consultant. */
  private Map<String, Consultant> lookUp(Set<String> colleagueIds) {
    var looked = new LinkedHashMap<String, Consultant>();
    for (String id : colleagueIds) {
      looked.put(
          id,
          TenantContext.supplyAcrossTenants(
                  () -> consultantRepository.findByIdAndDeleteDateIsNull(id))
              .orElse(null));
    }
    return looked;
  }

  /**
   * One colleague outside the scope refuses the whole request. An id that is no active consultant
   * answers exactly like one outside the scope, so the answer does not tell whether a consultant of
   * another Träger exists.
   */
  private void requireInsideScope(
      Consultant creator,
      Map<String, Consultant> looked,
      InternalGroupChatRecipientScope scope,
      Long agencyId) {
    looked.forEach(
        (id, colleague) -> {
          if (colleague == null || !scope.permits(creator, colleague, agencyId, multitenancy)) {
            log.info("A selected colleague is outside the recipient scope {}", scope);
            throw new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE,
                HttpStatus.FORBIDDEN);
          }
        });
  }

  /** A colleague the caller may pick, but who cannot join a room, is reported, never skipped. */
  private static List<Consultant> requireChatIdentity(Map<String, Consultant> looked) {
    var colleagues = new ArrayList<Consultant>();
    for (Consultant colleague : looked.values()) {
      if (!MatrixIds.isUserId(colleague.getMatrixUserId())) {
        throw new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_UNKNOWN);
      }
      colleagues.add(colleague);
    }
    return colleagues;
  }

  /**
   * A members-only internal chat whose participants are exactly {@code memberIds} <em>and</em>
   * whose Matrix room holds exactly the matching Matrix accounts right now. Matrix is the source of
   * truth for who can read; the participants table is only a snapshot, so a room that gained or
   * lost anyone since is not reused.
   */
  private Optional<Chat> findReusableRoom(
      Consultant creator, Set<String> memberIds, Set<String> matrixIds) {
    var seriesIds =
        participantRepository.findSeriesIdsWithExactParticipants(
            creator.getId(), memberIds, memberIds.size());
    if (seriesIds.isEmpty()) {
      return Optional.empty();
    }
    var candidates = TenantContext.supplyAcrossTenants(() -> chatRepository.findAllById(seriesIds));
    var someRoomCouldNotBeRead = false;
    for (Chat chat : candidates) {
      if (chat.isMembersOnly()
          && chat.isActive()
          && ChatConverter.conversationTypeOf(chat) == ConversationType.INTERNAL_GROUP
          && MatrixIds.isRoomId(chat.getMatrixRoomId())) {
        var holdsExactly = roomHoldsExactly(chat.getMatrixRoomId(), matrixIds);
        if (holdsExactly == null) {
          // Keep looking: a later candidate may hold exactly these people.
          someRoomCouldNotBeRead = true;
        } else if (holdsExactly) {
          return Optional.of(chat);
        }
      }
    }
    if (someRoomCouldNotBeRead) {
      // Unknown is not "different": no candidate matched and one could not be read, so a second
      // room for the same people could split their history. Ask the caller to try again once
      // Matrix answers.
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_BUSY, HttpStatus.SERVICE_UNAVAILABLE);
    }
    return Optional.empty();
  }

  /**
   * Whether the room holds exactly the matching Matrix accounts, or {@code null} when its members
   * cannot be read. Every account in the room, mapped or not, counts: a user of a foreign
   * homeserver, a deleted consultant or any other stranger makes the room unfit. Only the
   * configured technical admin, which the platform adds to rooms for administration, is ignored.
   */
  private Boolean roomHoldsExactly(String matrixRoomId, Set<String> matrixIds) {
    var joined = matrixSynapseService.getRoomMembers(matrixRoomId);
    if (joined.isEmpty()) {
      return null;
    }
    var admin = "@" + matrixConfig.getAdminUsername() + ":" + matrixConfig.getServerName();
    var present = new java.util.HashSet<>(joined.get());
    present.remove(admin);
    return present.equals(matrixIds);
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
