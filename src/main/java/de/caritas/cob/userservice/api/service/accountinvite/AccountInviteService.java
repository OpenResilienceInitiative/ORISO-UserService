package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountInviteService {

  private static final int TOKEN_BYTES = 32;
  private static final long DEFAULT_EXPIRY_DAYS = 30L;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull InviteEmailTemplateRepository templateRepository;
  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;

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

    LocalDateTime now = LocalDateTime.now();
    AccountInvite invite =
        AccountInvite.builder()
            .targetRole(command.targetRole())
            .tenantId(command.tenantId())
            .recipientEmail(command.recipientEmail().trim())
            .firstName(trimToNull(command.firstName()))
            .lastName(trimToNull(command.lastName()))
            .agencyId(command.agencyId())
            .departmentId(command.departmentId())
            .expiresAt(resolveExpiry(now, command.expiresInDays()))
            .status(AccountInviteStatus.DRAFT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(defaultTwoFactorStatus(command.targetRole()))
            .createdByUserId(authenticatedUser.getUserId())
            .createdByUsername(authenticatedUser.getUsername())
            .createDate(now)
            .updateDate(now)
            .build();
    return accountInviteRepository.save(invite);
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
    return sendInvite(invite, template, command.acceptBaseUrl());
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

    LocalDateTime now = LocalDateTime.now();
    oldInvite.setStatus(AccountInviteStatus.SUPERSEDED);
    oldInvite.setSupersededAt(now);
    oldInvite.setSupersededByUserId(authenticatedUser.getUserId());
    oldInvite.setUpdateDate(now);
    accountInviteRepository.save(oldInvite);

    AccountInvite replacement =
        AccountInvite.builder()
            .targetRole(oldInvite.getTargetRole())
            .tenantId(oldInvite.getTenantId())
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
    replacement = accountInviteRepository.save(replacement);
    oldInvite.setSupersededByInviteId(replacement.getId());
    accountInviteRepository.save(oldInvite);

    InviteEmailTemplate template = findTemplate(command.templateId());
    return sendInvite(replacement, template, command.acceptBaseUrl());
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
    if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(now)) {
      invite.setStatus(AccountInviteStatus.EXPIRED);
      invite.setUpdateDate(now);
      accountInviteRepository.save(invite);
      throw new BadRequestException("Account invite expired");
    }
    // Only EMAIL_SENT invites are eligible to be accepted: DRAFT invites have never been
    // delivered to the recipient, so allowing them to be accepted would bypass the email
    // verification step entirely.
    if (invite.getStatus() != AccountInviteStatus.EMAIL_SENT) {
      throw new BadRequestException("Account invite is not active");
    }

    invite.setStatus(AccountInviteStatus.ACCEPTED);
    invite.setAcceptedAt(now);
    invite.setAcceptedByUserId(acceptedByUserId);
    invite.setEmailVerificationStatus(EmailVerificationStatus.VERIFIED);
    invite.setUpdateDate(now);
    return accountInviteRepository.save(invite);
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

  private InviteSendResult sendInvite(
      AccountInvite invite, InviteEmailTemplate template, String acceptBaseUrl) {
    if (invite.getStatus() == AccountInviteStatus.ACCEPTED) {
      throw new BadRequestException("Accepted invites cannot be sent");
    }
    if (invite.getStatus() == AccountInviteStatus.REVOKED
        || invite.getStatus() == AccountInviteStatus.SUPERSEDED) {
      throw new BadRequestException("Inactive invites cannot be sent");
    }

    LocalDateTime now = LocalDateTime.now();
    String rawToken = generateToken();
    String acceptUrl = buildAcceptUrl(acceptBaseUrl, rawToken);
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
            .subjectSnapshot(render(template.getSubject(), invite, acceptUrl))
            .bodySnapshot(render(template.getBody(), invite, acceptUrl))
            .status(InviteEmailDeliveryStatus.SENT)
            .sentAt(now)
            .createDate(now)
            .build();
    delivery = deliveryRepository.save(delivery);
    return new InviteSendResult(invite, delivery, rawToken, acceptUrl);
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

  private static TwoFactorGateStatus defaultTwoFactorStatus(AccountInviteTargetRole targetRole) {
    return targetRole == AccountInviteTargetRole.COUNSELLOR
        ? TwoFactorGateStatus.PENDING_SETUP
        : TwoFactorGateStatus.NOT_REQUIRED;
  }

  private static boolean isEmailGateSatisfied(EmailVerificationStatus status) {
    return status == EmailVerificationStatus.NOT_REQUIRED
        || status == EmailVerificationStatus.VERIFIED;
  }

  private static boolean isTwoFactorGateSatisfied(TwoFactorGateStatus status) {
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

  private static String render(String value, AccountInvite invite, String acceptUrl) {
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

  private static String buildAcceptUrl(String acceptBaseUrl, String rawToken) {
    String base = isBlank(acceptBaseUrl) ? "/account-invite" : acceptBaseUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/" + rawToken;
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
      Long expiresInDays) {}

  public record SendInviteCommand(Long inviteId, Long templateId, String acceptBaseUrl) {}

  public record InviteSendResult(
      AccountInvite invite, InviteEmailDelivery delivery, String rawToken, String acceptUrl) {}

  public record WaiveTwoFactorCommand(String reason) {}
}
