package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes and reads an invited counsellor's topic permission. Once the account exists, the
 * counsellor's own value is the only one; the invite just shows it.
 */
@Service
@RequiredArgsConstructor
public class AccountInviteTopicPermissionService {

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull AgencyFacts agencyFacts;

  /** Before and after the account exists; same scope as send and revoke. */
  @Transactional
  public AccountInvite updatePermission(Long inviteId, TopicPermission permission) {
    if (inviteId == null) {
      throw new BadRequestException("inviteId is required");
    }
    // Locked, so a racing accept or revoke is waited for and never written over.
    AccountInvite invite = InviteRowHold.lock(accountInviteRepository, inviteId).orElse(null);
    if (invite == null) {
      accessPolicy.authorizeMissing(inviteId);
      throw new NotFoundException("Account invite not found");
    }
    accessPolicy.authorizeAccess(invite);
    if (permission == null) {
      throw new BadRequestException("topicPermission is required");
    }
    if (invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw new BadRequestException("Only counsellor invites carry a topic permission");
    }
    boolean waitsForNewAgency =
        invite.getStatus() == AccountInviteStatus.WAITING_FOR_UNIT
            && invite.getWaitingForUnit() == InviteUnitType.AGENCY;
    if (permission != TopicPermission.CREATE
        && invite.getDepartmentId() == null
        && !waitsForNewAgency) {
      TopicPermissionPolicy.requireATopicToPick(
          permission, null, false, agencyTopics(invite.getAgencyId()));
    }
    Optional<Consultant> account = account(invite);
    if (account.isPresent()) {
      account.get().setTopicPermission(permission);
      consultantRepository.save(account.get());
      return invite;
    }
    invite.setTopicPermission(permission);
    invite.setUpdateDate(LocalDateTime.now());
    return accountInviteRepository.save(invite);
  }

  /** The permission each invite shows: its counsellor's once the account exists. One query. */
  public Map<Long, TopicPermission> currentPermissions(Collection<AccountInvite> invites) {
    List<String> accountIds =
        invites.stream().map(AccountInvite::getProvisionedUserId).filter(Objects::nonNull).toList();
    Map<String, TopicPermission> byAccount = new HashMap<>();
    if (!accountIds.isEmpty()) {
      consultantRepository.findAllById(accountIds).stream()
          .filter(consultant -> consultant.getDeleteDate() == null)
          .forEach(
              consultant -> byAccount.put(consultant.getId(), consultant.getTopicPermission()));
    }
    Map<Long, TopicPermission> current = new HashMap<>();
    for (AccountInvite invite : invites) {
      TopicPermission permission =
          invite.getProvisionedUserId() == null
              ? null
              : byAccount.get(invite.getProvisionedUserId());
      current.put(invite.getId(), permission != null ? permission : invite.getTopicPermission());
    }
    return current;
  }

  private Optional<Consultant> account(AccountInvite invite) {
    return invite.getProvisionedUserId() == null
        ? Optional.empty()
        : consultantRepository.findByIdAndDeleteDateIsNull(invite.getProvisionedUserId());
  }

  private List<Long> agencyTopics(Long agencyId) {
    return agencyId == null
        ? List.of()
        : agencyFacts.find(agencyId).map(AgencyFacts.Agency::topicIds).orElse(List.of());
  }
}
