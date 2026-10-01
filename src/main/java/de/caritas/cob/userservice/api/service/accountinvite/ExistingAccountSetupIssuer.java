package de.caritas.cob.userservice.api.service.accountinvite;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Issues an opaque, single-use setup link after a direct-created identity has been persisted. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExistingAccountSetupIssuer {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long EXPIRY_DAYS = AccountInviteService.DEFAULT_EXPIRY_DAYS;
  private static final InviteEmailTemplateKind KIND =
      InviteEmailTemplateKind.EXISTING_ACCOUNT_SETUP;
  private static final String TEMPLATE = "konto-einrichten";

  private final @NonNull AccountInviteRepository invites;
  private final @NonNull InviteEmailDeliveryRepository deliveries;
  private final @NonNull AdminRepository admins;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull AdminScope adminScope;
  private final @NonNull AuthenticatedUser actor;
  private final @NonNull ExistingAccountSetupService confirmation;
  private final @NonNull InitialPasswordVerifier initialPasswords;
  private final @NonNull InviteAcceptUrlBuilder urls;
  private final @NonNull EmailBrandingResolver branding;
  private final @NonNull TenantEmailBrandValues brandValues;
  private final @NonNull OrisoEmailRenderer renderer;
  private final @NonNull InviteMailDispatchService mail;
  private final @NonNull PlatformTransactionManager transactions;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancyEnabled;

  /**
   * Direct-created accounts only; an ordinary invite-created permanent credential never calls it.
   */
  public void issueAfterCreation(
      AccountInviteTargetRole role, String identityId, String initialPassword) {
    issue(role, identityId, initialPassword, false, null);
  }

  /** Operator reissue for a known account; an in-flight or indeterminate password claim is held. */
  public void reissue(AccountInviteTargetRole role, String identityId) {
    issue(role, identityId, null, true, null);
  }

  /** Resends only the same scoped setup row selected in the existing invite-history action. */
  public AccountInvite reissueSelectedInvite(
      AccountInviteTargetRole role, String identityId, Long expectedInviteId) {
    if (expectedInviteId == null) {
      throw new BadRequestException("Selected setup link is required");
    }
    return issue(role, identityId, null, true, expectedInviteId);
  }

  private AccountInvite issue(
      AccountInviteTargetRole role,
      String identityId,
      String initialPassword,
      boolean reissue,
      Long expectedInviteId) {
    if (isBlank(identityId) || role == null) {
      throw new BadRequestException("Existing account and role are required");
    }
    if (!reissue && isBlank(initialPassword)) {
      throw new BadRequestException("Initial credential is required for account setup");
    }
    if (reissue) {
      assertMayAct(role, identityId);
    }
    ExistingIdentity identity = loadCurrentIdentity(role, identityId);
    if (reissue) {
      // A normal password reset may already have removed Keycloak's UPDATE_PASSWORD action.
      // Do not supersede the prior token or transfer its private verifier in that case.
      confirmation.requireSameCurrentIdentity(
          identity.id(),
          identity.role(),
          identity.tenantId(),
          identity.email(),
          identity.username());
    }
    String rawToken = token();
    AccountInvite invite = prepare(identity, rawToken, initialPassword, reissue, expectedInviteId);
    try {
      // Persist a recoverable setup row first. If the identity provider is briefly unavailable,
      // the direct-created account still has a named DRAFT failure and a safe operator reissue.
      // No database lock is held across this remote check or the later SMTP handover.
      confirmation.requireSameCurrentIdentity(
          identity.id(),
          identity.role(),
          identity.tenantId(),
          identity.email(),
          identity.username());
      String setupUrl = urls.buildAcceptUrl(identity.role(), rawToken);
      Map<String, String> values =
          new LinkedHashMap<>(
              brandValues.values(branding.resolve(identity.tenantId()), identity.tenantId()));
      values.put("setupUrl", setupUrl);
      values.put("inviteExpiresAt", invite.getExpiresAt().toLocalDate().toString());
      RenderedEmail rendered = renderer.render(TEMPLATE, identity.tone(), values);
      markReadyToSend(invite.getId(), invite.getTokenHash());
      var receipt = mail.sendRendered(identity.email(), rendered);
      recordDelivery(
          invite.getId(),
          identity.email(),
          InviteEmailDeliveryStatus.SENT,
          receipt.sentAt() == null
              ? null
              : LocalDateTime.ofInstant(receipt.sentAt(), ZoneId.systemDefault()),
          null);
      invite.setStatus(AccountInviteStatus.EMAIL_SENT);
      return invite;
    } catch (SmtpSendException failure) {
      recordFailure(
          invite.getId(),
          identity.email(),
          failure.isConfirmedNotSent()
              ? "SETUP_MAIL_CONFIRMED_NOT_SENT"
              : "SETUP_MAIL_DELIVERY_UNCERTAIN",
          failure.isConfirmedNotSent());
      throw failure;
    } catch (RuntimeException preparationFailure) {
      recordFailure(invite.getId(), identity.email(), "SETUP_PREPARATION_FAILED", true);
      throw new IllegalStateException(
          "Account exists but its setup mail could not be prepared; operator action is required");
    }
  }

  private AccountInvite prepare(
      ExistingIdentity identity,
      String rawToken,
      String initialPassword,
      boolean reissue,
      Long expectedInviteId) {
    return inTransaction(
        () -> {
          AccountInvite previous = invites.findByActiveSetupIdentityKey(identity.id()).orElse(null);
          String verifier;
          if (previous != null) {
            if (expectedInviteId != null && !expectedInviteId.equals(previous.getId())) {
              throw new ConflictException("Selected setup link was already replaced");
            }
            if (!reissue) {
              throw new ConflictException("An account setup link already exists");
            }
            if (previous.getPurpose() != AccountInvitePurpose.EXISTING_ACCOUNT_SETUP
                || (previous.getProvisioningStatus() != AccountInviteProvisioningStatus.PENDING
                    && previous.getProvisioningStatus()
                        != AccountInviteProvisioningStatus.FAILED)) {
              throw new ConflictException(
                  "Account setup needs operator review before another link can be issued");
            }
            if (previous.getStatus() != AccountInviteStatus.DRAFT
                && previous.getStatus() != AccountInviteStatus.EMAIL_SENT
                && previous.getStatus() != AccountInviteStatus.EXPIRED) {
              throw new ConflictException("Account setup is no longer eligible for reissue");
            }
            if (!Objects.equals(previous.getTargetRole(), identity.role())
                || !Objects.equals(previous.getTenantId(), identity.tenantId())
                || !Objects.equals(previous.getRecipientEmail(), identity.email())
                || !Objects.equals(previous.getSetupBoundUsername(), identity.username())) {
              throw new ConflictException(
                  "Account setup binding changed; operator review is required");
            }
            verifier = previous.getInitialPasswordVerifier();
            if (isBlank(verifier)) {
              throw new ConflictException("Account setup needs operator review before reissue");
            }
            previous.setStatus(AccountInviteStatus.SUPERSEDED);
            previous.setActiveSetupIdentityKey(null);
            previous.setInitialPasswordVerifier(null);
            previous.setSupersededAt(LocalDateTime.now());
            invites.saveAndFlush(previous);
          } else if (reissue) {
            throw new ConflictException("Account setup needs operator review before reissue");
          } else {
            verifier = initialPasswords.encode(initialPassword);
          }
          // Recheck the saved row after acquiring the old-link lock. A changed account cannot
          // inherit
          // the old link's recipient, tenant or role even when the caller supplied the same
          // identity ID.
          ExistingIdentity fresh = loadCurrentIdentity(identity.role(), identity.id());
          if (!identity.equals(fresh)) {
            throw new ConflictException("Account changed while preparing setup");
          }
          LocalDateTime now = LocalDateTime.now();
          AccountInvite invite =
              AccountInvite.builder()
                  .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                  .targetRole(identity.role())
                  .tenantId(identity.tenantId())
                  .recipientEmail(identity.email())
                  .setupBoundUsername(identity.username())
                  .provisionedUserId(identity.id())
                  .activeSetupIdentityKey(identity.id())
                  .initialPasswordVerifier(verifier)
                  .tokenHash(AccountInviteService.hash(rawToken))
                  .status(AccountInviteStatus.DRAFT)
                  .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
                  .emailVerificationStatus(EmailVerificationStatus.PENDING)
                  .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                  .expiresAt(now.plusDays(EXPIRY_DAYS))
                  .createdByUserId(actor.getUserId())
                  .createdByUsername(actor.getUsername())
                  .createDate(now)
                  .updateDate(now)
                  .build();
          return invites.saveAndFlush(invite);
        });
  }

  private void markReadyToSend(Long inviteId, String expectedHash) {
    inTransaction(
        () -> {
          AccountInvite invite =
              invites
                  .findByIdForUpdate(inviteId)
                  .orElseThrow(() -> new ConflictException("Setup link was replaced"));
          if (invite.getPurpose() != AccountInvitePurpose.EXISTING_ACCOUNT_SETUP
              || invite.getStatus() != AccountInviteStatus.DRAFT
              || !Objects.equals(expectedHash, invite.getTokenHash())) {
            throw new ConflictException("Setup link was replaced");
          }
          invite.setStatus(AccountInviteStatus.EMAIL_SENT);
          invite.setUpdateDate(LocalDateTime.now());
          invites.saveAndFlush(invite);
          return null;
        });
  }

  private void recordFailure(
      Long inviteId, String recipient, String reason, boolean definitelyUnsent) {
    try {
      inTransaction(
          () -> {
            AccountInvite current = invites.findByIdForUpdate(inviteId).orElse(null);
            if (current != null
                && current.getPurpose() == AccountInvitePurpose.EXISTING_ACCOUNT_SETUP
                && (current.getStatus() == AccountInviteStatus.DRAFT
                    || current.getStatus() == AccountInviteStatus.EMAIL_SENT)
                && current.getProvisioningStatus() != AccountInviteProvisioningStatus.IN_PROGRESS) {
              current.setProvisioningFailureReason(reason);
              if (definitelyUnsent) {
                current.setStatus(AccountInviteStatus.DRAFT);
              }
              invites.saveAndFlush(current);
            }
            return null;
          });
      recordDelivery(inviteId, recipient, InviteEmailDeliveryStatus.FAILED, null, reason);
    } catch (RuntimeException auditFailure) {
      log.error(
          "Existing-account setup {} failed and its delivery audit is unavailable ({})",
          inviteId,
          auditFailure.getClass().getSimpleName());
    }
  }

  private void recordDelivery(
      Long inviteId,
      String recipient,
      InviteEmailDeliveryStatus status,
      LocalDateTime sentAt,
      String reason) {
    try {
      inTransaction(
          () -> {
            // Never persist the raw setup URL or the initial password in a delivery snapshot.
            deliveries.saveAndFlush(
                InviteEmailDelivery.builder()
                    .accountInviteId(inviteId)
                    .templateKind(KIND)
                    .recipientSnapshot(recipient)
                    .subjectSnapshot(TEMPLATE)
                    .bodySnapshot(TEMPLATE)
                    .status(status)
                    .sentAt(sentAt)
                    .failureReason(reason)
                    .createDate(LocalDateTime.now())
                    .build());
            return null;
          });
    } catch (RuntimeException auditFailure) {
      log.error(
          "Existing-account setup {} mail audit could not be recorded ({})",
          inviteId,
          auditFailure.getClass().getSimpleName());
    }
  }

  private ExistingIdentity loadCurrentIdentity(AccountInviteTargetRole role, String identityId) {
    if (role == AccountInviteTargetRole.COUNSELLOR) {
      var consultant =
          consultants
              .findByIdAndDeleteDateIsNull(identityId)
              .orElseThrow(() -> new NotFoundException("Counsellor account not found"));
      if ((multitenancyEnabled && consultant.getTenantId() == null)
          || isBlank(consultant.getEmail())
          || isBlank(consultant.getUsername())) {
        throw new BadRequestException("Counsellor account setup binding is incomplete");
      }
      OrisoEmailRenderer.Tone tone;
      try {
        tone = OrisoEmailRenderer.Tone.of(consultant.getLanguageCode());
      } catch (IllegalArgumentException absentLanguage) {
        tone = OrisoEmailRenderer.Tone.DE_FORMAL;
      }
      return new ExistingIdentity(
          identityId,
          role,
          consultant.getTenantId(),
          consultant.getEmail(),
          consultant.getUsername(),
          tone);
    }
    Admin.AdminType type =
        switch (role) {
          case TENANT_ADMIN -> Admin.AdminType.TENANT;
          case AGENCY_ADMIN -> Admin.AdminType.AGENCY;
          default -> throw new BadRequestException("Existing account role is not supported");
        };
    var admin =
        admins
            .findByIdAndType(identityId, type)
            .orElseThrow(() -> new NotFoundException("Admin account not found"));
    if ((multitenancyEnabled && admin.getTenantId() == null)
        || isBlank(admin.getEmail())
        || isBlank(admin.getUsername())) {
      throw new BadRequestException("Admin account setup binding is incomplete");
    }
    return new ExistingIdentity(
        identityId,
        role,
        admin.getTenantId(),
        admin.getEmail(),
        admin.getUsername(),
        OrisoEmailRenderer.Tone.DE_FORMAL);
  }

  private void assertMayAct(AccountInviteTargetRole role, String identityId) {
    if (role == AccountInviteTargetRole.COUNSELLOR) {
      adminScope.assertMay(AdminScope.Target.counsellor(identityId));
    } else if (role == AccountInviteTargetRole.AGENCY_ADMIN
        || role == AccountInviteTargetRole.TENANT_ADMIN) {
      adminScope.assertMay(AdminScope.Target.admin(identityId));
    } else {
      throw new BadRequestException("Existing account role is not supported");
    }
  }

  private <T> T inTransaction(java.util.function.Supplier<T> action) {
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction.execute(status -> action.get());
  }

  private static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record ExistingIdentity(
      String id,
      AccountInviteTargetRole role,
      Long tenantId,
      String email,
      String username,
      OrisoEmailRenderer.Tone tone) {}
}
