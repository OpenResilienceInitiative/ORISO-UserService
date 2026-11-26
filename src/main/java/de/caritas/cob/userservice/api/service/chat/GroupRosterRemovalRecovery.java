package de.caritas.cob.userservice.api.service.chat;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.GroupRosterRemovalIntent;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.GroupRosterRemovalIntentRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Repairs the local consequences of a Matrix departure that outlived a roster transaction. */
@Service
@RequiredArgsConstructor
class GroupRosterRemovalRecovery {

  private final GroupRosterRemovalIntentRepository intents;
  private final ChatRepository groups;
  private final ConsultantRepository consultants;
  private final GroupChatParticipantRepository participants;
  private final SessionSupervisorRepository supervisions;
  private final MembersOnlyRevocation revocation;
  private final MatrixSynapseService matrix;
  private final Clock clock;

  record GroupScope(Long id, String ownerId, Long tenantId, String roomId, int occurrenceIndex) {
    static GroupScope of(Chat group) {
      return new GroupScope(
          group.getId(),
          group.getChatOwner().getId(),
          group.getChatOwner().getTenantId(),
          group.getMatrixRoomId(),
          group.getCurrentOccurrenceIndex());
    }
  }

  record Reservation(Long id, boolean sendLeave) {}

  record ParticipantScope(Long id, long version, String consultantId) {
    static ParticipantScope of(GroupChatParticipant participant) {
      return new ParticipantScope(
          participant.getId(), participant.getRosterVersion(), participant.getConsultantId());
    }
  }

  /** Persist before the remote call, independently of the transaction holding the group lock. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Reservation record(
      GroupScope group,
      ParticipantScope participant,
      String roomId,
      String memberId,
      Long supervisionId,
      Long localSupervisionId) {
    var older =
        intents.findBySeriesIdAndRoomIdAndMemberIdAndDispatchSettledIsFalse(
            group.id(), roomId, memberId);
    if (!older.isEmpty()) {
      confirmSettledDeparture(older);
    }
    var intent =
        intents.save(
            GroupRosterRemovalIntent.builder()
                .seriesId(group.id())
                .ownerId(group.ownerId())
                .tenantId(group.tenantId())
                .seriesRoomId(group.roomId())
                .occurrenceIndex(group.occurrenceIndex())
                .participantId(participant.id())
                .participantVersion(participant.version())
                .consultantId(participant.consultantId())
                .roomId(roomId)
                .memberId(memberId)
                .supervisionId(supervisionId)
                .localSupervisionId(localSupervisionId)
                .dispatchSettled(!older.isEmpty())
                .retryAt(nextProbe())
                .build());
    // An unresolved request is never dispatched twice. Its verified result is adopted instead.
    return new Reservation(intent.getId(), older.isEmpty());
  }

  /** An older unresolved leave must settle before an authorised edit can rejoin that member. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void verifyRetainedMembers(GroupScope group, Map<String, String> selectedMemberIds) {
    if (selectedMemberIds.isEmpty()) {
      return;
    }
    for (var intent :
        intents.findBySeriesIdAndSupervisionIdIsNullAndDispatchSettledIsFalseAndConsultantIdIn(
            group.id(), selectedMemberIds.keySet())) {
      var selectedMember = selectedMemberIds.get(intent.getConsultantId());
      if (Objects.equals(group.roomId(), intent.getRoomId())
          && (isBlank(selectedMember) || selectedMember.equals(intent.getMemberId()))) {
        confirmSettledDeparture(List.of(intent));
      }
    }
  }

  private void confirmSettledDeparture(List<GroupRosterRemovalIntent> older) {
    if (!hasDeparted(older.getFirst())) {
      throw new ConflictException("A previous group removal is still being confirmed");
    }
    older.forEach(intent -> intent.setDispatchSettled(true));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markDispatchSettled(Long id) {
    intents.findById(id).orElseThrow().setDispatchSettled(true);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void discardUnsent(Long id) {
    intents.deleteById(id);
  }

  /** Clear a side exit only if the writer commits its local supervision update. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void clearSideExit(Long id) {
    intents.deleteById(id);
  }

  /** A committed complete roster supersedes earlier main-room intents for that series. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acceptRoster(Long seriesId) {
    intents.discardGroupRoomIntents(seriesId);
  }

  @Transactional(readOnly = true)
  public List<Long> readyIds() {
    return intents.findDueIds(LocalDateTime.now(clock), PageRequest.of(0, 20));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public void retry(Long id) {
    var route = intents.findRetryRouteById(id).orElse(null);
    if (route == null) {
      return;
    }
    // Match roster writers: owner, series, then participants and supervision. Hydrate the intent
    // only after those first locks so the persistence context cannot reuse an older snapshot.
    var owner = consultants.findPictureOwnerForUpdate(route.ownerId()).orElse(null);
    var group = groups.findByIdForUpdate(route.seriesId()).orElse(null);
    var intent = intents.lockById(id).orElse(null);
    if (intent == null || intent.getRetryAt().isAfter(LocalDateTime.now(clock))) {
      return;
    }
    if (owner == null
        || group == null
        || group.getChatOwner() == null
        || !Objects.equals(owner.getTenantId(), intent.getTenantId())
        || !matches(GroupScope.of(group), intent)) {
      retireStale(group, intent);
      return;
    }

    var participant =
        participants
            .findBySeriesIdAndConsultantIdForUpdate(intent.getSeriesId(), intent.getConsultantId())
            .orElse(null);
    var sideRoomExit = intent.getSupervisionId() != null;
    var member = sideRoomExit ? null : consultants.findById(intent.getConsultantId()).orElse(null);
    if (participant == null
        || !Objects.equals(participant.getId(), intent.getParticipantId())
        || participant.getRosterVersion() != intent.getParticipantVersion()
        || participant.getRole() != ParticipantRole.CO_MODERATOR
        || (!sideRoomExit
            && (member == null
                || !Objects.equals(member.getMatrixUserId(), intent.getMemberId())))) {
      retireStale(group, intent);
      return;
    }

    var supervision =
        group.isMembersOnly() && participant.getChatId() != null
            ? supervisions
                .findActiveForUpdate(participant.getChatId(), participant.getConsultantId())
                .orElse(null)
            : null;
    if (sideRoomExit && !matchesSideExit(supervision, intent)) {
      retireStale(group, intent);
      return;
    }
    var departed = matrix.isOutOfRoom(intent.getRoomId(), intent.getMemberId());
    if (intent.isDispatchSettled() && Boolean.FALSE.equals(departed.orElse(null))) {
      // A later authorised edit can already have rejoined, even if an unrelated step failed.
      intents.delete(intent);
      return;
    }

    var localOnlySupervision =
        supervision != null
            && Objects.equals(supervision.getId(), intent.getLocalSupervisionId())
            && isBlank(supervision.getMatrixRoomId());
    if ((!sideRoomExit && supervision != null && !localOnlySupervision)
        || !departed.orElse(false)) {
      intent.setRetryAt(nextProbe());
      return;
    }
    if (sideRoomExit || localOnlySupervision) {
      revocation.endLocally(supervision);
    }
    if (!sideRoomExit) {
      revocation.handOverAfterVerifiedGroupDeparture(group, participant);
      participants.delete(participant);
    }
    intents.delete(intent);
  }

  private static boolean matchesSideExit(
      SessionSupervisor supervision, GroupRosterRemovalIntent intent) {
    return supervision != null
        && Objects.equals(supervision.getId(), intent.getSupervisionId())
        && Objects.equals(supervision.getMatrixRoomId(), intent.getRoomId())
        && supervision.getSupervisorConsultant() != null
        && Objects.equals(
            supervision.getSupervisorConsultant().getMatrixUserId(), intent.getMemberId());
  }

  private void retireStale(Chat group, GroupRosterRemovalIntent intent) {
    // Retain unresolved side exits and departures from the group's current room. A replacement
    // may use the same Matrix account and room while the old leave is still in flight.
    var mayBeReused =
        intent.getSupervisionId() != null
            || (group != null && Objects.equals(group.getMatrixRoomId(), intent.getRoomId()));
    if (!intent.isDispatchSettled() && mayBeReused && !hasDeparted(intent)) {
      intent.setRetryAt(nextProbe());
      return;
    }
    intents.delete(intent);
  }

  private boolean hasDeparted(GroupRosterRemovalIntent intent) {
    return matrix.isOutOfRoom(intent.getRoomId(), intent.getMemberId()).orElse(false);
  }

  private static boolean matches(GroupScope group, GroupRosterRemovalIntent intent) {
    return Objects.equals(group.id(), intent.getSeriesId())
        && Objects.equals(group.ownerId(), intent.getOwnerId())
        && Objects.equals(group.tenantId(), intent.getTenantId())
        && Objects.equals(group.roomId(), intent.getSeriesRoomId())
        && group.occurrenceIndex() == intent.getOccurrenceIndex();
  }

  private LocalDateTime nextProbe() {
    return LocalDateTime.now(clock).plusMinutes(1);
  }
}
