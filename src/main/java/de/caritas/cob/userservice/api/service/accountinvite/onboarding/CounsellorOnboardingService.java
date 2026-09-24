package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyAdminInviteProvisioningService;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService.ProvisionCounsellorCommand;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public counsellor onboarding behind an invite link (#997, Admin-panel wizard). Clone of the
 * proven {@link TenantAdminOnboardingService} contract (#569 chain) for {@code COUNSELLOR} invites:
 * resolve returns the invite state (prefill data + the topic coverage of the invite's
 * department/agency routing) keyed by the raw link token; register creates the consultant and
 * returns TOTP setup material; two-factor confirms the TOTP setup with a first one-time password.
 * Links are strictly single-use but stay resumable at the 2FA step while the mandatory activation
 * is pending and the link is unexpired (same resume contract as the tenant-admin flow and the
 * public accept endpoint).
 *
 * <p>Registration deliberately owns NO write path of its own: the consultant is created through
 * {@link CounsellorInviteProvisioningService#acceptInvite}, i.e. the exact same domain path
 * (ConsultantAdminFacade → CreateConsultantSaga + agency relation + invite lifecycle from PR #967)
 * the public accept endpoint uses — a wizard-created consultant is indistinguishable from one
 * created via the normal admin form.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounsellorOnboardingService {

  private static final int MIN_PASSWORD_LENGTH = 8;

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull CounsellorInviteProvisioningService counsellorInviteProvisioningService;
  private final @NonNull IdentitySecondFactor identitySecondFactor;
  private final @NonNull IdentityProfileLookup identityProfileLookup;
  private final @NonNull AgencyService agencyService;
  private final @NonNull TopicService topicService;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull AgencyCreationClient agencyCreationClient;
  private final @NonNull AgencyAdminInviteProvisioningService agencyAdminInviteProvisioningService;

  /**
   * Drives the SHORT database-only transactions of this flow explicitly instead of annotating the
   * public entry points: the invite row is read under a PESSIMISTIC_WRITE lock, and every remote
   * call of this service (AgencyService, TopicService, Keycloak) has to happen OUTSIDE that lock.
   */
  private final @NonNull PlatformTransactionManager transactionManager;

  /**
   * Resolves the invite state for a raw link token. Mirrors the tenant-admin resolve state machine:
   * a deliverable ({@code EMAIL_SENT}) unexpired invite resolves plainly and carries the topic
   * coverage the wizard's topic step may offer; a consumed invite whose mandatory 2FA activation is
   * still open and whose link is unexpired resolves with a pending two-factor resume; every other
   * state maps to the distinct link-death reasons (410 body {@code reason}, unknown tokens 404).
   *
   * <p>Transaction shape (#1008 review): the locked load, the expiry transition and the state
   * classification are ONE short database transaction ({@link #loadInviteForResolve}); the topic
   * coverage (AgencyService + TopicService over HTTP) and the Keycloak repair of a lost TOTP secret
   * run afterwards, outside it. A hanging upstream can therefore no longer hold the invite's
   * PESSIMISTIC_WRITE lock — nor a database connection — for its whole timeout.
   */
  public CounsellorOnboardingState resolveOnboardingInvite(String rawToken) {
    ResolvedOnboardingInvite resolved = loadInviteForResolve(rawToken);
    // Thrown only now, i.e. after the transaction committed, so a persisted EXPIRED transition
    // survives the link-death answer (what noRollbackFor used to buy).
    resolved.rethrowLinkDeath();

    AccountInvite invite = resolved.invite();
    if (resolved.pendingTwoFactorResume()) {
      repairMissingTotpSecret(invite);
      return new CounsellorOnboardingState(invite, true, List.of());
    }
    CoverageResolution coverage = resolveTopicCoverage(invite);
    return new CounsellorOnboardingState(
        invite, false, coverage.topics(), coverage.availableTopics(), coverage.agencyExists());
  }

  /** The database-only part of {@link #resolveOnboardingInvite}: locked load and classification. */
  private ResolvedOnboardingInvite loadInviteForResolve(String rawToken) {
    return inTransaction(
        () -> {
          AccountInvite invite = findCounsellorInvite(rawToken);
          LocalDateTime now = LocalDateTime.now();

          if (invite.getStatus() == AccountInviteStatus.EMAIL_SENT) {
            AccountInviteLinkException expired = expireIfPastExpiry(invite, now);
            return expired == null
                ? ResolvedOnboardingInvite.open(invite)
                : ResolvedOnboardingInvite.dead(expired);
          }
          if (isResumableAtTwoFactorStep(invite, now)) {
            return ResolvedOnboardingInvite.pendingTwoFactorResume(invite);
          }
          return ResolvedOnboardingInvite.dead(linkDeathException(invite));
        });
  }

  /**
   * Creates the consultant for the invite and returns the TOTP setup material for the 2FA step.
   *
   * <p>Deliberately NOT transactional at this level: the whole creation is delegated to {@link
   * CounsellorInviteProvisioningService#acceptInvite}, which brings its own carefully tuned
   * transaction semantics ({@code noRollbackFor} so a failed provisioning keeps its FAILED audit
   * state) and its own external-state compensation. This method only validates the wizard input up
   * front (before the invite is touched) and persists the pending TOTP secret afterwards so the
   * resume path can re-show it (#569 resume contract).
   */
  public CounsellorRegistrationResult registerCounsellor(
      String rawToken, RegisterCounsellorCommand requestedCommand) {
    RegisterCounsellorCommand command = requestedCommand;
    validateRegistration(command);
    AccountInvite invite = findCounsellorInvite(rawToken);
    LocalDateTime now = LocalDateTime.now();

    if (invite.getStatus() != AccountInviteStatus.EMAIL_SENT) {
      throw linkDeathException(invite);
    }
    AccountInviteLinkException expired = expireIfPastExpiry(invite, now);
    if (expired != null) {
      throw expired;
    }
    // ORISO-Admin#1026 slice 3: an AGENCY_ADMIN invite runs this wizard too. "Also counsellor"
    // (the inviter's proposal, which the invitee may override here) decides whether a consultant
    // is created; only a counselling invitee needs a topic.
    boolean agencyAdmin = invite.getTargetRole() == AccountInviteTargetRole.AGENCY_ADMIN;
    boolean counsels = !agencyAdmin || alsoCounsellor(invite, command);
    CoverageResolution coverage = resolveTopicCoverage(invite);
    if (counsels) {
      command = withAtLeastOneTopic(command, coverage);
      validateTopicSelection(command.topicIds(), coverage);
    } else if (command.topicIds() != null && !command.topicIds().isEmpty()) {
      validateTopicSelection(command.topicIds(), coverage);
    } else if (!coverage.agencyExists()) {
      // ORISO-Admin#1026 (Q28): a founding admin who does not counsel still gives the new agency
      // at least one topic — the counsellors queued for it pick from those.
      throw new BadRequestException("A new agency needs at least one topic");
    }

    // A reserved (not yet created) Beratungsstellen-ID: the invitee named the agency in the
    // wizard and it has to exist — under exactly the reserved ID — before the consultant can be
    // attached to it. Deliberately here, OUTSIDE any transaction of this service: the invite's
    // PESSIMISTIC_WRITE lock must never be held across a remote call (#1008 review).
    boolean agencyCreated = false;
    if (!coverage.agencyExists()) {
      createReservedAgency(invite, command);
      agencyCreated = true;
    }

    // The agency admin administers the invite's agency; a counsellor gets admin rights only when
    // they just brought the agency into existence (#998).
    AccountInvite accepted =
        counsels
            ? counsellorInviteProvisioningService.acceptInvite(
                rawToken, toProvisionCommand(command, agencyCreated || agencyAdmin))
            : agencyAdminInviteProvisioningService.acceptAsAgencyAdmin(
                rawToken, command.username(), command.password());

    String consultantId = counsels ? accepted.getProvisionedUserId() : null;
    if (!AccountInviteService.isTwoFactorGateSatisfied(accepted.getTwoFactorStatus())) {
      IdentityOtpCredential otpInfo =
          identitySecondFactor.getOtpCredential(
              usernameTranscoder.encodeUsername(command.username().trim()));
      if (otpInfo == null || isBlank(otpInfo.secret())) {
        // The consultant exists and the invite is consumed — do NOT undo that here. The link
        // stays resumable at the 2FA step, and both the resolve resume path and the two-factor
        // activation re-request the setup material from Keycloak (repairMissingTotpSecret), so
        // this degradation is retryable instead of a permanently blocked account.
        log.error(
            "Keycloak issued no TOTP setup material for counsellor onboarding invite {} — the"
                + " 2FA step will re-request it on resume",
            accepted.getId());
        return new CounsellorRegistrationResult(consultantId, null, null, true);
      }
      accepted.setTotpPendingSecret(otpInfo.secret());
      accepted.setUpdateDate(LocalDateTime.now());
      accountInviteRepository.save(accepted);
      return new CounsellorRegistrationResult(
          consultantId, otpInfo.secret(), otpInfo.secretQrCode(), true);
    }
    // 2FA waived/not required for this invite — the wizard skips the 2FA step.
    return new CounsellorRegistrationResult(consultantId, null, null, false);
  }

  /**
   * Confirms the pending TOTP setup with a first one-time password. Same contract as the
   * tenant-admin endpoint: an invalid or rejected code answers 400 (the wizard maps 400/422 to its
   * invalid-code state); once the gate is satisfied the link is terminally consumed.
   *
   * <p>Transaction shape (#1008 review): the state checks are one short transaction, the Keycloak
   * round trips (profile lookup, OTP verification, secret repair) run outside it, and consuming the
   * gate is a second short transaction. The invite's row lock is never held across a remote call.
   */
  public void activateTwoFactor(String rawToken, String oneTimePassword) {
    if (isBlank(oneTimePassword)) {
      throw new BadRequestException("otp is required");
    }
    AccountInvite invite = loadInviteForTwoFactorActivation(rawToken);

    if (isBlank(invite.getTotpPendingSecret())) {
      // Registration may have received no setup material from Keycloak — re-request it instead
      // of rejecting the account forever (the wizard re-shows the repaired secret on resolve).
      repairMissingTotpSecret(invite);
    }
    if (isBlank(invite.getTotpPendingSecret())) {
      throw new BadRequestException("No pending TOTP setup exists for this invite");
    }

    var profile =
        identityProfileLookup
            .findById(invite.getAcceptedByUserId())
            .orElseThrow(
                () -> new BadRequestException("No identity profile exists for this invite"));
    boolean valid =
        identitySecondFactor.setUpOtpCredential(
            profile.username(), oneTimePassword.trim(), invite.getTotpPendingSecret());
    if (!valid) {
      throw new BadRequestException("Invalid one-time password");
    }

    consumeTwoFactorGate(invite);
  }

  /**
   * Issue #1049: the consultant this invite created, while the link is still resumable at the
   * two-factor step. At that point the raw invite token is the only credential the wizard holds, so
   * the picture write is guarded by exactly the gate that guards the two-factor activation:
   * registration must have happened, the link must not be dead, expired or terminally consumed.
   */
  public String consultantIdForOnboardingPicture(String rawToken) {
    return requireOnboardingPictureInvite(rawToken).getProvisionedUserId();
  }

  /**
   * Same gate as {@link #consultantIdForOnboardingPicture}, but for a caller that already holds a
   * write transaction. The invite row is locked here ({@code findByTokenHash}'s pessimistic lock)
   * so an expiry or terminal consumption racing the ClamAV scan cannot persist after the credential
   * has died.
   */
  public AccountInvite requireOnboardingPictureInvite(String rawToken) {
    AccountInvite invite = loadInviteForTwoFactorActivation(rawToken);
    if (isBlank(invite.getProvisionedUserId())) {
      throw new BadRequestException("Registration has not happened yet for this invite");
    }
    return invite;
  }

  /**
   * The database-only precondition check of {@link #activateTwoFactor}. Every rejection here is
   * thrown before anything is written, so an ordinary rollback loses nothing.
   */
  private AccountInvite loadInviteForTwoFactorActivation(String rawToken) {
    return inTransaction(
        () -> {
          AccountInvite invite = findCounsellorInvite(rawToken);
          LocalDateTime now = LocalDateTime.now();

          if (invite.getStatus() == AccountInviteStatus.EMAIL_SENT) {
            throw new BadRequestException("Registration has not happened yet for this invite");
          }
          if (invite.getStatus() != AccountInviteStatus.ACCEPTED) {
            throw linkDeathException(invite);
          }
          if (!isResumableAtTwoFactorStep(invite, now)) {
            // Gate already satisfied or resume window expired — terminally consumed.
            throw new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
          }
          if (isBlank(invite.getAcceptedByUserId())) {
            throw new BadRequestException("No pending TOTP setup exists for this invite");
          }
          return invite;
        });
  }

  /**
   * Terminal consumption of the link once Keycloak accepted the one-time password. The pending
   * secret is cleared FIRST so the gate transition — which re-reads the invite by its acceptor —
   * wins over the merge of the detached row loaded before the Keycloak round trip.
   */
  private void consumeTwoFactorGate(AccountInvite invite) {
    inTransaction(
        () -> {
          invite.setTotpPendingSecret(null);
          invite.setUpdateDate(LocalDateTime.now());
          accountInviteRepository.save(invite);
          accountInviteService.markTwoFactorActive(invite.getAcceptedByUserId());
          return null;
        });
  }

  /** Runs {@code action} in its own short database transaction (no remote call belongs inside). */
  private <T> T inTransaction(Supplier<T> action) {
    return new TransactionTemplate(transactionManager).execute(status -> action.get());
  }

  /**
   * The topics the wizard may offer: the invite's agency topic coverage plus the invite's routed
   * department topic (Admin PR #663 capture). Lookups run under the invite's tenant (the caller is
   * anonymous — there is no request tenant). Lookups are best-effort: an unreachable AgencyService
   * or TopicService degrades to the routed department topic instead of blocking the public flow —
   * but the resolution reports whether the agency lookup failed, because a DEGRADED set is a usable
   * prefill yet not a validation baseline (see {@link #validateTopicSelection}). Names missing from
   * the active-topics map stay {@code null} and the client falls back to a generic label.
   */
  private CoverageResolution resolveTopicCoverage(AccountInvite invite) {
    TenantData requestTenant = snapshotTenantContext();
    TenantContext.setCurrentTenant(invite.getTenantId());
    boolean agencyLookupFailed = false;
    // A reserved (not yet created) Beratungsstellen-ID answers "no agency" — the invitee then
    // names the agency in the wizard. An unreachable AgencyService is NOT "no agency".
    boolean agencyExists = true;
    try {
      Set<Long> topicIds = new LinkedHashSet<>();
      if (invite.getAgencyId() != null) {
        try {
          var agency = agencyService.getAgencyWithoutCaching(invite.getAgencyId());
          if (agency == null) {
            agencyExists = false;
          } else if (agency.getTopicIds() != null) {
            topicIds.addAll(agency.getTopicIds());
          }
        } catch (RuntimeException exception) {
          agencyLookupFailed = true;
          log.warn(
              "Counsellor onboarding could not resolve the agency {} topic coverage — falling"
                  + " back to the invite's department topic",
              invite.getAgencyId(),
              exception);
        }
      }
      if (invite.getDepartmentId() != null) {
        topicIds.add(invite.getDepartmentId());
      }
      TopicLookup topicLookup = safeActiveTopicsById();
      Map<Long, TopicDTO> namesById = topicLookup.topicsById();
      List<TopicOption> topics =
          topicIds.stream()
              .map(
                  id -> {
                    TopicDTO topic = namesById.get(id);
                    return new TopicOption(id, topic == null ? null : topic.getName());
                  })
              .toList();
      // Every active tenant topic is selectable on top of the coverage (owner decision
      // 2026-09-17): the invitee removes preselected topics or adds further ones.
      List<TopicOption> availableTopics =
          namesById.values().stream()
              .filter(topic -> topic.getId() != null)
              .map(topic -> new TopicOption(topic.getId(), topic.getName()))
              .toList();
      return new CoverageResolution(
          topics, availableTopics, agencyLookupFailed, topicLookup.failed(), agencyExists);
    } finally {
      restoreTenantContext(requestTenant);
    }
  }

  /**
   * An EMPTY active-topics map is an answer, not a failure: a tenant may simply have no active
   * topics, and a topic outside the coverage then IS invalid input (400). Only an actual exception
   * from TopicService makes the set degraded — indeterminate rather than authoritative — which is
   * what {@link #validateTopicSelection} turns into a retryable 5xx. Reporting emptiness as failure
   * answered 500 for plain client errors (CI failure of {@code
   * CounsellorOnboardingWizardIT.registerWithTopicOutsideHealthyCoverage_answers400...}).
   */
  private TopicLookup safeActiveTopicsById() {
    try {
      Map<Long, TopicDTO> topicsById = topicService.getAllActiveTopicsMap();
      return new TopicLookup(topicsById == null ? Map.of() : topicsById, false);
    } catch (RuntimeException exception) {
      log.warn("Counsellor onboarding could not resolve topic names", exception);
      return new TopicLookup(Map.of(), true);
    }
  }

  /**
   * The tenant's active topics and whether the lookup itself failed (as opposed to being empty).
   */
  private record TopicLookup(Map<Long, TopicDTO> topicsById, boolean failed) {}

  private static TenantData snapshotTenantContext() {
    TenantData tenantData = TenantContext.getCurrentTenantData();
    return tenantData == null
        ? null
        : new TenantData(tenantData.getTenantId(), tenantData.getSubdomain());
  }

  private static void restoreTenantContext(TenantData tenantData) {
    if (tenantData == null) {
      TenantContext.clear();
    } else {
      TenantContext.setCurrentTenantData(tenantData);
    }
  }

  /**
   * Creates the Beratungsstelle the invite reserved but never created (ORISO-Admin#998).
   *
   * <p>Not compensated on purpose, and the one step of this flow that is not: if the consultant
   * creation afterwards fails, the provisioning service rolls the consultant back and records the
   * failure, but the agency stays. Deleting it again would be the wrong trade — the reservation is
   * already consumed, the ID can never be re-issued, and a second attempt on the resumable link
   * would then have nothing to create the agency under. An agency without its counsellor is a
   * visible, repairable state in the admin panel; a consumed reservation with no agency is not. The
   * follow-up attempt sees {@code agencyExists == true} and simply attaches to it.
   */
  private void createReservedAgency(AccountInvite invite, RegisterCounsellorCommand command) {
    if (isBlank(command.agencyName())) {
      throw new BadRequestException("agency.name is required for an invite without an agency");
    }
    TenantData requestTenant = snapshotTenantContext();
    TenantContext.setCurrentTenant(invite.getTenantId());
    try {
      agencyCreationClient.createAgencyWithReservedId(
          invite.getAgencyId(),
          command.agencyName().trim(),
          invite.getTenantId(),
          command.topicIds());
    } finally {
      restoreTenantContext(requestTenant);
    }
  }

  private ProvisionCounsellorCommand toProvisionCommand(
      RegisterCounsellorCommand command, boolean grantAgencyAdmin) {
    return new ProvisionCounsellorCommand(
        command.username(),
        command.password(),
        // The wizard collects no conversational-register preference; formal address ("Sie") is
        // the platform default and stays editable in the consultant's own profile.
        Boolean.TRUE,
        null,
        trimToNull(command.salutation()),
        trimToNull(command.position()),
        trimToNull(command.title()),
        trimToNull(command.displayName()),
        trimToNull(command.internalDisplayName()),
        command.topicIds(),
        trimToNull(command.avatarKind()),
        trimToNull(command.avatarId()),
        grantAgencyAdmin);
  }

  /**
   * Compensation for a registration that received no TOTP setup material from Keycloak (#997
   * review): without a stored pending secret the 2FA activation could never succeed and the account
   * would be permanently blocked behind a consumed invite. Re-requests the setup material for the
   * provisioned user and persists it; best-effort — on continued failure the invite stays resumable
   * and the next resolve retries.
   *
   * <p>Called from OUTSIDE a transaction on every path (#1008 review): it talks to Keycloak, so the
   * write it may do is a repository call of its own instead of work inside the caller's transaction
   * — the invite's row lock is not held while Keycloak is asked.
   */
  private void repairMissingTotpSecret(AccountInvite invite) {
    if (!isBlank(invite.getTotpPendingSecret()) || isBlank(invite.getAcceptedByUserId())) {
      return;
    }
    try {
      var profile = identityProfileLookup.findById(invite.getAcceptedByUserId()).orElse(null);
      if (profile == null) {
        // The invite id identifies the record for support; the identity-provider user id is a
        // direct user identifier and must not be written to the log (#1008 review).
        log.warn(
            "Cannot repair the missing TOTP setup material for invite {} — no identity profile"
                + " exists for its acceptor",
            invite.getId());
        return;
      }
      IdentityOtpCredential otpInfo = identitySecondFactor.getOtpCredential(profile.username());
      if (otpInfo == null || isBlank(otpInfo.secret())) {
        log.warn(
            "Keycloak again issued no TOTP setup material for invite {} — the 2FA step stays"
                + " resumable and the next resolve retries",
            invite.getId());
        return;
      }
      invite.setTotpPendingSecret(otpInfo.secret());
      invite.setUpdateDate(LocalDateTime.now());
      accountInviteRepository.save(invite);
      log.info("Repaired the missing TOTP setup material for invite {}", invite.getId());
    } catch (RuntimeException exception) {
      log.warn(
          "Repairing the missing TOTP setup material for invite {} failed — the 2FA step stays"
              + " resumable and the next resolve retries",
          invite.getId(),
          exception);
    }
  }

  private AccountInvite findCounsellorInvite(String rawToken) {
    // Delegates to the transactional lookup (the token-hash query carries a lock hint that
    // requires an active transaction; registerCounsellor itself deliberately runs without one).
    // Inside one of this service's short transactions the lookup simply joins it.
    AccountInvite invite = accountInviteService.findInviteByToken(rawToken);
    if (!runsTheCounsellorWizard(invite.getTargetRole())) {
      // Tokens of other roles must not resolve on the counsellor onboarding path.
      throw new NotFoundException("Account invite not found");
    }
    return invite;
  }

  /** The roles onboarded by this wizard: counsellors and (ORISO-Admin#1026) agency admins. */
  public static boolean runsTheCounsellorWizard(AccountInviteTargetRole targetRole) {
    return targetRole == AccountInviteTargetRole.COUNSELLOR
        || targetRole == AccountInviteTargetRole.AGENCY_ADMIN;
  }

  /** The invitee's choice wins; without one the inviter's proposal; without that: counsels. */
  private static boolean alsoCounsellor(AccountInvite invite, RegisterCounsellorCommand command) {
    if (command.alsoCounsellor() != null) {
      return command.alsoCounsellor();
    }
    return !Boolean.FALSE.equals(invite.getAlsoCounsellor());
  }

  /**
   * Persists the {@code EMAIL_SENT -> EXPIRED} transition of an overdue link and RETURNS the
   * link-death answer instead of throwing it: inside a transaction the caller must let that write
   * commit before the exception leaves the flow.
   *
   * @return the {@code EXPIRED} link-death exception, or {@code null} when the link is still live
   */
  private AccountInviteLinkException expireIfPastExpiry(AccountInvite invite, LocalDateTime now) {
    if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(now)) {
      invite.setStatus(AccountInviteStatus.EXPIRED);
      invite.setActiveRecipientKey(null);
      invite.setUpdateDate(now);
      accountInviteRepository.save(invite);
      return new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
    }
    return null;
  }

  private static boolean isResumableAtTwoFactorStep(AccountInvite invite, LocalDateTime now) {
    boolean twoFactorStillPending =
        !AccountInviteService.isTwoFactorGateSatisfied(invite.getTwoFactorStatus());
    boolean withinExpiryWindow =
        invite.getExpiresAt() == null || !invite.getExpiresAt().isBefore(now);
    return invite.getStatus() == AccountInviteStatus.ACCEPTED
        && twoFactorStillPending
        && withinExpiryWindow;
  }

  private static AccountInviteLinkException linkDeathException(AccountInvite invite) {
    return switch (invite.getStatus()) {
      case ACCEPTED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
      case REVOKED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.REVOKED);
      case SUPERSEDED ->
          new AccountInviteLinkException(AccountInviteLinkException.Reason.SUPERSEDED);
      case EXPIRED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
      default -> new AccountInviteLinkException(AccountInviteLinkException.Reason.NOT_ACTIVE);
    };
  }

  private static void validateRegistration(RegisterCounsellorCommand command) {
    if (command == null) {
      throw new BadRequestException("Request body is required");
    }
    if (isBlank(command.username())) {
      throw new BadRequestException("account.username is required");
    }
    if (isBlank(command.password()) || command.password().length() < MIN_PASSWORD_LENGTH) {
      throw new BadRequestException(
          "account.password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
    }
  }

  /**
   * Every counsellor needs a topic: an invitee who picked none gets the coverage's only topic; with
   * several (or none) on offer they must choose.
   */
  private static RegisterCounsellorCommand withAtLeastOneTopic(
      RegisterCounsellorCommand command, CoverageResolution coverage) {
    if (command.topicIds() != null && !command.topicIds().isEmpty()) {
      return command;
    }
    if (coverage.topics().size() != 1) {
      throw new BadRequestException("At least one topic must be selected");
    }
    return new RegisterCounsellorCommand(
        command.username(),
        command.password(),
        command.salutation(),
        command.position(),
        command.title(),
        command.displayName(),
        command.internalDisplayName(),
        List.of(coverage.topics().get(0).id()),
        command.avatarKind(),
        command.avatarId(),
        command.agencyName(),
        command.alsoCounsellor());
  }

  /**
   * The invitee chooses topics only WITHIN the invite's department/agency coverage — the token
   * never grants a wider assignment than the inviting admin routed (#997 contract).
   *
   * <p>Dependency failure vs client error (#997 review): a selection inside the resolved set is
   * provably valid even when the agency lookup failed (the set only ever shrinks on failure). But a
   * selection OUTSIDE a degraded set is indeterminate — the topic may well be in the real agency
   * coverage — so that answers 5xx (retry), never 400, to avoid misclassifying valid input as a
   * client error during an AgencyService outage.
   *
   * <p>Only the AGENCY lookup can degrade this decision. The active-topics lookup contributes the
   * NAMES and the tenant-wide widening, not the grant itself: when AgencyService answered, the
   * invite's coverage is authoritative and a topic outside it is a client error — an empty or
   * unavailable topic list does not turn that into an outage ({@code
   * CounsellorOnboardingWizardIT.registerWithTopicOutsideHealthyCoverage_answers400...}).
   */
  private static void validateTopicSelection(List<Long> chosen, CoverageResolution coverage) {
    Set<Long> allowed =
        new LinkedHashSet<>(coverage.topics().stream().map(TopicOption::id).toList());
    coverage.availableTopics().forEach(topic -> allowed.add(topic.id()));
    for (Long topicId : chosen) {
      if (topicId == null) {
        throw new BadRequestException("Topic null is outside the coverage of this invite");
      }
      if (!allowed.contains(topicId)) {
        if (coverage.agencyLookupFailed()) {
          throw new InternalServerErrorException(
              "Topic "
                  + topicId
                  + " cannot be verified against the invite's agency coverage right now — the"
                  + " agency lookup is unavailable, retry the registration",
              LogService::logInternalServerError);
        }
        throw new BadRequestException(
            "Topic " + topicId + " is outside the coverage of this invite");
      }
    }
  }

  /**
   * Resolved coverage, the tenant's active topics, whether either lookup failed (degraded set — a
   * selection outside it is indeterminate, not invalid), and whether the invite's agency exists.
   */
  private record CoverageResolution(
      List<TopicOption> topics,
      List<TopicOption> availableTopics,
      boolean agencyLookupFailed,
      boolean topicLookupFailed,
      boolean agencyExists) {}

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String trimToNull(String value) {
    return isBlank(value) ? null : value.trim();
  }

  /** A selectable topic of the invite's coverage; {@code name} may be null when unresolvable. */
  public record TopicOption(Long id, String name) {}

  /**
   * Resolved onboarding state: the invite, whether the flow re-enters at the 2FA step (#569 resume
   * contract), and the topic coverage the wizard's topic step offers (empty on resume — the 2FA
   * step shows no topics).
   */
  public record CounsellorOnboardingState(
      AccountInvite invite,
      boolean pendingTwoFactorResume,
      List<TopicOption> topics,
      /** The tenant's active topics the invitee may add on top of the coverage. */
      List<TopicOption> availableTopics,
      /** False when the invite's agency ID is still a reservation (new Beratungsstelle). */
      boolean agencyExists) {

    /** Resume/legacy shape: no selectable extras, agency assumed to exist. */
    public CounsellorOnboardingState(
        AccountInvite invite, boolean pendingTwoFactorResume, List<TopicOption> topics) {
      this(invite, pendingTwoFactorResume, topics, List.of(), true);
    }
  }

  /** Input of the wizard registration; mirrors the Admin panel request shape. */
  public record RegisterCounsellorCommand(
      String username,
      String password,
      String salutation,
      String position,
      String title,
      String displayName,
      String internalDisplayName,
      List<Long> topicIds,
      String avatarKind,
      String avatarId,
      /**
       * Name of the Beratungsstelle to create for an invite on a reserved agency ID (wizard section
       * "Ihre Beratungsstelle"). Null for invites into an existing agency; agency creation on
       * accept is the AgencyService/provisioning follow-up.
       */
      String agencyName,
      /**
       * AGENCY_ADMIN invites only (ORISO-Admin#1026, slice 3): the invitee's own choice whether
       * they also counsel; {@code null} keeps the inviter's proposal.
       */
      Boolean alsoCounsellor) {

    /** Shape without the agency-admin choice. */
    public RegisterCounsellorCommand(
        String username,
        String password,
        String salutation,
        String position,
        String title,
        String displayName,
        String internalDisplayName,
        List<Long> topicIds,
        String avatarKind,
        String avatarId,
        String agencyName) {
      this(
          username,
          password,
          salutation,
          position,
          title,
          displayName,
          internalDisplayName,
          topicIds,
          avatarKind,
          avatarId,
          agencyName,
          null);
    }

    /** Shape without avatar or new-agency name (existing agency). */
    public RegisterCounsellorCommand(
        String username,
        String password,
        String salutation,
        String position,
        String title,
        String displayName,
        String internalDisplayName,
        List<Long> topicIds) {
      this(
          username,
          password,
          salutation,
          position,
          title,
          displayName,
          internalDisplayName,
          topicIds,
          null,
          null,
          null);
    }

    /** Shape with avatar, no new-agency name (#1046). */
    public RegisterCounsellorCommand(
        String username,
        String password,
        String salutation,
        String position,
        String title,
        String displayName,
        String internalDisplayName,
        List<Long> topicIds,
        String avatarKind,
        String avatarId) {
      this(
          username,
          password,
          salutation,
          position,
          title,
          displayName,
          internalDisplayName,
          topicIds,
          avatarKind,
          avatarId,
          null);
    }

    /** Shape with new-agency name, no avatar (#998). */
    public RegisterCounsellorCommand(
        String username,
        String password,
        String salutation,
        String position,
        String title,
        String displayName,
        String internalDisplayName,
        List<Long> topicIds,
        String agencyName) {
      this(
          username,
          password,
          salutation,
          position,
          title,
          displayName,
          internalDisplayName,
          topicIds,
          null,
          null,
          agencyName);
    }
  }

  /**
   * The created consultant plus the TOTP setup material for the 2FA step. {@code twoFactorRequired}
   * is false when the invite's gate was waived — the wizard then skips the 2FA step entirely.
   */
  public record CounsellorRegistrationResult(
      String consultantId, String totpSecret, String totpQrCodeBase64, boolean twoFactorRequired) {}
}
