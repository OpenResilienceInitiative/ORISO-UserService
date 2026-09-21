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
 * The topic permission of an invited counsellor (ORISO-Admin#1026, slice 6).
 *
 * <p>Wraps invite creation instead of living inside {@link AccountInviteService}, so the invite
 * lifecycle stays untouched: the permission is decided (and validated) BEFORE the invite is
 * created, then written onto the created row.
 *
 * <ul>
 *   <li>The admin's explicit choice wins. Without one, an existing agency's default applies;
 *       agencies that existed before the setting report {@link TopicPermission#CREATE}, so nothing
 *       changes for them.
 *   <li>The founder of a NEW agency (reserved agency ID) gets {@link TopicPermission#CREATE}: the
 *       agency has no departments yet, so the founder has to bring its topics.
 *   <li>Non-counsellor invites store {@link TopicPermission#NONE}; the value means nothing for
 *       them.
 *   <li>A counsellor must always be able to end up with at least one topic: {@code NONE} and {@code
 *       SELECT_EXISTING} are refused (400) when there is neither an assigned department nor an
 *       agency topic to pick.
 * </ul>
 *
 * <p>{@code NONE} without an assigned department means the counsellor picks exactly one of the
 * agency's departments during onboarding; with one, that department is fixed.
 */
@Service
@RequiredArgsConstructor
public class AccountInviteTopicPermissionService {

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull AgencyTopicPermissionLookup agencyTopicPermissionLookup;

  /** Creates the invite (not sent) with its topic permission. */
  public AccountInvite createInvite(CreateAccountInviteCommand command, TopicPermission requested) {
    TopicPermission permission = decide(command, requested);
    return store(accountInviteService.createInvite(command), permission);
  }

  /** Creates and sends the invite with its topic permission. */
  public InviteSendResult createAndSendInvite(
      CreateAccountInviteCommand command, Long templateId, TopicPermission requested) {
    TopicPermission permission = decide(command, requested);
    InviteSendResult result = accountInviteService.createAndSendInvite(command, templateId);
    if (result.invite() != null) {
      store(result.invite(), permission);
    }
    return result;
  }

  /**
   * Changes the permission from the invite table — before and after the account exists. Allowed for
   * every admin who may act on the invite (same scope as send/revoke); the counsellor created from
   * the invite follows.
   */
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
    if (permission != TopicPermission.CREATE && invite.getDepartmentId() == null) {
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
    if (command == null || command.targetRole() != AccountInviteTargetRole.COUNSELLOR) {
      return TopicPermission.NONE;
    }
    Optional<AgencyTopicSettings> agency = existingAgency(command);
    TopicPermission permission =
        requested != null
            ? requested
            : agency.map(AgencyTopicSettings::defaultPermission).orElse(TopicPermission.CREATE);
    if (permission != TopicPermission.CREATE && command.departmentId() == null) {
      List<Long> agencyTopics = agency.map(AgencyTopicSettings::topicIds).orElse(List.of());
      if (agencyTopics.isEmpty()) {
        throw noTopicToPick(permission);
      }
    }
    return permission;
  }

  /** The invite's agency when it already exists; a reserved (new) agency ID has no settings yet. */
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
