package de.caritas.cob.userservice.api.service.chat;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Takes everything a participant of a members-only chat holds besides the participation itself, in
 * one place, whenever they leave or are removed (remove, leave, owner edit):
 *
 * <ul>
 *   <li>the technical session of the chat, when it is assigned to them, goes to a remaining
 *       participant, an owner first;
 *   <li>their active supervision of that session ends, and they leave the supervision side room,
 *       which is a separate Matrix room the group's own leave does not touch.
 * </ul>
 *
 * <p>{@link MembersOnlyChatAccess} already refuses a counsellor who is not a participant, so none
 * of these rows grants anything on its own any more; they are ended so that no list, notification
 * or Matrix room still points at someone who left. The Matrix leaves are strict, like the leave of
 * the group's room itself: when one fails the removal answers 502 and rolls back, so no one is
 * revoked in the database yet still joined in an encrypted room.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MembersOnlyRevocation {

  private final SessionRepository sessionRepository;
  private final ConsultantRepository consultantRepository;
  private final GroupChatParticipantRepository participantRepository;
  private final SessionSupervisorRepository supervisorRepository;
  private final GroupChatMembershipService membershipService;
  private final ChatRepository chatRepository;
  private final MatrixChatShutdownService shutdownService;

  private org.springframework.jdbc.core.JdbcTemplate preDeletionJdbc;

  @org.springframework.beans.factory.annotation.Autowired
  public void configurePreDeletionJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
    preDeletionJdbc = java.util.Objects.requireNonNull(jdbc);
  }

  /** The actual ACK and complete related rows bind every remote room and final local change. */
  public PreparedRemoval prepareAcknowledgedDeletion(
      de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
              .AcknowledgedPreDeletion
          acknowledged,
      boolean forceDeleteSessions) {
    acknowledged.requireConsultantCurrent();
    if (preDeletionJdbc == null)
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    var prepared = new java.util.concurrent.atomic.AtomicReference<PreparedRemoval>();
    acknowledged.runOwnedLocal(
        () ->
            TenantContext.supplyAcrossTenants(
                () -> {
                  var actual =
                      consultantRepository.findById(acknowledged.accountId()).orElseThrow();
                  acknowledged.requireCurrentConsultant(actual);
                  var rows = PreDeletionChatRows.capture(preDeletionJdbc, actual.getId());
                  var remotes = remoteRemovals(actual, forceDeleteSessions);
                  var originalSessions =
                      sessionRepository
                          .findByConsultantId(
                              actual.getId(), org.springframework.data.domain.Pageable.unpaged())
                          .getContent()
                          .stream()
                          .map(Session::getId)
                          .collect(java.util.stream.Collectors.toUnmodifiableSet());
                  var proof =
                      new PreparedRemoval(
                          this, acknowledged, rows, remotes, originalSessions, forceDeleteSessions);
                  acknowledged.freezeRelatedRemoval(proof);
                  prepared.set(proof);
                  return null;
                }));
    var proof = prepared.get();
    for (var remote : proof.remotes) {
      proof.requireFresh();
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.exception.httpresponses
            .ServiceUnavailableException("Room removal must run outside SQL");
      if (remote.kind.equals("SIDE"))
        membershipService.removeMemberFromRoomOrFail(remote.room, remote.member);
      else {
        var chat = new Chat();
        chat.setId(remote.chat);
        chat.setMatrixRoomId(remote.room);
        chat.setMembersOnly(true);
        if (remote.kind.equals("PURGE")) shutdownService.shutdownRoom(chat);
        else membershipService.removeParticipantFromRoom(chat, remote.member);
      }
      proof.requireFresh();
    }
    proof.remoteComplete = true;
    return proof;
  }

  private record RemoteRemoval(String kind, Long chat, String room, String member) {}

  private List<RemoteRemoval> remoteRemovals(Consultant leaving, boolean force) {
    var remotes = new LinkedHashSet<RemoteRemoval>();
    if (force)
      for (var session :
          sessionRepository.findByConsultantAndStatusIn(
              leaving, List.of(SessionStatus.IN_PROGRESS, SessionStatus.IN_ARCHIVE))) {
        var participants = participantRepository.findByChatId(session.getId());
        if (membersOnlyCase(session)
            && !participants.isEmpty()
            && successorOf(participants, leaving.getId()).isEmpty())
          supervisorRepository
              .findBySessionIdAndIsActiveTrue(session.getId())
              .forEach(value -> addSide(remotes, value));
      }
    for (var participation : participantRepository.findByConsultantId(leaving.getId())) {
      var chat =
          chatRepository
              .findById(participation.getSeriesId())
              .filter(Chat::isMembersOnly)
              .orElse(null);
      if (chat == null) continue;
      supervisorRepository
          .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
              participation.getChatId(), leaving.getId())
          .ifPresent(value -> addSide(remotes, value));
      if (ownedBy(chat, participation) && nextOwnerOf(chat, leaving.getId()).isEmpty()) {
        for (var session : casesOf(chat))
          supervisorRepository
              .findBySessionIdAndIsActiveTrue(session.getId())
              .forEach(value -> addSide(remotes, value));
        remotes.add(new RemoteRemoval("PURGE", chat.getId(), chat.getMatrixRoomId(), null));
      } else
        remotes.add(
            new RemoteRemoval(
                "MAIN", chat.getId(), chat.getMatrixRoomId(), leaving.getMatrixUserId()));
    }
    return remotes.stream()
        .sorted(
            Comparator.comparingInt(
                    (RemoteRemoval value) ->
                        value.kind.equals("SIDE") ? 0 : value.kind.equals("MAIN") ? 1 : 2)
                .thenComparing(
                    RemoteRemoval::chat, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(
                    RemoteRemoval::room, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(
                    RemoteRemoval::member, Comparator.nullsFirst(Comparator.naturalOrder())))
        .toList();
  }

  private static void addSide(LinkedHashSet<RemoteRemoval> remotes, SessionSupervisor supervision) {
    if (supervision.getMatrixRoomId() != null
        && !supervision.getMatrixRoomId().isBlank()
        && supervision.getSupervisorConsultant() != null)
      remotes.add(
          new RemoteRemoval(
              "SIDE",
              null,
              supervision.getMatrixRoomId(),
              supervision.getSupervisorConsultant().getMatrixUserId()));
  }

  private List<Session> casesOf(Chat chat) {
    var ids = new LinkedHashSet<Long>();
    participantRepository.findBySeriesId(chat.getId()).stream()
        .map(GroupChatParticipant::getChatId)
        .filter(Objects::nonNull)
        .forEach(ids::add);
    if (chat.getMatrixRoomId() != null)
      sessionRepository
          .findByMatrixRoomId(chat.getMatrixRoomId())
          .map(Session::getId)
          .ifPresent(ids::add);
    return ids.stream().map(sessionRepository::findById).flatMap(Optional::stream).toList();
  }

  private boolean membersOnlyCase(Session session) {
    if (session.getMatrixRoomId() == null
        || session.getMatrixRoomId().isBlank()
        || session.getConversationType() != null
            && session.getConversationType()
                != de.caritas.cob.userservice.api.model.ConversationType.INTERNAL_GROUP)
      return false;
    return chatRepository.existsByMatrixRoomIdAndMembersOnlyTrue(session.getMatrixRoomId())
        || session.getConversationType()
                == de.caritas.cob.userservice.api.model.ConversationType.INTERNAL_GROUP
            && !session.isTeamSession()
            && !chatRepository.existsByMatrixRoomId(session.getMatrixRoomId());
  }

  public static final class PreparedRemoval {
    private final MembersOnlyRevocation issuer;
    private final de.caritas.cob.userservice.api.workflow.delete.service
            .PublicPreDeletionMaintenance.AcknowledgedPreDeletion
        acknowledged;
    private final java.util.Map<String, List<List<String>>> rows;
    private final List<RemoteRemoval> remotes;
    private final java.util.Set<Long> originalSessions;
    private final boolean force;
    private boolean remoteComplete, readyForLocal, applied;

    private PreparedRemoval(
        MembersOnlyRevocation issuer,
        de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                .AcknowledgedPreDeletion
            acknowledged,
        java.util.Map<String, List<List<String>>> rows,
        List<RemoteRemoval> remotes,
        java.util.Set<Long> originalSessions,
        boolean force) {
      this.issuer = issuer;
      this.acknowledged = acknowledged;
      this.rows = rows;
      this.remotes = remotes;
      this.originalSessions = originalSessions;
      this.force = force;
    }

    private void requireRows() {
      acknowledged.requireConsultantCurrent();
      if (!org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()
          || !rows.equals(
              PreDeletionChatRows.capture(issuer.preDeletionJdbc, acknowledged.accountId())))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "The owned chat source changed");
    }

    private void requireFresh() {
      acknowledged.runOwnedLocal(
          () -> {
            requireRows();
            acknowledged.requireFrozenRelatedRemoval(this);
          });
    }

    /** Data is recomputed only from this issuer's complete rows and original remote inventory. */
    public com.fasterxml.jackson.databind.JsonNode frozenPlanFor(
        de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                .AcknowledgedPreDeletion
            frame,
        de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec codec) {
      if (frame != acknowledged)
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Foreign related preparation");
      requireRows();
      var summaries = new java.util.TreeMap<String, Object>();
      rows.forEach(
          (table, values) ->
              summaries.put(
                  table,
                  java.util.Map.of(
                      "count",
                      (long) values.size() - 1,
                      "digest",
                      codec.digest(
                          "public-pre-deletion-related/v1",
                          java.util.Map.of("table", table, "rows", values)))));
      var inventory =
          remotes.stream()
              .map(
                  remote -> {
                    var value = new java.util.TreeMap<String, Object>();
                    value.put("kind", remote.kind);
                    value.put("chatId", remote.chat);
                    value.put("roomId", remote.room);
                    value.put("memberId", remote.member);
                    return value;
                  })
              .toList();
      return codec.read(
          codec.canonical(
              java.util.Map.of(
                  "state",
                  "FROZEN",
                  "rows",
                  summaries,
                  "remotes",
                  java.util.Map.of(
                      "count",
                      (long) inventory.size(),
                      "digest",
                      codec.digest(
                          "public-pre-deletion-related/v1",
                          java.util.Map.of("remotes", inventory))))),
          com.fasterxml.jackson.databind.JsonNode.class);
    }

    public void requireRequestFor(
        de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                .AcknowledgedPreDeletion
            frame,
        boolean originalForce) {
      if (frame != acknowledged || force != originalForce)
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Foreign original deletion request");
    }

    public void requireReadyFor(
        de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                .AcknowledgedPreDeletion
            frame) {
      acknowledged.requireRelatedFinal(this);
      if (frame != acknowledged || !remoteComplete || applied)
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Related preparation is not ready");
      requireRows();
      readyForLocal = true;
    }

    public void requireAppliedFor(
        de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                .AcknowledgedPreDeletion
            frame) {
      acknowledged.requireRelatedFinal(this);
      if (frame != acknowledged || !readyForLocal || !applied)
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Related preparation was not applied");
    }

    public void apply(Consultant independentlyLoadedOriginal) {
      acknowledged.requireRelatedFinal(this);
      acknowledged.requireCurrentConsultant(independentlyLoadedOriginal);
      if (!remoteComplete || !readyForLocal || applied)
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "The room preparation was already consumed");
      TenantContext.supplyAcrossTenants(
          () -> {
            if (force)
              for (var session :
                  issuer.sessionRepository.findByConsultantAndStatusIn(
                      independentlyLoadedOriginal,
                      List.of(SessionStatus.IN_PROGRESS, SessionStatus.IN_ARCHIVE)))
                if (issuer.membersOnlyCase(session))
                  issuer.keepCaseLocally(session, independentlyLoadedOriginal);
            issuer.revokeAllLocally(independentlyLoadedOriginal);
            return null;
          });
      applied = true;
    }

    public boolean keepCaseOnDeletionOf(Session session, Consultant independentlyLoadedOriginal) {
      acknowledged.requireRelatedFinal(this);
      acknowledged.requireCurrentConsultant(independentlyLoadedOriginal);
      if (!remoteComplete
          || !applied
          || !force
          || session == null
          || !originalSessions.contains(session.getId())
          || issuer.sessionRepository.findById(session.getId()).orElse(null) != session
          || session.getConsultant() == null
          || !independentlyLoadedOriginal.getId().equals(session.getConsultant().getId()))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "The original case changed");
      return issuer.keepCaseLocally(session, independentlyLoadedOriginal);
    }

    @Override
    public String toString() {
      return "PreparedRemoval[ownership=redacted]";
    }
  }

  private void revokeAllLocally(Consultant leaving) {
    for (var participation : participantRepository.findByConsultantId(leaving.getId())) {
      var chat =
          chatRepository
              .findById(participation.getSeriesId())
              .filter(Chat::isMembersOnly)
              .orElse(null);
      if (chat == null) continue;
      supervisorRepository
          .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
              participation.getChatId(), leaving.getId())
          .ifPresent(this::endLocally);
      handOverSessionFrom(chat, participation);
      participantRepository.delete(participation);
      if (ownedBy(chat, participation) && !passOwnershipOn(chat, leaving.getId())) {
        for (var session : casesOf(chat)) closeLocally(session);
        participantRepository.deleteBySeriesId(chat.getId());
        chatRepository.delete(chat);
      }
    }
  }

  private boolean keepCaseLocally(Session session, Consultant leaving) {
    var participants = participantRepository.findByChatId(session.getId());
    if (participants.isEmpty()) return false;
    var successor = successorOf(participants, leaving.getId());
    if (successor.isPresent()) {
      session.setConsultant(successor.get());
      sessionRepository.save(session);
    } else closeLocally(session);
    return true;
  }

  private void closeLocally(Session session) {
    supervisorRepository.findBySessionIdAndIsActiveTrue(session.getId()).forEach(this::endLocally);
    session.setStatus(SessionStatus.DONE);
    session.setUpdateDate(LocalDateTime.now());
    sessionRepository.save(session);
  }

  private void endLocally(SessionSupervisor supervision) {
    supervision.setIsActive(false);
    supervision.setRemovedDate(LocalDateTime.now());
    supervisorRepository.save(supervision);
  }

  /**
   * Call before the participation of {@code leaving} is deleted, in the same transaction. Does
   * nothing for an ordinary chat.
   */
  public void revokeAccessOf(Chat series, GroupChatParticipant leaving) {
    if (series == null
        || !series.isMembersOnly()
        || leaving == null
        || leaving.getChatId() == null) {
      return;
    }
    TenantContext.supplyAcrossTenants(
        () -> {
          endSupervisionBy(leaving);
          handOverSessionFrom(series, leaving);
          return null;
        });
  }

  /**
   * Call when a members-only chat is deleted, before its participations are, in the same
   * transaction: the technical session of the chat is closed and every supervision of it ends, so
   * nobody keeps an in-progress case that points at a room that no longer exists. Does nothing for
   * an ordinary chat.
   */
  public void retireCaseOf(Chat series) {
    if (series == null || !series.isMembersOnly()) {
      return;
    }
    TenantContext.supplyAcrossTenants(
        () -> {
          var sessionIds = new LinkedHashSet<Long>();
          participantRepository.findBySeriesId(series.getId()).stream()
              .map(GroupChatParticipant::getChatId)
              .filter(Objects::nonNull)
              .forEach(sessionIds::add);
          if (series.getMatrixRoomId() != null) {
            sessionRepository
                .findByMatrixRoomId(series.getMatrixRoomId())
                .map(Session::getId)
                .ifPresent(sessionIds::add);
          }
          sessionIds.stream()
              .map(sessionRepository::findById)
              .flatMap(Optional::stream)
              .forEach(this::close);
          return null;
        });
  }

  private void close(Session session) {
    supervisorRepository.findBySessionIdAndIsActiveTrue(session.getId()).forEach(this::end);
    session.setStatus(SessionStatus.DONE);
    session.setUpdateDate(LocalDateTime.now());
    sessionRepository.save(session);
  }

  private void handOverSessionFrom(Chat series, GroupChatParticipant leaving) {
    var session = sessionRepository.findById(leaving.getChatId()).orElse(null);
    if (session == null || !session.isAdvisedBy(leaving.getConsultantId())) {
      return;
    }
    successorOf(participantRepository.findBySeriesId(series.getId()), leaving.getConsultantId())
        .ifPresent(
            successor -> {
              session.setConsultant(successor);
              sessionRepository.save(session);
            });
  }

  /** The remaining active participant who takes a case over, an owner first. */
  private Optional<Consultant> successorOf(
      List<GroupChatParticipant> participants, String leavingConsultantId) {
    return participants.stream()
        .filter(member -> !leavingConsultantId.equals(member.getConsultantId()))
        .sorted(Comparator.comparing(member -> member.getRole() == ParticipantRole.OWNER ? 0 : 1))
        .map(member -> consultantRepository.findByIdAndDeleteDateIsNull(member.getConsultantId()))
        .flatMap(Optional::stream)
        .findFirst();
  }

  /**
   * Call when a consultant is marked for deletion, inside that transaction and while their chat
   * account still works: they leave every members-only chat they take part in exactly as if they
   * had left or been removed (supervision and side room first, the participation, the main room
   * last). A Matrix leave that fails answers 502 and rolls the whole deletion back, so a departing
   * counsellor never keeps reading private rooms until the account is finally deleted. Ordinary
   * chats are left alone.
   */
  public void revokeAllAccessOf(Consultant leaving) {
    if (leaving == null || leaving.getId() == null) {
      return;
    }
    var participations =
        TenantContext.supplyAcrossTenants(
            () -> participantRepository.findByConsultantId(leaving.getId()));
    for (var participation : participations) {
      var series =
          TenantContext.supplyAcrossTenants(
                  () -> chatRepository.findById(participation.getSeriesId()))
              .filter(Chat::isMembersOnly);
      if (series.isEmpty()) {
        continue;
      }
      var chat = series.get();
      revokeAccessOf(chat, participation);
      participantRepository.delete(participation);
      if (ownedBy(chat, participation) && !passOwnershipOn(chat, leaving.getId())) {
        // Nobody is left to own it: the chat is retired like a deleted one, the strict purge of
        // its room (which removes the leaving member as well) last.
        retire(chat);
        continue;
      }
      membershipService.removeParticipantFromRoom(chat, leaving.getMatrixUserId());
    }
  }

  /**
   * Call when an owner of a chat leaves or is deleted, in the same transaction: the remaining
   * active participant who takes the chat over (an owner, else a co-moderator, else the
   * longest-standing participant, the lowest id) becomes {@link Chat#getChatOwner()} when the
   * leaving consultant was it, and an OWNER when no owner is left. Database only, like a transfer
   * of primary ownership; Matrix power levels are not touched.
   *
   * @return {@code false} when nobody active remains to own the chat
   */
  public boolean passOwnershipOn(Chat chat, String leavingConsultantId) {
    var next = nextOwnerOf(chat, leavingConsultantId);
    if (next.isEmpty()) {
      return false;
    }
    var successor = next.get();
    TenantContext.supplyAcrossTenants(
        () -> {
          if (successor.participation().getRole() != ParticipantRole.OWNER) {
            successor.participation().setRole(ParticipantRole.OWNER);
            participantRepository.save(successor.participation());
          }
          var primary = chat.getChatOwner();
          if (primary == null || leavingConsultantId.equals(primary.getId())) {
            chat.setChatOwner(successor.account());
            chat.setUpdateDate(nowInUtc());
            chatRepository.save(chat);
          }
          return null;
        });
    return true;
  }

  private static boolean ownedBy(Chat chat, GroupChatParticipant participation) {
    return participation.getRole() == ParticipantRole.OWNER
        || (chat.getChatOwner() != null
            && participation.getConsultantId().equals(chat.getChatOwner().getId()));
  }

  /**
   * The remaining active participant who takes a chat over: an owner, else a co-moderator, else the
   * longest-standing participant (the lowest id). Same database-only change as a transfer of
   * primary ownership; Matrix power levels are not touched.
   */
  private Optional<OwnerCandidate> nextOwnerOf(Chat chat, String leavingConsultantId) {
    return TenantContext.supplyAcrossTenants(
        () ->
            participantRepository.findBySeriesId(chat.getId()).stream()
                .filter(member -> !leavingConsultantId.equals(member.getConsultantId()))
                .sorted(
                    Comparator.comparing((GroupChatParticipant member) -> rank(member.getRole()))
                        .thenComparing(
                            GroupChatParticipant::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                .flatMap(
                    member ->
                        consultantRepository
                            .findByIdAndDeleteDateIsNull(member.getConsultantId())
                            .map(account -> new OwnerCandidate(member, account))
                            .stream())
                .findFirst());
  }

  private static int rank(ParticipantRole role) {
    return role == ParticipantRole.OWNER ? 0 : role == ParticipantRole.CO_MODERATOR ? 1 : 2;
  }

  private void retire(Chat chat) {
    TenantContext.supplyAcrossTenants(
        () -> {
          retireCaseOf(chat);
          participantRepository.deleteBySeriesId(chat.getId());
          chatRepository.delete(chat);
          return null;
        });
    // Last, in the same transaction: a room that cannot be purged answers 502 and rolls the
    // deletion of the consultant back.
    shutdownService.shutdownRoom(chat);
  }

  private record OwnerCandidate(GroupChatParticipant participation, Consultant account) {}

  /**
   * Call when a consultant is deleted while still the assignee of the technical session of a
   * members-only chat: the case goes to a remaining active participant, an owner first, and is
   * retired (closed, its supervisions ended) when nobody active remains, instead of being deleted
   * under a chat that still exists.
   *
   * @return {@code false} when the session has no participants, so there is no chat to keep it for;
   *     the caller then treats it like any other session
   */
  public boolean keepCaseOnDeletionOf(Session session, Consultant leaving) {
    if (session == null || session.getId() == null || leaving == null) {
      return false;
    }
    return TenantContext.supplyAcrossTenants(
        () -> {
          var participants = participantRepository.findByChatId(session.getId());
          if (participants.isEmpty()) {
            return false;
          }
          var successor = successorOf(participants, leaving.getId());
          if (successor.isPresent()) {
            session.setConsultant(successor.get());
            sessionRepository.save(session);
          } else {
            close(session);
          }
          return true;
        });
  }

  /**
   * A participant who was added as supervisor of the session reads it through that relation as
   * well; it ends with the participation.
   */
  private void endSupervisionBy(GroupChatParticipant leaving) {
    supervisorRepository
        .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
            leaving.getChatId(), leaving.getConsultantId())
        .ifPresent(this::end);
  }

  private void end(SessionSupervisor supervision) {
    supervision.setIsActive(false);
    supervision.setRemovedDate(LocalDateTime.now());
    supervisorRepository.save(supervision);
    leaveSideRoom(supervision);
  }

  /**
   * Inside the transaction, strictly: a supervisor who stays in the side room keeps reading it
   * while the database says the supervision ended. A failed leave answers 502 and rolls the whole
   * removal back, so it can be repeated.
   */
  private void leaveSideRoom(SessionSupervisor supervision) {
    var sideRoomId = supervision.getMatrixRoomId();
    var supervisor = supervision.getSupervisorConsultant();
    if (sideRoomId == null || sideRoomId.isBlank() || supervisor == null) {
      return;
    }
    membershipService.removeMemberFromRoomOrFail(sideRoomId, supervisor.getMatrixUserId());
  }
}
