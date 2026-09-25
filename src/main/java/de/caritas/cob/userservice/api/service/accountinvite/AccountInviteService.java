package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteAccessPolicy.InviteListScope;
import de.caritas.cob.userservice.api.service.accountinvite.InviteDelivery.Prepared;
import de.caritas.cob.userservice.api.service.accountinvite.ReservationLedger.Held;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The invite lifecycle: create, send, resend, revoke, expire, accept. Targets, reserved IDs and the
 * queue live in {@link InviteTargetResolver}, {@link ReservationLedger} and {@link UnitQueue}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountInviteService {

  private static final String ACTIVE_RECIPIENT_CONSTRAINT = "idx_account_invite_active_recipient";

  static final long DEFAULT_EXPIRY_DAYS = 30L;

  /**
   * An address is taken while its invite can still be redeemed. ACCEPTED is covered by the identity
   * probe; blocking it here too would make a deleted identity's address un-invitable.
   */
  private static final List<AccountInviteStatus> ADDRESS_HOLDING_INVITE_STATUSES =
      List.of(
          AccountInviteStatus.WAITING_FOR_UNIT,
          AccountInviteStatus.DRAFT,
          AccountInviteStatus.EMAIL_SENT);

  private static final List<AccountInviteStatus> EXPIRABLE_STATUSES =
      List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT);

  /** Everything but ACCEPTED and REVOKED, as before the revoke became conditional. */
  private static final List<AccountInviteStatus> REVOCABLE_STATUSES =
      List.of(
          AccountInviteStatus.WAITING_FOR_UNIT,
          AccountInviteStatus.DRAFT,
          AccountInviteStatus.EMAIL_SENT,
          AccountInviteStatus.EXPIRED,
          AccountInviteStatus.SUPERSEDED);

  private static final int EXPIRY_SWEEP_BATCH = 100;

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull InviteEmailTemplateRepository templateRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull IdentityEmailOwnerLookup identityEmailOwnerLookup;
  private final @NonNull PlatformTransactionManager transactionManager;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull AgencyFacts agencyFacts;
  private final @NonNull InviteTargetResolver targets;
  private final @NonNull ReservationLedger ledger;
  private final @NonNull UnitQueue unitQueue;
  private final @NonNull InviteDelivery delivery;

  @Transactional
  public AccountInvite createInvite(CreateAccountInviteCommand requestedCommand) {
    if (requestedCommand == null) {
      throw new BadRequestException("Request body is required");
    }
    if (requestedCommand.targetRole() == null) {
      throw new BadRequestException("targetRole is required");
    }
    Supplier<Optional<AgencyFacts.Agency>> agency = oneLookupOf(requestedCommand);
    // Cross-Träger guard: the target tenant, agency and role come from the request body.
    CreateAccountInviteCommand command = accessPolicy.authorizeCreate(requestedCommand);
    if (isBlank(command.recipientEmail())) {
      throw new BadRequestException("recipientEmail is required");
    }
    InviteTarget target = targets.resolve(command, agency);
    verifyRecipientEmailAvailable(command.recipientEmail());
    TopicPermission topicPermission =
        TopicPermissionPolicy.decide(
            command.targetRole(),
            agencyForTopics(command, target, agency),
            target.newAgency(),
            target.departmentId(),
            command.topicPermission());
    LocalDateTime now = LocalDateTime.now();
    AccountInvite invite = newInvite(command, target, topicPermission, now);
    if (target.waitsFor() != null) {
      return save(unitQueue.enqueue(invite, target, command.expiresInDays()));
    }
    Held held = ledger.reserve(target);
    try {
      invite.setTenantId(held.tenantId());
      invite.setTenantIdReservationToken(held.tenantToken());
      invite.setAgencyId(held.agencyId());
      invite.setExpiresAt(resolveExpiry(now, command.expiresInDays()));
      invite.setStatus(AccountInviteStatus.DRAFT);
      return save(invite);
    } catch (RuntimeException exception) {
      ledger.undo(held);
      throw exception;
    }
  }

  /**
   * Commits the address claim and usable token before SMTP. Confirmed pre-dispatch failures are
   * compensated; ambiguous transport failures retain the claim so a retry cannot duplicate mail.
   */
  public InviteSendResult createAndSendInvite(CreateAccountInviteCommand command, Long templateId) {
    Prepared prepared;
    try {
      prepared =
          requiresNewTransaction()
              .execute(
                  transaction -> {
                    AccountInvite invite = createInvite(command);
                    InviteEmailTemplate template = findTemplate(templateId);
                    if (invite.getStatus() == AccountInviteStatus.WAITING_FOR_UNIT) {
                      // Stored, not sent: the template goes out with the release.
                      invite.setQueuedTemplateId(template.getId());
                      return new Prepared(
                          accountInviteRepository.saveAndFlush(invite),
                          null,
                          null,
                          null,
                          null,
                          null,
                          null);
                    }
                    LocalDateTime now = LocalDateTime.now();
                    Prepared mail = delivery.prepare(invite, template, now);
                    invite.setTokenHash(mail.tokenHash());
                    if (invite.getExpiresAt() == null || invite.getExpiresAt().isBefore(now)) {
                      invite.setExpiresAt(resolveExpiry(now, DEFAULT_EXPIRY_DAYS));
                    }
                    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
                    invite.setUpdateDate(now);
                    return mail.withInvite(accountInviteRepository.saveAndFlush(invite));
                  });
    } catch (RuntimeException claimFailure) {
      // The rollback already gave the reserved IDs back (ReservationLedger#reserve).
      if (isActiveRecipientConflict(claimFailure)) {
        throw emailNotAvailable(claimFailure);
      }
      throw claimFailure;
    }
    if (prepared.template() == null) {
      return new InviteSendResult(prepared.invite(), null, null, null);
    }
    return delivery.deliver(
        prepared,
        prepared.invite().getId(),
        false,
        sendFailure -> discardUnsentInvite(prepared.invite(), sendFailure));
  }

  /** At most one AgencyService call per request, and only when a rule needs the agency. */
  private Supplier<Optional<AgencyFacts.Agency>> oneLookupOf(CreateAccountInviteCommand command) {
    if (command.agencyId() == null
        || IdAllocationMode.reservesAnId(command.agencyIdAllocationMode())) {
      return Optional::empty;
    }
    Optional<AgencyFacts.Agency>[] cached = new Optional[1];
    return () -> {
      if (cached[0] == null) {
        cached[0] = agencyFacts.find(command.agencyId());
      }
      return cached[0];
    };
  }

  private static AgencyFacts.Agency agencyForTopics(
      CreateAccountInviteCommand command,
      InviteTarget target,
      Supplier<Optional<AgencyFacts.Agency>> agency) {
    if (command.targetRole() != AccountInviteTargetRole.COUNSELLOR || target.newAgency()) {
      return null;
    }
    return agency.get().orElse(null);
  }

  private AccountInvite newInvite(
      CreateAccountInviteCommand command,
      InviteTarget target,
      TopicPermission topicPermission,
      LocalDateTime now) {
    return AccountInvite.builder()
        .targetRole(command.targetRole())
        .tenantId(target.tenantId())
        .recipientEmail(command.recipientEmail().trim())
        .activeRecipientKey(normalizeEmail(command.recipientEmail()))
        .firstName(trimToNull(command.firstName()))
        .lastName(trimToNull(command.lastName()))
        .agencyId(target.agencyId())
        .departmentId(target.departmentId())
        .tenantIdAllocationMode(command.tenantIdAllocationMode())
        .agencyIdAllocationMode(command.agencyIdAllocationMode())
        .alsoCounsellor(alsoCounsellorOf(command))
        .topicPermission(topicPermission)
        .status(AccountInviteStatus.DRAFT)
        .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(defaultTwoFactorStatus(command.targetRole()))
        .createdByUserId(authenticatedUser.getUserId())
        .createdByUsername(authenticatedUser.getUsername())
        .createDate(now)
        .updateDate(now)
        .build();
  }

  private AccountInvite save(AccountInvite invite) {
    try {
      AccountInvite saved = accountInviteRepository.save(invite);
      accountInviteRepository.flush();
      return saved;
    } catch (RuntimeException exception) {
      if (isActiveRecipientConflict(exception)) {
        throw emailNotAvailable(exception);
      }
      throw exception;
    }
  }

  private static Boolean alsoCounsellorOf(CreateAccountInviteCommand command) {
    if (command.targetRole() != AccountInviteTargetRole.AGENCY_ADMIN) {
      return null;
    }
    return !Boolean.FALSE.equals(command.alsoCounsellor());
  }

  /** SMTP confirmed nothing went out: drop the invite and give back the IDs only it needed. */
  private void discardUnsentInvite(AccountInvite invite, SmtpSendException sendFailure) {
    try {
      requiresNewTransaction()
          .executeWithoutResult(
              transaction -> {
                AccountInvite stored = findInvite(invite.getId());
                ledger.releaseUnneeded(stored, LocalDateTime.now());
                accountInviteRepository.deleteById(stored.getId());
                accountInviteRepository.flush();
              });
    } catch (RuntimeException compensationFailure) {
      log.error(
          "Direct invite {} failed before dispatch and its claim cleanup could not be recorded",
          invite.getId(),
          compensationFailure);
      sendFailure.addSuppressed(compensationFailure);
    }
  }

  private TransactionTemplate requiresNewTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }

  /**
   * The address must be free in both halves of the funnel: the identity provider (accounts) and the
   * invite table (promised addresses). Checked on the admin-only create call, so it is no
   * enumeration oracle; the unique active-recipient key closes the race between replicas.
   */
  private void verifyRecipientEmailAvailable(String recipientEmail) {
    String normalized = normalizeEmail(recipientEmail);
    if (identityEmailOwnerLookup.findByEmail(normalized).isPresent()) {
      throw emailNotAvailable(null);
    }
    LocalDateTime now = LocalDateTime.now();
    expireElapsedRecipientClaims(normalized, now);
    if (accountInviteRepository.countNonTerminalInvitesForRecipientEmail(
            normalized, ADDRESS_HOLDING_INVITE_STATUSES, now)
        > 0) {
      // Same reason as the identity hit: the Admin renders one inline field error for both.
      throw emailNotAvailable(null);
    }
  }

  /** Each elapsed claim gives its reserved number back, like the expiry sweep. */
  private void expireElapsedRecipientClaims(String normalizedEmail, LocalDateTime now) {
    for (AccountInvite elapsed :
        accountInviteRepository.findElapsedRecipientClaims(
            normalizedEmail, ADDRESS_HOLDING_INVITE_STATUSES, now)) {
      expireAndReleaseNumbers(elapsed, now);
    }
    accountInviteRepository.expireElapsedRecipientClaims(
        normalizedEmail, ADDRESS_HOLDING_INVITE_STATUSES, now);
  }

  private void verifyRecipientEmailAvailableExcluding(
      String recipientEmail, Long excludedInviteId, LocalDateTime now) {
    String normalized = normalizeEmail(recipientEmail);
    if (identityEmailOwnerLookup.findByEmail(normalized).isPresent()) {
      throw emailNotAvailable(null);
    }
    expireElapsedRecipientClaims(normalized, now);
    if (accountInviteRepository.countNonTerminalInvitesForRecipientEmailExcludingId(
            normalized, excludedInviteId, ADDRESS_HOLDING_INVITE_STATUSES, now)
        > 0) {
      throw emailNotAvailable(null);
    }
  }

  @Transactional(readOnly = true)
  public Page<AccountInvite> listInvites(
      AccountInviteTargetRole targetRole,
      AccountInviteStatus status,
      Long tenantId,
      String query,
      int page,
      int size) {
    PageRequest pageRequest = PageRequest.of(Math.max(page, 0), clampSize(size));
    return findInScope(targetRole, status, tenantId, query, pageRequest);
  }

  /** Every invite in the caller's scope that matches, unpaged, newest first; for the tiles. */
  @Transactional(readOnly = true)
  public List<AccountInvite> listAllInvites(
      AccountInviteTargetRole targetRole, Long tenantId, String query) {
    return findInScope(targetRole, null, tenantId, query, Pageable.unpaged()).getContent();
  }

  private Page<AccountInvite> findInScope(
      AccountInviteTargetRole targetRole,
      AccountInviteStatus status,
      Long tenantId,
      String query,
      Pageable pageable) {
    String search = normalizeSearch(query);
    // Cross-Träger guard: an absent tenant_id would otherwise list the invites of every Träger.
    InviteListScope scope = accessPolicy.scopeForListing(tenantId, targetRole);
    if (scope.empty()) {
      return pageable.isPaged() ? Page.empty(pageable) : Page.empty();
    }
    if (scope.restrictedToAgencies()) {
      return accountInviteRepository.findAllByFiltersWithinAgencies(
          scope.tenantId(),
          scope.targetRole(),
          status,
          search,
          parseNumericSearch(search),
          scope.agencyIds(),
          pageable);
    }
    return accountInviteRepository.findAllByFilters(
        scope.tenantId(), scope.targetRole(), status, search, parseNumericSearch(search), pageable);
  }

  /**
   * Blank/missing preserves the existing result set (ORISO-UserService#479 acceptance): only a
   * non-empty, trimmed, lower-cased term is passed to the repository, since {@code null} is the
   * sentinel {@code findAllByFilters} short-circuits its search clause on.
   */
  private static String normalizeSearch(String query) {
    if (query == null) {
      return null;
    }
    String trimmed = query.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase();
  }

  private static Long parseNumericSearch(String normalizedSearch) {
    if (normalizedSearch == null || !normalizedSearch.matches("\\d+")) {
      return null;
    }
    try {
      return Long.parseLong(normalizedSearch);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  /**
   * A waiting invite can be sent by hand only once its unit exists (409 before). An SMTP failure
   * leaves at most a FAILED audit row behind.
   */
  @Transactional(noRollbackFor = SmtpSendException.class)
  public InviteSendResult sendInvite(SendInviteCommand command) {
    AccountInvite invite = findAuthorizedInvite(command.inviteId());
    InviteEmailTemplate template = findTemplate(command.templateId());
    if (invite.getStatus() == AccountInviteStatus.WAITING_FOR_UNIT) {
      return unitQueue.sendByHand(invite, template);
    }
    if (invite.getStatus() == AccountInviteStatus.ACCEPTED) {
      throw new BadRequestException("Accepted invites cannot be sent");
    }
    if (invite.getStatus() == AccountInviteStatus.REVOKED
        || invite.getStatus() == AccountInviteStatus.SUPERSEDED) {
      throw new BadRequestException("Inactive invites cannot be sent");
    }
    LocalDateTime now = LocalDateTime.now();
    Prepared prepared = delivery.prepare(invite, template, now);
    InviteEmailDelivery sent =
        delivery.sendNow(
            prepared,
            invite.getId(),
            () -> {
              invite.setTokenHash(prepared.tokenHash());
              if (invite.getExpiresAt() == null || invite.getExpiresAt().isBefore(now)) {
                invite.setExpiresAt(resolveExpiry(now, DEFAULT_EXPIRY_DAYS));
              }
              invite.setStatus(AccountInviteStatus.EMAIL_SENT);
              invite.setUpdateDate(now);
              accountInviteRepository.save(invite);
            });
    return new InviteSendResult(invite, sent, prepared.rawToken(), prepared.acceptUrl());
  }

  public InviteSendResult resendInvite(SendInviteCommand command) {
    AccountInvite current =
        requiresNewTransaction().execute(transaction -> findAuthorizedInvite(command.inviteId()));
    if (current != null && current.getStatus() == AccountInviteStatus.WAITING_FOR_UNIT) {
      // A waiting invite was never sent: "resend" is its first send (release), same rules.
      return sendInvite(command);
    }
    ResendDispatch resend = prepareResend(command);
    return delivery.deliver(
        resend.prepared(),
        resend.oldInviteId(),
        true,
        sendFailure -> restoreResendAfterConfirmedFailure(resend, sendFailure));
  }

  private ResendDispatch prepareResend(SendInviteCommand command) {
    return requiresNewTransaction()
        .execute(
            transaction -> {
              AccountInvite initialOldInvite = findAuthorizedInvite(command.inviteId());
              if (initialOldInvite.getStatus() == AccountInviteStatus.ACCEPTED) {
                throw new BadRequestException("Accepted invites cannot be resent");
              }
              if (initialOldInvite.getStatus() == AccountInviteStatus.REVOKED) {
                throw new BadRequestException("Revoked invites cannot be resent");
              }

              LocalDateTime now = LocalDateTime.now();
              verifyRecipientEmailAvailableExcluding(
                  initialOldInvite.getRecipientEmail(), initialOldInvite.getId(), now);
              // The expiry cleanup is a clearing bulk update, so reload the old row before the
              // durable handover.
              AccountInvite oldInvite = findInvite(command.inviteId());
              InviteEmailTemplate template = findTemplate(command.templateId());
              Prepared prepared = delivery.prepare(oldInvite, template, now);

              ResendState oldState = ResendState.from(oldInvite);
              AccountInvite replacement =
                  AccountInvite.builder()
                      .targetRole(oldInvite.getTargetRole())
                      .tenantId(oldInvite.getTenantId())
                      .tenantIdReservationToken(oldInvite.getTenantIdReservationToken())
                      .recipientEmail(oldInvite.getRecipientEmail())
                      .activeRecipientKey(normalizeEmail(oldInvite.getRecipientEmail()))
                      .firstName(oldInvite.getFirstName())
                      .lastName(oldInvite.getLastName())
                      .agencyId(oldInvite.getAgencyId())
                      .departmentId(oldInvite.getDepartmentId())
                      .tenantIdAllocationMode(oldInvite.getTenantIdAllocationMode())
                      .agencyIdAllocationMode(oldInvite.getAgencyIdAllocationMode())
                      .alsoCounsellor(oldInvite.getAlsoCounsellor())
                      .topicPermission(oldInvite.getTopicPermission())
                      .unitCreatedAt(oldInvite.getUnitCreatedAt())
                      .tokenHash(prepared.tokenHash())
                      .expiresAt(resolveExpiry(now, DEFAULT_EXPIRY_DAYS))
                      .status(AccountInviteStatus.EMAIL_SENT)
                      .emailVerificationStatus(oldInvite.getEmailVerificationStatus())
                      .twoFactorStatus(oldInvite.getTwoFactorStatus())
                      .createdByUserId(authenticatedUser.getUserId())
                      .createdByUsername(authenticatedUser.getUsername())
                      .createDate(now)
                      .updateDate(now)
                      .build();

              oldInvite.setStatus(AccountInviteStatus.SUPERSEDED);
              oldInvite.setActiveRecipientKey(null);
              oldInvite.setSupersededAt(now);
              oldInvite.setSupersededByUserId(authenticatedUser.getUserId());
              oldInvite.setUpdateDate(now);
              accountInviteRepository.saveAndFlush(oldInvite);
              try {
                replacement = accountInviteRepository.saveAndFlush(replacement);
              } catch (DataIntegrityViolationException conflict) {
                if (isActiveRecipientConflict(conflict)) {
                  throw emailNotAvailable(conflict);
                }
                throw conflict;
              }
              oldInvite.setSupersededByInviteId(replacement.getId());
              accountInviteRepository.saveAndFlush(oldInvite);

              return new ResendDispatch(
                  prepared.withInvite(replacement), oldInvite.getId(), oldState);
            });
  }

  private void restoreResendAfterConfirmedFailure(
      ResendDispatch resend, SmtpSendException sendFailure) {
    try {
      requiresNewTransaction()
          .executeWithoutResult(
              transaction -> {
                accountInviteRepository.deleteById(resend.prepared().invite().getId());
                accountInviteRepository.flush();
                AccountInvite oldInvite = findInvite(resend.oldInviteId());
                resend.oldState().restore(oldInvite);
                accountInviteRepository.saveAndFlush(oldInvite);
              });
    } catch (RuntimeException compensationFailure) {
      log.error(
          "Resend {} failed before dispatch and its committed claim handover could not be restored",
          resend.prepared().invite().getId(),
          compensationFailure);
      sendFailure.addSuppressed(compensationFailure);
    }
  }

  /**
   * Expires elapsed invites that still hold a Träger or Beratungsstelle number; run by {@link
   * ExpiredInviteReservationSweep}.
   *
   * @return how many invites were expired
   */
  @Transactional
  public int expireElapsedInvites() {
    LocalDateTime now = LocalDateTime.now();
    List<AccountInvite> elapsed =
        accountInviteRepository.findElapsedHoldingANumber(
            EXPIRABLE_STATUSES,
            ReservationLedger.RESERVING_MODES,
            now,
            PageRequest.of(0, EXPIRY_SWEEP_BATCH));
    for (AccountInvite invite : elapsed) {
      expireAndReleaseNumbers(invite, now);
    }
    return elapsed.size();
  }

  /**
   * Locked and conditional so a racing accept and revoke cannot both win (ORISO-Admin#1026); only
   * the call that revoked gives the numbers back.
   */
  @Transactional
  public AccountInvite revokeInvite(Long inviteId) {
    AccountInvite invite = findAuthorizedInviteForUpdate(inviteId);
    if (invite.getStatus() == AccountInviteStatus.ACCEPTED) {
      throw inviteAlreadyAccepted();
    }
    if (invite.getStatus() == AccountInviteStatus.REVOKED) {
      return invite;
    }
    LocalDateTime now = LocalDateTime.now();
    int revoked =
        accountInviteRepository.revokeWhileStatusIn(
            invite.getId(), REVOCABLE_STATUSES, authenticatedUser.getUserId(), now);
    AccountInvite current = findInvite(inviteId);
    if (revoked == 0) {
      if (current.getStatus() == AccountInviteStatus.ACCEPTED) {
        throw inviteAlreadyAccepted();
      }
      return current;
    }
    ledger.releaseUnneeded(current, now);
    return current;
  }

  private static CustomValidationHttpStatusException inviteAlreadyAccepted() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.INVITE_ALREADY_ACCEPTED, HttpStatus.CONFLICT);
  }

  private void expireAndReleaseNumbers(AccountInvite invite, LocalDateTime now) {
    invite.setStatus(AccountInviteStatus.EXPIRED);
    invite.setActiveRecipientKey(null);
    invite.setUpdateDate(now);
    accountInviteRepository.save(invite);
    ledger.releaseUnneeded(invite, now);
  }

  @Transactional(noRollbackFor = AccountInviteLinkException.class)
  public AccountInvite acceptInvite(String rawToken, String acceptedByUserId) {
    if (isBlank(rawToken)) {
      throw new BadRequestException("Invite token is required");
    }
    AccountInvite invite =
        accountInviteRepository
            .findByTokenHash(hash(rawToken))
            .orElseThrow(() -> new NotFoundException("Account invite not found"));

    LocalDateTime now = LocalDateTime.now();
    if (invite.getStatus() != AccountInviteStatus.EMAIL_SENT) {
      return resolveAlreadyProcessedInvite(invite, now);
    }
    if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(now)) {
      expireAndReleaseNumbers(invite, now);
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
    }

    // Single-use enforcement as an atomic guarded UPDATE (hardening, ORISO-Admin#569): only the
    // transaction whose UPDATE still matches EMAIL_SENT claims the invite. This does not depend
    // on the database honoring the pessimistic lock hint of the token lookup above.
    int claimed = accountInviteRepository.claimForAcceptance(invite.getId(), acceptedByUserId, now);
    if (claimed == 0) {
      // Lost the race between our read and the claim — re-read the winner's committed state
      // (the persistence context was cleared by the modifying query) and map it as usual.
      AccountInvite current =
          accountInviteRepository
              .findById(invite.getId())
              .orElseThrow(() -> new NotFoundException("Account invite not found"));
      return resolveAlreadyProcessedInvite(current, now);
    }

    // Mirror exactly the columns the guarded UPDATE wrote onto the (now detached) entity so the
    // caller sees the persisted state without an extra round trip.
    invite.setStatus(AccountInviteStatus.ACCEPTED);
    invite.setActiveRecipientKey(null);
    invite.setAcceptedAt(now);
    invite.setAcceptedByUserId(acceptedByUserId);
    invite.setEmailVerificationStatus(EmailVerificationStatus.VERIFIED);
    invite.setUpdateDate(now);
    return invite;
  }

  /**
   * Maps every non-{@code EMAIL_SENT} state to the wire contract. Distinct, machine-readable
   * reasons (TEN-INV-U6, #890): terminal states win over the date check so an already
   * consumed/revoked link is reported as such, not as merely expired. Consumed invites may still be
   * resumable — see {@link #resumeConsumedInviteOrThrow(AccountInvite, LocalDateTime)}.
   */
  private AccountInvite resolveAlreadyProcessedInvite(AccountInvite invite, LocalDateTime now) {
    switch (invite.getStatus()) {
      case ACCEPTED -> {
        return resumeConsumedInviteOrThrow(invite, now);
      }
      case REVOKED ->
          throw new AccountInviteLinkException(AccountInviteLinkException.Reason.REVOKED);
      case SUPERSEDED ->
          throw new AccountInviteLinkException(AccountInviteLinkException.Reason.SUPERSEDED);
      case EXPIRED ->
          throw new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
        // DRAFT (or any future state) has never been delivered to the recipient; accepting it
        // would bypass the email verification step entirely.
      default -> throw new AccountInviteLinkException(AccountInviteLinkException.Reason.NOT_ACTIVE);
    }
  }

  /**
   * RESUME CONTRACT (hardening for ORISO-Admin#569): a consumed invite whose mandatory two-factor
   * activation is still pending ({@code twoFactorStatus == PENDING_SETUP}) stays resumable — the
   * accept call is then idempotent: it returns the invite unchanged (HTTP 200, same response shape
   * and data as the original accept, nothing beyond it) so the client can pick the onboarding up at
   * the 2FA step. The resume window stays token- and expiry-bound: after {@code expiresAt} the link
   * is terminally CONSUMED. Once the gate is satisfied (ACTIVE via {@link
   * #markTwoFactorActive(String)}, WAIVED, NOT_REQUIRED or DISABLED_BY_POLICY) the link is
   * terminally consumed as well. The ACCEPTED audit state (acceptor, timestamps) is never rewritten
   * by a resume attempt.
   */
  private AccountInvite resumeConsumedInviteOrThrow(AccountInvite invite, LocalDateTime now) {
    boolean twoFactorStillPending = !isTwoFactorGateSatisfied(invite.getTwoFactorStatus());
    boolean withinExpiryWindow =
        invite.getExpiresAt() == null || !invite.getExpiresAt().isBefore(now);
    if (twoFactorStillPending && withinExpiryWindow) {
      return invite;
    }
    throw new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
  }

  /**
   * Resolves ONLY the target role of an invite by its raw link token — the dispatch probe of the
   * shared public onboarding routes. Answers exactly like {@link #findInviteByToken} for a blank
   * (400) or unknown (404) token, but takes no pessimistic row lock: the role-specific flow this
   * probe selects loads the same row under its own lock right afterwards (#1008 review).
   */
  @Transactional(readOnly = true)
  public AccountInviteTargetRole findTargetRoleByToken(String rawToken) {
    if (isBlank(rawToken)) {
      throw new BadRequestException("Invite token is required");
    }
    return accountInviteRepository
        .findTargetRoleByTokenHash(hash(rawToken))
        .orElseThrow(() -> new NotFoundException("Account invite not found"));
  }

  /** Resolves an invite by its raw link token without any state checks. */
  @Transactional(readOnly = true)
  public AccountInvite findInviteByToken(String rawToken) {
    if (isBlank(rawToken)) {
      throw new BadRequestException("Invite token is required");
    }
    return accountInviteRepository
        .findByTokenHash(hash(rawToken))
        .orElseThrow(() -> new NotFoundException("Account invite not found"));
  }

  @Transactional
  public AccountInvite requireActiveInvite(String rawToken) {
    AccountInvite invite = findInviteByToken(rawToken);
    LocalDateTime now = LocalDateTime.now();
    if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(now)) {
      throw new BadRequestException("Account invite expired");
    }
    if (invite.getStatus() != AccountInviteStatus.EMAIL_SENT) {
      throw new BadRequestException("Account invite is not active");
    }
    return invite;
  }

  public AccountAccessGateStatus calculateAccessGate(AccountInvite invite) {
    if (invite == null || invite.getStatus() != AccountInviteStatus.ACCEPTED) {
      return AccountAccessGateStatus.BLOCKED_INVITE;
    }
    if (!isEmailGateSatisfied(invite.getEmailVerificationStatus())) {
      return AccountAccessGateStatus.BLOCKED_EMAIL;
    }
    if (!isTwoFactorGateSatisfied(invite.getTwoFactorStatus())) {
      return AccountAccessGateStatus.BLOCKED_TWO_FACTOR;
    }
    return AccountAccessGateStatus.READY;
  }

  public AccountInvite waiveTwoFactor(Long inviteId, WaiveTwoFactorCommand command) {
    return waiveTwoFactor(findAuthorizedInvite(inviteId), command);
  }

  /** Waives the 2FA gate; applies the cross-Träger guard itself, whichever overload is used. */
  public AccountInvite waiveTwoFactor(AccountInvite invite, WaiveTwoFactorCommand command) {
    if (invite == null) {
      throw new BadRequestException("Invite is required");
    }
    accessPolicy.authorizeAccess(invite);
    if (command == null || isBlank(command.reason())) {
      throw new BadRequestException("Waiver reason is required");
    }
    LocalDateTime now = LocalDateTime.now();
    invite.setTwoFactorStatus(TwoFactorGateStatus.WAIVED);
    invite.setTwoFactorWaivedBy(authenticatedUser.getUserId());
    invite.setTwoFactorWaivedAt(now);
    invite.setTwoFactorWaiverReason(command.reason());
    invite.setUpdateDate(now);
    accountInviteRepository.save(invite);
    return invite;
  }

  /** Marks pending invite gates as satisfied once the user has an OTP credential. */
  public void markTwoFactorActive(String userId) {
    transitionTwoFactorStatus(
        userId, TwoFactorGateStatus.PENDING_SETUP, TwoFactorGateStatus.ACTIVE);
  }

  /** Re-opens the gate when the user deletes their OTP credential (waivers stay untouched). */
  public void markTwoFactorPendingSetup(String userId) {
    transitionTwoFactorStatus(
        userId, TwoFactorGateStatus.ACTIVE, TwoFactorGateStatus.PENDING_SETUP);
  }

  private void transitionTwoFactorStatus(
      String userId, TwoFactorGateStatus from, TwoFactorGateStatus to) {
    if (isBlank(userId)) {
      return;
    }
    var invites = accountInviteRepository.findAllByAcceptedByUserIdAndTwoFactorStatus(userId, from);
    if (invites.isEmpty()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    invites.forEach(
        invite -> {
          invite.setTwoFactorStatus(to);
          if (to == TwoFactorGateStatus.ACTIVE) {
            invite.setTwoFactorActivatedAt(now);
          }
          invite.setUpdateDate(now);
        });
    accountInviteRepository.saveAll(invites);
  }

  /** Loads an invite for an admin action and applies the cross-Träger guard. */
  private AccountInvite findAuthorizedInvite(Long inviteId) {
    if (inviteId == null) {
      throw new BadRequestException("inviteId is required");
    }
    Optional<AccountInvite> invite = accountInviteRepository.findById(inviteId);
    if (invite.isEmpty()) {
      accessPolicy.authorizeMissing(inviteId);
      throw new NotFoundException("Account invite not found");
    }
    accessPolicy.authorizeAccess(invite.get());
    return invite.get();
  }

  private AccountInvite findAuthorizedInviteForUpdate(Long inviteId) {
    if (inviteId == null) {
      throw new BadRequestException("inviteId is required");
    }
    Optional<AccountInvite> invite = accountInviteRepository.findByIdForUpdate(inviteId);
    if (invite.isEmpty()) {
      accessPolicy.authorizeMissing(inviteId);
      throw new NotFoundException("Account invite not found");
    }
    accessPolicy.authorizeAccess(invite.get());
    return invite.get();
  }

  private AccountInvite findInvite(Long inviteId) {
    if (inviteId == null) {
      throw new BadRequestException("inviteId is required");
    }
    return accountInviteRepository
        .findById(inviteId)
        .orElseThrow(() -> new NotFoundException("Account invite not found"));
  }

  private InviteEmailTemplate findTemplate(Long templateId) {
    if (templateId == null) {
      throw new BadRequestException("templateId is required");
    }
    return templateRepository
        .findById(templateId)
        .orElseThrow(() -> new NotFoundException("Invite e-mail template not found"));
  }

  /**
   * Counsellors, agency admins (they onboard through the same wizard) and tenant admins carry a
   * mandatory TOTP setup (ORISO-Admin#569: "account, password, 2FA" is one coherent onboarding
   * flow). Their gate starts at {@code PENDING_SETUP}, which also keeps the consumed invite link
   * resumable until the OTP credential exists — see {@link
   * #resumeConsumedInviteOrThrow(AccountInvite, LocalDateTime)}.
   */
  private static TwoFactorGateStatus defaultTwoFactorStatus(AccountInviteTargetRole targetRole) {
    return targetRole == AccountInviteTargetRole.COUNSELLOR
            || targetRole == AccountInviteTargetRole.AGENCY_ADMIN
            || targetRole == AccountInviteTargetRole.TENANT_ADMIN
        ? TwoFactorGateStatus.PENDING_SETUP
        : TwoFactorGateStatus.NOT_REQUIRED;
  }

  private static boolean isEmailGateSatisfied(EmailVerificationStatus status) {
    return status == EmailVerificationStatus.NOT_REQUIRED
        || status == EmailVerificationStatus.VERIFIED;
  }

  /** Public because the tenant-admin onboarding flow shares the resume-window semantics. */
  public static boolean isTwoFactorGateSatisfied(TwoFactorGateStatus status) {
    return status == TwoFactorGateStatus.NOT_REQUIRED
        || status == TwoFactorGateStatus.ACTIVE
        || status == TwoFactorGateStatus.WAIVED
        || status == TwoFactorGateStatus.DISABLED_BY_POLICY;
  }

  private static LocalDateTime resolveExpiry(LocalDateTime now, Long expiresInDays) {
    return now.plusDays(validExpiryDays(expiresInDays));
  }

  static long validExpiryDays(Long expiresInDays) {
    long days = expiresInDays == null ? DEFAULT_EXPIRY_DAYS : expiresInDays;
    if (days < 1 || days > 365) {
      throw new BadRequestException("expiresInDays must be between 1 and 365");
    }
    return days;
  }

  private static int clampSize(int size) {
    if (size < 1) {
      return 20;
    }
    return Math.min(size, 100);
  }

  /** The action-link token standing alone on its own line, including that line's break. */
  private static final Pattern ACTION_LINK_TOKEN_LINE =
      Pattern.compile("(?m)^[ \\t]*\\{\\{inviteLink\\}\\}[ \\t]*(\\r?\\n)?");

  /** A run of three or more line breaks, left behind when a token line is lifted out. */
  private static final Pattern BLANK_LINE_RUN = Pattern.compile("(\\r?\\n){3,}");

  /** The action-link token sitting inside a sentence, with the space in front of it. */
  private static final Pattern ACTION_LINK_TOKEN_INLINE =
      Pattern.compile("[ \\t]*\\{\\{inviteLink\\}\\}");

  /**
   * Renders a template <em>body</em>. Same substitution as {@link #render}, minus the action link.
   *
   * <p>The branded layout renders the invite link itself — as a CTA button and, underneath it, a
   * visible copy-paste line carrying the plain URL (in the HTML part and in the text/plain
   * alternative alike). A body that <em>also</em> inlined {@code {{inviteLink}}} therefore produced
   * the same URL twice in the received mail, which is what the annotated screenshots show. The
   * layout owns the action link; the body must not carry it.
   *
   * <p>Enforcing it here rather than in the composer is deliberate: the send path and the Admin
   * preview share this code, so an author cannot compose a mail whose link is duplicated, and
   * templates saved before this rule existed — including the shipped default — are repaired on
   * render instead of needing a migration.
   *
   * <p>Removal is line-aware. A token alone on its line takes the line with it, so the sentence
   * that introduced it runs straight into the button. A token inside a sentence takes the space in
   * front of it, leaving the author's own wording otherwise untouched: {@code "Hier: {{inviteLink}}
   * — viel Erfolg"} becomes {@code "Hier: — viel Erfolg"}. The dangling colon is the author's text
   * and is not invented away.
   */
  public static String renderBody(String value, AccountInvite invite, String acceptUrl) {
    if (value == null) {
      return "";
    }
    String withoutActionLink = ACTION_LINK_TOKEN_LINE.matcher(value).replaceAll("");
    withoutActionLink = ACTION_LINK_TOKEN_INLINE.matcher(withoutActionLink).replaceAll("");
    // Lifting a line out of "text\n\n{{inviteLink}}\n\ntext" would otherwise leave a
    // triple break — a visible hole exactly where the link used to be.
    withoutActionLink = BLANK_LINE_RUN.matcher(withoutActionLink).replaceAll("\n\n");
    return render(withoutActionLink, invite, acceptUrl);
  }

  /**
   * Substitutes the author-facing placeholders of a template. Public since #914 so the Admin
   * preview endpoint renders a template exactly the way the send path does, instead of
   * re-implementing the substitution.
   */
  public static String render(String value, AccountInvite invite, String acceptUrl) {
    if (value == null) {
      return "";
    }
    Map<String, String> placeholders =
        Map.of(
            "inviteLink", acceptUrl,
            "email", safe(invite.getRecipientEmail()),
            "firstName", safe(invite.getFirstName()),
            "lastName", safe(invite.getLastName()),
            "tenantId", invite.getTenantId() == null ? "" : String.valueOf(invite.getTenantId()));
    String rendered = value;
    for (var entry : placeholders.entrySet()) {
      rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return rendered;
  }

  public static String hash(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String normalizeEmail(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isActiveRecipientConflict(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      String message = current.getMessage();
      if (message != null
          && message.toLowerCase(Locale.ROOT).contains(ACTIVE_RECIPIENT_CONSTRAINT)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static CustomValidationHttpStatusException emailNotAvailable(Throwable cause) {
    CustomValidationHttpStatusException conflict =
        new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE, HttpStatus.CONFLICT);
    if (cause != null) {
      conflict.initCause(cause);
    }
    return conflict;
  }

  private static String trimToNull(String value) {
    return isBlank(value) ? null : value.trim();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  public record CreateAccountInviteCommand(
      AccountInviteTargetRole targetRole,
      Long tenantId,
      String recipientEmail,
      String firstName,
      String lastName,
      Long agencyId,
      Long departmentId,
      Long expiresInDays,
      IdAllocationMode tenantIdAllocationMode,
      IdAllocationMode agencyIdAllocationMode,
      /** AGENCY_ADMIN only; null = true. Any other role must leave it null. */
      Boolean alsoCounsellor,
      /** The admin's choice; null = SELECT_EXISTING (TopicPermissionPolicy). */
      TopicPermission topicPermission) {

    public CreateAccountInviteCommand(
        AccountInviteTargetRole targetRole,
        Long tenantId,
        String recipientEmail,
        String firstName,
        String lastName,
        Long agencyId,
        Long departmentId,
        Long expiresInDays,
        IdAllocationMode tenantIdAllocationMode,
        IdAllocationMode agencyIdAllocationMode,
        Boolean alsoCounsellor) {
      this(
          targetRole,
          tenantId,
          recipientEmail,
          firstName,
          lastName,
          agencyId,
          departmentId,
          expiresInDays,
          tenantIdAllocationMode,
          agencyIdAllocationMode,
          alsoCounsellor,
          null);
    }

    public CreateAccountInviteCommand(
        AccountInviteTargetRole targetRole,
        Long tenantId,
        String recipientEmail,
        String firstName,
        String lastName,
        Long agencyId,
        Long departmentId,
        Long expiresInDays,
        IdAllocationMode tenantIdAllocationMode,
        IdAllocationMode agencyIdAllocationMode) {
      this(
          targetRole,
          tenantId,
          recipientEmail,
          firstName,
          lastName,
          agencyId,
          departmentId,
          expiresInDays,
          tenantIdAllocationMode,
          agencyIdAllocationMode,
          null);
    }

    public CreateAccountInviteCommand withTenantId(Long newTenantId) {
      return new CreateAccountInviteCommand(
          targetRole,
          newTenantId,
          recipientEmail,
          firstName,
          lastName,
          agencyId,
          departmentId,
          expiresInDays,
          tenantIdAllocationMode,
          agencyIdAllocationMode,
          alsoCounsellor,
          topicPermission);
    }

    public CreateAccountInviteCommand withDepartmentId(Long newDepartmentId) {
      return new CreateAccountInviteCommand(
          targetRole,
          tenantId,
          recipientEmail,
          firstName,
          lastName,
          agencyId,
          newDepartmentId,
          expiresInDays,
          tenantIdAllocationMode,
          agencyIdAllocationMode,
          alsoCounsellor,
          topicPermission);
    }

    /** Convenience for callers without ID-allocation semantics (no reservation modes). */
    public CreateAccountInviteCommand(
        AccountInviteTargetRole targetRole,
        Long tenantId,
        String recipientEmail,
        String firstName,
        String lastName,
        Long agencyId,
        Long departmentId,
        Long expiresInDays) {
      this(
          targetRole,
          tenantId,
          recipientEmail,
          firstName,
          lastName,
          agencyId,
          departmentId,
          expiresInDays,
          null,
          null);
    }
  }

  /**
   * The accept link's base URL is server configuration, never caller input (TEN-INV-U6) — the
   * former {@code acceptBaseUrl} member is gone on purpose.
   */
  public record SendInviteCommand(Long inviteId, Long templateId) {}

  public record InviteSendResult(
      AccountInvite invite, InviteEmailDelivery delivery, String rawToken, String acceptUrl) {}

  private record ResendDispatch(Prepared prepared, Long oldInviteId, ResendState oldState) {}

  private record ResendState(
      AccountInviteStatus status,
      String activeRecipientKey,
      LocalDateTime supersededAt,
      String supersededByUserId,
      Long supersededByInviteId,
      LocalDateTime updateDate) {

    private static ResendState from(AccountInvite invite) {
      return new ResendState(
          invite.getStatus(),
          invite.getActiveRecipientKey(),
          invite.getSupersededAt(),
          invite.getSupersededByUserId(),
          invite.getSupersededByInviteId(),
          invite.getUpdateDate());
    }

    private void restore(AccountInvite invite) {
      invite.setStatus(status);
      invite.setActiveRecipientKey(activeRecipientKey);
      invite.setSupersededAt(supersededAt);
      invite.setSupersededByUserId(supersededByUserId);
      invite.setSupersededByInviteId(supersededByInviteId);
      invite.setUpdateDate(updateDate);
    }
  }

  public record WaiveTwoFactorCommand(String reason) {}
}
