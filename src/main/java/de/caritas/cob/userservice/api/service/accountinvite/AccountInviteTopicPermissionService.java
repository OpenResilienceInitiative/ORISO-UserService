package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyTopicPermissionLookup.AgencyTopicSettings;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wraps invite creation so the invite lifecycle stays untouched: the permission is validated before
 * the invite exists, then written onto the created row.
 */
@Service
@RequiredArgsConstructor
public class AccountInviteTopicPermissionService {

  /** Must match what AgencyService writes onto every newly created agency. */
  static final TopicPermission NEW_AGENCY_DEFAULT = TopicPermission.NONE;

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull AgencyTopicPermissionLookup agencyTopicPermissionLookup;

  public AccountInvite createInvite(CreateAccountInviteCommand command, TopicPermission requested) {
    TopicPermission permission = decide(command, requested);
    return store(accountInviteService.createInvite(command), permission);
  }

  public InviteSendResult createAndSendInvite(
      CreateAccountInviteCommand command, Long templateId, TopicPermission requested) {
    TopicPermission permission = decide(command, requested);
    InviteSendResult result = accountInviteService.createAndSendInvite(command, templateId);
    if (result.invite() != null) {
      store(result.invite(), permission);
    }
    return result;
  }

  /** Also after the account exists; the counsellor created from the invite follows. */
  @Transactional
  public AccountInvite updatePermission(Long inviteId, TopicPermission permission) {
    if (inviteId == null) {
      throw new BadRequestException("inviteId is required");
    }
    AccountInvite invite =
        accountInviteRepository
            .findById(inviteId)
            .orElseThrow(() -> new NotFoundException("Account invite not found"));
    accessPolicy.authorizeAccess(invite);
    if (permission == null) {
      throw new BadRequestException("topicPermission is required");
    }
    if (invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw new BadRequestException("Only counsellor invites carry a topic permission");
    }
    // A waiting invite's new agency has no topics yet; its admin brings them before release.
    boolean waitsForNewAgency =
        invite.getStatus() == AccountInviteStatus.WAITING_FOR_UNIT
            && invite.getWaitingForUnit() == InviteUnitType.AGENCY;
    if (permission != TopicPermission.CREATE
        && invite.getDepartmentId() == null
        && !waitsForNewAgency) {
      boolean agencyHasTopics =
          invite.getAgencyId() != null
              && agencyTopicPermissionLookup
                  .find(invite.getAgencyId())
                  .map(settings -> !settings.topicIds().isEmpty())
                  .orElse(false);
      if (!agencyHasTopics) {
        throw noTopicToPick(permission);
      }
    }
    invite.setTopicPermission(permission);
    invite.setUpdateDate(LocalDateTime.now());
    AccountInvite saved = accountInviteRepository.save(invite);
    if (invite.getProvisionedUserId() != null) {
      consultantRepository
          .findByIdAndDeleteDateIsNull(invite.getProvisionedUserId())
          .ifPresent(
              consultant -> {
                consultant.setTopicPermission(permission);
                consultantRepository.save(consultant);
              });
    }
    return saved;
  }

  private TopicPermission decide(CreateAccountInviteCommand command, TopicPermission requested) {
    if (command != null && command.targetRole() == AccountInviteTargetRole.AGENCY_ADMIN) {
      // A founding agency admin who also counsels has to bring the agency's topics.
      return TopicPermission.CREATE;
    }
    if (command == null || command.targetRole() != AccountInviteTargetRole.COUNSELLOR) {
      return TopicPermission.NONE;
    }
    Optional<AgencyTopicSettings> agency = existingAgency(command);
    // A new agency does not exist yet: use the default it will be created with, and skip the
    // topic check (its admin brings the topics before the invite is released).
    boolean waitsForNewAgency = IdAllocationMode.reservesAnId(command.agencyIdAllocationMode());
    TopicPermission permission;
    if (requested != null) {
      permission = requested;
    } else if (waitsForNewAgency) {
      permission = NEW_AGENCY_DEFAULT;
    } else {
      permission =
          agency.map(AgencyTopicSettings::defaultPermission).orElse(TopicPermission.CREATE);
    }
    if (permission != TopicPermission.CREATE
        && command.departmentId() == null
        && !waitsForNewAgency) {
      List<Long> agencyTopics = agency.map(AgencyTopicSettings::topicIds).orElse(List.of());
      if (agencyTopics.isEmpty()) {
        throw noTopicToPick(permission);
      }
    }
    return permission;
  }

  /** A reserved (new) agency ID has no settings yet. */
  private Optional<AgencyTopicSettings> existingAgency(CreateAccountInviteCommand command) {
    if (command.agencyId() == null
        || IdAllocationMode.reservesAnId(command.agencyIdAllocationMode())) {
      return Optional.empty();
    }
    return agencyTopicPermissionLookup.find(command.agencyId());
  }

  private AccountInvite store(AccountInvite invite, TopicPermission permission) {
    invite.setTopicPermission(permission);
    accountInviteRepository.save(invite);
    return invite;
  }

  private static BadRequestException noTopicToPick(TopicPermission permission) {
    return new BadRequestException(
        "topicPermission "
            + permission
            + " needs a departmentId or an agency with at least one topic — otherwise the"
            + " counsellor could not choose any topic");
  }
}
