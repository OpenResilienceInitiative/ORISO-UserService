package de.caritas.cob.userservice.api.admin.service.agency;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionFailureDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionFailureDTO.KindEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionPreviewDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionResultDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.AdminAgencyRelationService;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Deletes everything that belongs to a Beratungsstelle (ORISO-Admin#1524, rule 4 of the deletion
 * epic ORISO-Admin#1520: cascade instead of blocking). AgencyService takes the Beratungsstelle
 * offline and marks it first, then calls this.
 *
 * <ul>
 *   <li>Invitations and invite links are revoked, so nobody new joins.
 *   <li>Enquiries nobody has taken yet are closed.
 *   <li>Advice seekers whose only Beratungsstelle this is are marked for deletion. Those who also
 *       belong to another keep their account: their sessions here are marked and purged with their
 *       Matrix room at night, and their membership here is removed.
 *   <li>Counsellors whose only Beratungsstelle this is are marked for deletion; the others only
 *       lose this one.
 *   <li>Agency admins lose their assignment to it; their account stays.
 *   <li>Its group chats are deleted with their Matrix rooms: scheduled and running ones, and whole
 *       recurring series, without creating a next occurrence.
 * </ul>
 *
 * <p>Enquiries and advice seekers go before the counsellors: UserService refuses to take the last
 * counsellor off a Beratungsstelle that still has open enquiries. Every child is handled on its
 * own; one that fails is reported and handled on the next call, because every step only picks up
 * what is not done yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencyDeletionCascadeService {

  private final @NonNull AdminScope adminScope;
  private final @NonNull DeletedUnitInviteRevocation inviteRevocation;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull AgencyAdviceSeekerUnlinker adviceSeekerUnlinker;
  private final @NonNull AskerUserAdminFacade askerUserAdminFacade;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull ConsultantAdminFacade consultantAdminFacade;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AdminAgencyRelationService adminAgencyRelationService;
  private final @NonNull AgencyGroupChatDeleter groupChatDeleter;
  private final @NonNull Clock clock;

  /** What deleting the Beratungsstelle would do; shown before the admin confirms. */
  public AgencyDeletionPreviewDTO preview(Long agencyId) {
    assertMay(agencyId);
    var counsellors = splitCounsellors(agencyId);
    return new AgencyDeletionPreviewDTO()
        .adviceSeekers(userRepository.countActiveAdviceSeekersOnlyInAgency(agencyId))
        .keptAdviceSeekerSessions(sessionRepository.countSessionsOfKeptAdviceSeekers(agencyId))
        .openEnquiries(sessionRepository.countUnassignedEnquiriesOfAgency(agencyId))
        .consultantsDeleted((long) counsellors.onlyHere().size())
        .consultantsUnassigned((long) counsellors.alsoElsewhere().size())
        .agencyAdmins((long) adminAgencyRepository.findAgencyAdminIdsByAgencyId(agencyId).size())
        .groupChats(groupChatDeleter.countChatsOf(agencyId));
  }

  /**
   * @param agencyId the Beratungsstelle being deleted
   * @return what this call did, and what it could not
   */
  public AgencyDeletionResultDTO cascade(Long agencyId) {
    assertMay(agencyId);
    var failures = new ArrayList<AgencyDeletionFailureDTO>();
    var now = LocalDateTime.now(clock);
    var result = new AgencyDeletionResultDTO();

    result.invites(
        count(
            KindEnum.INVITES,
            () -> {
              var revoked = inviteRevocation.revokeForAgency(agencyId);
              return (long) revoked.invites() + revoked.links();
            },
            failures));
    result.openEnquiries(
        count(
            KindEnum.ENQUIRIES,
            () -> (long) sessionRepository.closeUnassignedEnquiriesOfAgency(agencyId, now),
            failures));
    handleAdviceSeekers(agencyId, now, result, failures);

    Counsellors counsellors;
    try {
      counsellors = splitCounsellors(agencyId);
    } catch (RuntimeException e) {
      failures.add(failure(KindEnum.CONSULTANT, null, e));
      counsellors = new Counsellors(List.of(), List.of());
    }
    var onlyHere = counsellors.onlyHere();
    result.consultantsDeleted(
        forEach(
            KindEnum.CONSULTANT,
            () -> onlyHere,
            id -> consultantAdminFacade.markConsultantForDeletion(id, true),
            failures));
    var alsoElsewhere = counsellors.alsoElsewhere();
    result.consultantsUnassigned(
        forEach(
            KindEnum.CONSULTANT_RELATION,
            () -> alsoElsewhere,
            id -> consultantAdminFacade.markConsultantAgencyForDeletion(id, agencyId),
            failures));

    result.agencyAdmins(
        forEach(
            KindEnum.AGENCY_ADMIN,
            () -> adminAgencyRepository.findAgencyAdminIdsByAgencyId(agencyId),
            id -> adminAgencyRelationService.deleteAdminAgencyRelation(id, agencyId),
            failures));

    result.groupChats(deleteGroupChats(agencyId, failures));

    if (!failures.isEmpty()) {
      log.warn(
          "Agency {}: {} children could not be handled for deletion; retried on the next call",
          agencyId,
          failures.size());
    }
    return result.failures(failures).complete(failures.isEmpty());
  }

  private void assertMay(Long agencyId) {
    // The Beratungsstelle is already marked in AgencyService when the cascade runs.
    adminScope.assertMay(Target.removedAgencies(List.of(agencyId)));
  }

  private long deleteGroupChats(Long agencyId, List<AgencyDeletionFailureDTO> failures) {
    try {
      var deleted = groupChatDeleter.deleteChatsOf(agencyId);
      deleted
          .failedChatIds()
          .forEach(
              id ->
                  failures.add(
                      new AgencyDeletionFailureDTO()
                          .kind(KindEnum.GROUP_CHAT)
                          .id(String.valueOf(id))
                          .reason("NotDeleted")));
      return deleted.count();
    } catch (RuntimeException e) {
      failures.add(failure(KindEnum.GROUP_CHAT, null, e));
      return 0L;
    }
  }

  /** Counsellors with an active relation here, split by whether this is their only one. */
  private record Counsellors(List<String> onlyHere, List<String> alsoElsewhere) {}

  private Counsellors splitCounsellors(Long agencyId) {
    // Two id-only queries, whatever the number of counsellors: loading ConsultantAgency entities
    // would also load each eagerly fetched Consultant with its own select.
    var ids =
        new LinkedHashSet<>(consultantAgencyRepository.findActiveConsultantIdsByAgencyId(agencyId));
    if (ids.isEmpty()) {
      return new Counsellors(List.of(), List.of());
    }
    var withOtherAgency = new HashSet<String>();
    consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(List.copyOf(ids)).stream()
        .filter(relation -> !agencyId.equals(relation.getAgencyId()))
        .forEach(relation -> withOtherAgency.add(relation.getConsultantId()));
    var onlyHere = new ArrayList<String>();
    var alsoElsewhere = new ArrayList<String>();
    for (String id : ids) {
      (withOtherAgency.contains(id) ? alsoElsewhere : onlyHere).add(id);
    }
    return new Counsellors(onlyHere, alsoElsewhere);
  }

  /**
   * Each advice seeker linked here is decided on its own, under their row lock: one who belongs
   * only here is marked for deletion and keeps their link here until that succeeded, so a failed
   * mark is found again by the next call; one who belongs elsewhere too loses their sessions and
   * membership here.
   */
  private void handleAdviceSeekers(
      Long agencyId,
      LocalDateTime now,
      AgencyDeletionResultDTO result,
      List<AgencyDeletionFailureDTO> failures) {
    List<String> linked;
    try {
      linked = userRepository.findActiveAdviceSeekerIdsLinkedToAgency(agencyId);
    } catch (RuntimeException e) {
      failures.add(failure(KindEnum.ADVICE_SEEKER, null, e));
      result.adviceSeekers(0L).keptAdviceSeekerSessions(0L);
      return;
    }
    long marked = 0;
    long keptSessions = 0;
    for (String userId : linked) {
      try {
        var outcome = adviceSeekerUnlinker.unlink(userId, agencyId, now);
        if (outcome instanceof AgencyAdviceSeekerUnlinker.OnlyHere) {
          askerUserAdminFacade.markAskerForDeletion(userId);
          marked++;
        } else if (outcome instanceof AgencyAdviceSeekerUnlinker.Kept kept) {
          keptSessions += kept.markedSessions();
        }
      } catch (RuntimeException e) {
        // Ids only: the privacy filter would drop a message carrying personal data.
        log.warn(
            "Could not handle {} {} for agency deletion: {}",
            KindEnum.ADVICE_SEEKER,
            userId,
            reasonOf(e));
        failures.add(failure(KindEnum.ADVICE_SEEKER, userId, e));
      }
    }
    result.adviceSeekers(marked).keptAdviceSeekerSessions(keptSessions);
  }

  private static long count(
      KindEnum kind, Supplier<Long> step, List<AgencyDeletionFailureDTO> failures) {
    try {
      return step.get();
    } catch (RuntimeException e) {
      failures.add(failure(kind, null, e));
      return 0L;
    }
  }

  private static long forEach(
      KindEnum kind,
      Supplier<List<String>> ids,
      Consumer<String> handle,
      List<AgencyDeletionFailureDTO> failures) {
    List<String> found;
    try {
      found = ids.get();
    } catch (RuntimeException e) {
      failures.add(failure(kind, null, e));
      return 0L;
    }
    long handled = 0;
    for (String id : found) {
      try {
        handle.accept(id);
        handled++;
      } catch (RuntimeException e) {
        // Ids only: the privacy filter would drop a message carrying personal data.
        log.warn("Could not handle {} {} for agency deletion: {}", kind, id, reasonOf(e));
        failures.add(failure(kind, id, e));
      }
    }
    return handled;
  }

  private static AgencyDeletionFailureDTO failure(KindEnum kind, String id, RuntimeException e) {
    return new AgencyDeletionFailureDTO().kind(kind).id(id).reason(reasonOf(e));
  }

  /** The X-Reason when there is one (e.g. a refused counsellor), else the exception type. */
  private static String reasonOf(RuntimeException e) {
    if (e instanceof CustomValidationHttpStatusException validation
        && validation.getCustomHttpHeaders() != null
        && validation.getCustomHttpHeaders().getFirst("X-Reason") != null) {
      return validation.getCustomHttpHeaders().getFirst("X-Reason");
    }
    return e.getClass().getSimpleName();
  }
}
