package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountInviteService {

  private static final int TOKEN_BYTES = 32;
  private static final long DEFAULT_EXPIRY_DAYS = 30L;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final List<AccountInviteStatus> ACTIVE_TENANT_INVITE_STATUSES =
      List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT);

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull InviteEmailTemplateRepository templateRepository;
  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull TenantService tenantService;
  private final @NonNull TenantIdAllocationClient tenantIdAllocationClient;
  private final @NonNull AgencyIdAllocationClient agencyIdAllocationClient;
  private final @NonNull InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  private final @NonNull InviteMailDispatchService inviteMailDispatchService;
  private final @NonNull InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  private final @NonNull IdentityEmailOwnerLookup identityEmailOwnerLookup;

  @Transactional
  public AccountInvite createInvite(CreateAccountInviteCommand command) {
    if (command == null) {
      throw new BadRequestException("Request body is required");
    }
    if (command.targetRole() == null) {
      throw new BadRequestException("targetRole is required");
    }
    if (isBlank(command.recipientEmail())) {
      throw new BadRequestException("recipientEmail is required");
    }
    validateAllocationModes(command);
    verifyRecipientEmailAvailable(command.recipientEmail());
    if (command.targetRole() == AccountInviteTargetRole.TENANT_ADMIN
        && command.tenantId() != null
        && isTenantIdTaken(command.tenantId())) {
      // 409 — the admin frontend maps CONFLICT to its dedicated "tenant id taken" message.
      throw new ConflictException("tenantId " + command.tenantId() + " is already taken");
    }

    // TEN-INV-U3 (#889): tenant-admin invites hold an authoritative TenantService reservation;
    // agency IDs are reserved in AgencyService's own ID space (U2 decision, AS#214). The green
    // UI state alone grants nothing — the reservation plus the re-validation below decide.
    TenantIdReservation tenantReservation = null;
    Long reservedAgencyId = null;
    if (command.targetRole() == AccountInviteTargetRole.TENANT_ADMIN) {
      tenantReservation = reserveTenantIdOrDegrade(command);
    }
    try {
      if (command.agencyIdAllocationMode() != null) {
        // Long.valueOf: the reservation record carries a primitive long — a bare ternary would
        // unbox command.tenantId() and NPE on invites without a tenant ID.
        Long tenantIdForAgency =
            tenantReservation != null
                ? Long.valueOf(tenantReservation.tenantId())
                : command.tenantId();
        reservedAgencyId = agencyIdAllocationClient.reserve(command.agencyId(), tenantIdForAgency);
      }
      revalidateReservations(tenantReservation, reservedAgencyId);

      LocalDateTime now = LocalDateTime.now();
      AccountInvite invite =
          AccountInvite.builder()
              .targetRole(command.targetRole())
              .tenantId(
                  tenantReservation != null
                      ? Long.valueOf(tenantReservation.tenantId())
                      : command.tenantId())
              .tenantIdReservationToken(
                  tenantReservation != null ? tenantReservation.token() : null)
              .recipientEmail(command.recipientEmail().trim())
              .firstName(trimToNull(command.firstName()))
              .lastName(trimToNull(command.lastName()))
              .agencyId(reservedAgencyId != null ? reservedAgencyId : command.agencyId())
              .departmentId(command.departmentId())
              .expiresAt(resolveExpiry(now, command.expiresInDays()))
              .status(AccountInviteStatus.DRAFT)
              .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
              .emailVerificationStatus(EmailVerificationStatus.PENDING)
              .twoFactorStatus(defaultTwoFactorStatus(command.targetRole()))
              .createdByUserId(authenticatedUser.getUserId())
              .createdByUsername(authenticatedUser.getUsername())
              .createDate(now)
              .updateDate(now)
              .build();
      return accountInviteRepository.save(invite);
    } catch (RuntimeException exception) {
      // Compensation: a failed creation must not leave orphaned reservations behind.
      if (reservedAgencyId != null) {
        agencyIdAllocationClient.release(reservedAgencyId);
      }
      if (tenantReservation != null) {
        tenantIdAllocationClient.release(tenantReservation.tenantId());
      }
      throw exception;
    }
  }

  /**
   * P3: refuses an invite whose recipient address already belongs to a registered identity.
   *
   * <p>Before this guard the collision only surfaced at redemption time — the invitee filled in the
   * whole registration, signed and forwarded the contract and only then hit the dead-end
   * "invitation already used" page, with all that work lost. The check therefore belongs at the
   * front of the funnel, on the admin's create call.
   *
   * <p>Role-agnostic on purpose: counsellor and tenant-admin invites both run through {@code
   * createInvite}, and so do both creation paths ("send directly" posts a templateId, "add to list"
   * does not). One guard covers all four combinations.
   *
   * <p>No separate availability endpoint is exposed: the check rides on the existing, admin-only
   * create call, so it never becomes an unauthenticated user-enumeration oracle.
   *
   * <p>The address is normalized the same way {@link CounsellorInviteProvisioningService}
   * normalizes it when it later creates the identity, so the guard tests exactly the value that
   * would collide.
   */
  private void verifyRecipientEmailAvailable(String recipientEmail) {
    String normalized = recipientEmail.trim().toLowerCase(Locale.ROOT);
    if (identityEmailOwnerLookup.findByEmail(normalized).isPresent()) {
      // 409 + X-Reason: EMAIL_NOT_AVAILABLE — distinguishable from the bare 400 of a malformed
      // address and from the reason-less 409 of a taken tenant ID.
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE, HttpStatus.CONFLICT);
    }
  }

  /**
   * Reserves the tenant ID with graceful degradation for the deployment-order gap (ORISO-Admin#569
   * hardening, U3 verify finding): a TenantService that does not yet expose the TEN-INV-U1
   * allocation endpoints answers 404. Legacy requests (no explicit allocation mode) then fall back
   * to the pre-U3 duplicate checks — already performed by {@code isTenantIdTaken} — instead of
   * failing with an unmapped 500. Requests that explicitly demand AUTO/MANUAL allocation cannot be
   * honored without the authoritative ledger and fail loudly.
   */
  private TenantIdReservation reserveTenantIdOrDegrade(CreateAccountInviteCommand command) {
    try {
      return tenantIdAllocationClient.reserve(command.tenantId());
    } catch (HttpClientErrorException.NotFound exception) {
      if (command.tenantIdAllocationMode() == null) {
        log.warn(
            "TenantService does not expose the tenant-ID allocation endpoints yet (deploy"
                + " TEN-INV-U1 before U3) — creating a legacy invite without an authoritative"
                + " reservation");
        return null;
      }
      throw new InternalServerErrorException(
          "Tenant-ID allocation was requested but TenantService does not expose the allocation"
              + " endpoints (deployment-order gap: deploy TEN-INV-U1 before U3)");
    }
  }

  private static void validateAllocationModes(CreateAccountInviteCommand command) {
    if (command.tenantIdAllocationMode() == IdAllocationMode.MANUAL && command.tenantId() == null) {
      throw new BadRequestException("tenantId is required in MANUAL tenant allocation mode");
    }
    if (command.tenantIdAllocationMode() == IdAllocationMode.AUTO && command.tenantId() != null) {
      throw new BadRequestException("tenantId must be omitted in AUTO tenant allocation mode");
    }
    if (command.tenantIdAllocationMode() != null
        && command.targetRole() != AccountInviteTargetRole.TENANT_ADMIN) {
      throw new BadRequestException(
          "tenantIdAllocationMode is only supported for TENANT_ADMIN invites");
    }
    if (command.agencyIdAllocationMode() == IdAllocationMode.MANUAL && command.agencyId() == null) {
      throw new BadRequestException("agencyId is required in MANUAL agency allocation mode");
    }
    if (command.agencyIdAllocationMode() == IdAllocationMode.AUTO && command.agencyId() != null) {
      throw new BadRequestException("agencyId must be omitted in AUTO agency allocation mode");
    }
  }

  /**
   * Re-validates the reservations immediately before saving: the owning services must still report
   * RESERVED for the held IDs. The server-side check is authoritative — a stale UI state or a lost
   * reservation never produces a duplicate ID.
   */
  private void revalidateReservations(
      TenantIdReservation tenantReservation, Long reservedAgencyId) {
    if (tenantReservation != null
        && tenantIdAllocationClient.getAvailability(tenantReservation.tenantId())
            != IdAllocationStatus.RESERVED) {
      throw new ConflictException(
          "tenantId " + tenantReservation.tenantId() + " is no longer reserved for this invite");
    }
    if (reservedAgencyId != null
        && agencyIdAllocationClient.getAvailability(reservedAgencyId)
            != IdAllocationStatus.RESERVED) {
      throw new ConflictException(
          "agencyId " + reservedAgencyId + " is no longer reserved for this invite");
    }
  }

  @Transactional(readOnly = true)
  public Page<AccountInvite> listInvites(
      AccountInviteTargetRole targetRole,
      AccountInviteStatus status,
      Long tenantId,
      int page,
      int size) {
    return accountInviteRepository.findAllByFilters(
        tenantId, targetRole, status, PageRequest.of(Math.max(page, 0), clampSize(size)));
  }

  @Transactional
  public InviteSendResult sendInvite(SendInviteCommand command) {
    AccountInvite invite = findInvite(command.inviteId());
    InviteEmailTemplate template = findTemplate(command.templateId());
    // The invite was committed by an earlier createInvite transaction, so its id is a safe
    // audit anchor for a FAILED delivery row written in an independent transaction.
    return sendInvite(invite, template, invite.getId());
  }

  @Transactional
  public InviteSendResult resendInvite(SendInviteCommand command) {
    AccountInvite oldInvite = findInvite(command.inviteId());
    if (oldInvite.getStatus() == AccountInviteStatus.ACCEPTED) {
      throw new BadRequestException("Accepted invites cannot be resent");
    }
    if (oldInvite.getStatus() == AccountInviteStatus.REVOKED) {
      throw new BadRequestException("Revoked invites cannot be resent");
    }
    InviteEmailTemplate template = findTemplate(command.templateId());

    LocalDateTime now = LocalDateTime.now();
    AccountInvite replacement =
        AccountInvite.builder()
            .targetRole(oldInvite.getTargetRole())
            .tenantId(oldInvite.getTenantId())
            // The reservation follows the invite chain: the replacement keeps the reserved
            // tenant ID, so it must also keep the token that consumes the reservation.
            .tenantIdReservationToken(oldInvite.getTenantIdReservationToken())
            .recipientEmail(oldInvite.getRecipientEmail())
            .firstName(oldInvite.getFirstName())
            .lastName(oldInvite.getLastName())
            .agencyId(oldInvite.getAgencyId())
            .departmentId(oldInvite.getDepartmentId())
            .expiresAt(resolveExpiry(now, DEFAULT_EXPIRY_DAYS))
            .status(AccountInviteStatus.DRAFT)
            .emailVerificationStatus(oldInvite.getEmailVerificationStatus())
            .twoFactorStatus(oldInvite.getTwoFactorStatus())
            .createdByUserId(authenticatedUser.getUserId())
            .createdByUsername(authenticatedUser.getUsername())
            .createDate(now)
            .updateDate(now)
            .build();

    // Transport first (TEN-INV-U6): the old invite is only superseded and the replacement only
    // persisted after the SMTP server accepted the replacement mail. A failed handover leaves
    // the previous invite fully intact and resendable. The FAILED audit row anchors on the old
    // invite because the replacement does not exist outside this transaction.
    InviteSendResult result = sendInvite(replacement, template, oldInvite.getId());

    oldInvite.setStatus(AccountInviteStatus.SUPERSEDED);
    oldInvite.setSupersededAt(now);
    oldInvite.setSupersededByUserId(authenticatedUser.getUserId());
    oldInvite.setSupersededByInviteId(result.invite().getId());
    oldInvite.setUpdateDate(now);
    accountInviteRepository.save(oldInvite);
    return result;
  }

  @Transactional
  public AccountInvite revokeInvite(Long inviteId) {
    AccountInvite invite = findInvite(inviteId);
    if (invite.getStatus() == AccountInviteStatus.ACCEPTED) {
      throw new BadRequestException("Accepted invites cannot be revoked");
    }
    LocalDateTime now = LocalDateTime.now();
    invite.setStatus(AccountInviteStatus.REVOKED);
    invite.setRevokedAt(now);
    invite.setRevokedByUserId(authenticatedUser.getUserId());
    invite.setUpdateDate(now);
    return accountInviteRepository.save(invite);
  }

  @Transactional
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
      invite.setStatus(AccountInviteStatus.EXPIRED);
      invite.setUpdateDate(now);
      accountInviteRepository.save(invite);
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
    return waiveTwoFactor(findInvite(inviteId), command);
  }

  public AccountInvite waiveTwoFactor(AccountInvite invite, WaiveTwoFactorCommand command) {
    if (invite == null) {
      throw new BadRequestException("Invite is required");
    }
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
          invite.setUpdateDate(now);
        });
    accountInviteRepository.saveAll(invites);
  }

  /**
   * Renders and delivers the invite mail, then persists the send. SENT semantics (TEN-INV-U6,
   * #890): the EMAIL_SENT status and the SENT delivery row are written only after the SMTP server
   * confirmed acceptance of the message — a transport failure propagates as {@link
   * SmtpSendException} (502), rolls back every pending state change and leaves at most a FAILED
   * audit row behind.
   *
   * @param failureAuditInviteId id of an already-committed invite the FAILED audit row may
   *     reference; for resends this is the old invite because the replacement only exists inside
   *     the still-open transaction.
   */
  private InviteSendResult sendInvite(
      AccountInvite invite, InviteEmailTemplate template, Long failureAuditInviteId) {
    if (invite.getStatus() == AccountInviteStatus.ACCEPTED) {
      throw new BadRequestException("Accepted invites cannot be sent");
    }
    if (invite.getStatus() == AccountInviteStatus.REVOKED
        || invite.getStatus() == AccountInviteStatus.SUPERSEDED) {
      throw new BadRequestException("Inactive invites cannot be sent");
    }

    LocalDateTime now = LocalDateTime.now();
    String rawToken = generateToken();
    // The link target is decided server-side from the invite's role — tenant admins onboard on
    // the public Admin route, everyone else accepts on the public App route.
    String acceptUrl = inviteAcceptUrlBuilder.buildAcceptUrl(invite.getTargetRole(), rawToken);
    String subject = render(template.getSubject(), invite, acceptUrl);
    String body = renderBody(template.getBody(), invite, acceptUrl);

    InviteMailSendReceipt receipt;
    try {
      // #914: the dispatcher wraps the authored body in the canonical branded layout and renders
      // the accept URL as a button plus a visible copy-paste fallback line. The link target itself
      // is unchanged — it stays the server-decided acceptUrl from TEN-INV-U6.
      receipt =
          inviteMailDispatchService.send(
              invite.getRecipientEmail(),
              subject,
              body,
              acceptUrl,
              invite.getTenantId(),
              template.getLanguage());
    } catch (SmtpSendException exception) {
      recordDeliveryFailureSafely(failureAuditInviteId, template, invite, subject, body, exception);
      throw exception;
    }

    invite.setTokenHash(hash(rawToken));
    if (invite.getExpiresAt() == null || invite.getExpiresAt().isBefore(now)) {
      invite.setExpiresAt(resolveExpiry(now, DEFAULT_EXPIRY_DAYS));
    }
    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
    invite.setUpdateDate(now);
    invite = accountInviteRepository.save(invite);

    InviteEmailDelivery delivery =
        InviteEmailDelivery.builder()
            .accountInviteId(invite.getId())
            .templateId(template.getId())
            .templateKind(template.getKind())
            .recipientSnapshot(invite.getRecipientEmail())
            .subjectSnapshot(subject)
            .bodySnapshot(body)
            .status(InviteEmailDeliveryStatus.SENT)
            .sentAt(LocalDateTime.ofInstant(receipt.sentAt(), ZoneId.systemDefault()))
            .createDate(now)
            .build();
    delivery = deliveryRepository.save(delivery);
    return new InviteSendResult(invite, delivery, rawToken, acceptUrl);
  }

  /** Best-effort FAILED audit row in an independent transaction; never masks the send failure. */
  private void recordDeliveryFailureSafely(
      Long failureAuditInviteId,
      InviteEmailTemplate template,
      AccountInvite invite,
      String subject,
      String body,
      SmtpSendException exception) {
    if (failureAuditInviteId == null) {
      return;
    }
    try {
      deliveryFailureRecorder.recordFailure(
          failureAuditInviteId,
          template,
          invite.getRecipientEmail(),
          subject,
          body,
          exception.getMessage());
    } catch (RuntimeException auditFailure) {
      log.warn(
          "Could not persist FAILED invite delivery audit row ({})",
          auditFailure.getClass().getSimpleName());
    }
  }

  private boolean isTenantIdTaken(Long tenantId) {
    if (tenantExists(tenantId)) {
      return true;
    }
    return accountInviteRepository.existsByTenantIdAndTargetRoleAndStatusIn(
        tenantId, AccountInviteTargetRole.TENANT_ADMIN, ACTIVE_TENANT_INVITE_STATUSES);
  }

  private boolean tenantExists(Long tenantId) {
    try {
      return tenantService.getRestrictedTenantData(tenantId) != null;
    } catch (HttpClientErrorException exception) {
      if (HttpStatus.NOT_FOUND.equals(exception.getStatusCode())) {
        return false;
      }
      throw exception;
    }
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
   * Counsellors and tenant admins carry a mandatory TOTP setup (ORISO-Admin#569: "account,
   * password, 2FA" is one coherent onboarding flow). Their gate starts at {@code PENDING_SETUP},
   * which also keeps the consumed invite link resumable until the OTP credential exists — see
   * {@link #resumeConsumedInviteOrThrow(AccountInvite, LocalDateTime)}.
   */
  private static TwoFactorGateStatus defaultTwoFactorStatus(AccountInviteTargetRole targetRole) {
    return targetRole == AccountInviteTargetRole.COUNSELLOR
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
    long days = expiresInDays == null ? DEFAULT_EXPIRY_DAYS : expiresInDays;
    if (days < 1 || days > 365) {
      throw new BadRequestException("expiresInDays must be between 1 and 365");
    }
    return now.plusDays(days);
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

  private static String generateToken() {
    byte[] token = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
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
      IdAllocationMode agencyIdAllocationMode) {

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

  public record WaiveTwoFactorCommand(String reason) {}
}
