package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.observability.DpaSignedNoticeMetrics;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.SmtpSendException.DeliveryDisposition;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import de.caritas.cob.userservice.api.model.DpaSignedNotice.NoticeRole;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.DpaSignedNoticeRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailOrigin;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/**
 * DPA_SIGNED_NOTICE (ORISO-UserService#1005, epic ORISO-Admin#722): tells the administrator who
 * forwarded the data processing agreement that the signature has landed, and sends the authorised
 * person a separate confirmation receipt (ORISO-UserService#1353).
 *
 * <p>Trigger model — untrusted hint, verified facts: TenantService fires an unauthenticated
 * fire-and-forget hint after a public sign-link confirmation. This service then reads the signature
 * audit list back through the authenticated technical-user client and derives everything (signer,
 * version, timestamp, forward origin) from that authoritative answer. A spoofed or repeated hint
 * can never fabricate a notice; a hint for a self-signed or unsigned tenant finds no forwarded
 * signature and dies silently.
 *
 * <p>Per-role deduplication: a ledger row per (tenant, signed version, recipient role) is claimed
 * in its own transaction BEFORE sending — of two concurrent hints exactly one wins the unique
 * constraint. New verified administrator claims stay pending through mail preparation, so a
 * recovery sweep can pick them up after interruption. Explicit handoff failures release the claim
 * so a later hint can retry. Legacy and signer claims also release on preparation failure.
 *
 * <p><b>Deliberate duplicate on an uncertain delivery (#1341).</b> The release happens for EVERY
 * failure before the handover, including {@link
 * de.caritas.cob.userservice.api.exception.SmtpSendException.DeliveryDisposition#DELIVERY_UNCERTAIN}
 * — the case where SMTP may already have accepted the mail but the client never saw the
 * confirmation (a 502 or a timeout from a Träger's own relay). A later hint can then deliver a
 * SECOND "contract signed" notice for the same signature. That is the chosen trade-off, decided by
 * Frank on 2026-10-02: <b>better twice than never.</b> The notice is the signal that unblocks the
 * organisation's setup, so a missing one stalls the Träger while a duplicate only annoys one
 * administrator.
 *
 * <p>This is the opposite of {@code InviteDelivery}, which KEEPS its claim on an uncertain outcome.
 * An invite mail carries a single-use token: re-sending mints a second link and silently kills the
 * first, so a duplicate there can lock a person out of the account they were already invited to.
 * This notice carries no token and no state — it only repeats a fact that is already true.
 *
 * <p>Open risk, accepted with the trade-off: the trigger endpoint is unauthenticated, so anyone who
 * can reach it can provoke the retry. Every release is logged at WARN and counted by {@code
 * DpaSignedNoticeMetrics}, so a duplicate is visible after the fact rather than silent.
 *
 * <p>Recipient resolution per issue spec: {@code forwardedByUserId} → that admin's account e-mail
 * and account language (identity {@code locale}, fallback {@code de}); a pre-account wizard forward
 * (null forwarder) → the onboarding invite's contact address, fallback language. The signer receipt
 * uses the persisted signer e-mail and confirmation language (DE/EN, German fallback); it has no
 * admin action or editable account-invite template.
 */
@Service
@Slf4j
public class DpaSignedNoticeService {

  private static final PageRequest ADMIN_RECOVERY_BATCH = PageRequest.of(0, 8);
  private static final Duration ADMIN_RECOVERY_BUDGET = Duration.ofSeconds(45);
  private static final Duration ADMIN_RECOVERY_DELAY = Duration.ofMinutes(1);
  private static final Duration ADMIN_PROOF_RETRY_DELAY = Duration.ofMinutes(15);
  // A recorded recipient that still disagrees with the current one after this long is not a
  // transient read: the claim leaves automatic recovery and waits for an operator to reconcile it.
  private static final Duration ADMIN_RECIPIENT_MISMATCH_PARK_AFTER = Duration.ofHours(24);

  static final String SOURCE_FORWARDED_EXTERNAL = "FORWARDED_EXTERNAL";
  static final String STATUS_SIGNED = "SIGNED";
  static final String FALLBACK_LANGUAGE = "de";

  // TenantService timestamps are zoneless UTC; the notice must show German wall-clock time.
  private static final ZoneId MAIL_ZONE = ZoneId.of("Europe/Berlin");

  // Mail copy says "Vertragsunterlagen" / "contract documents", never "AVV" (Frank, 2026-09-23),
  // and "bestätigt" / "confirmed", never "unterzeichnet" (Frank, 2026-09-25).
  static final String DEFAULT_SUBJECT_DE = "Vertragsunterlagen bestätigt – {{tenantName}}";
  static final String DEFAULT_SUBJECT_EN = "Contract documents confirmed – {{tenantName}}";

  static final String DEFAULT_BODY_DE =
      """
      Guten Tag,

      die Vertragsunterlagen für {{tenantName}} wurden bestätigt.

      Vertragsversion: {{dpaVersion}}
      Bestätigt am: {{signedAt}}
      Bestätigt von: {{signerName}}{{signerPositionSuffix}}

      Damit ist die rechtliche Freigabe erteilt. Sie können die Einrichtung Ihrer
      Organisation im Admin-Bereich fortsetzen:

      {{adminUrl}}""";

  static final String DEFAULT_BODY_EN =
      """
      Hello,

      the contract documents for {{tenantName}} have been confirmed.

      Contract version: {{dpaVersion}}
      Confirmed at: {{signedAt}}
      Confirmed by: {{signerName}}{{signerPositionSuffix}}

      The legal approval is now in place. You can continue setting up your
      organisation in the admin panel:

      {{adminUrl}}""";

  static final String SIGNER_BODY_DE =
      """
      Guten Tag,

      Ihre Bestätigung der Vertragsunterlagen wurde registriert.
      Der Trägeradmin wird darüber benachrichtigt und kann nun die Einrichtung
      Ihrer Organisation im Admin-Bereich fortsetzen.

      Organisation: {{tenantName}}
      Vertragsversion: {{dpaVersion}}
      Bestätigt am: {{signedAt}}
      Bestätigt von: {{signerName}}{{signerPositionSuffix}}""";

  static final String SIGNER_BODY_EN =
      """
      Hello,

      Your confirmation of the contract documents has been recorded.
      The organisation administrator will be notified and can now continue
      setting up your organisation in the admin panel.

      Organisation: {{tenantName}}
      Contract version: {{dpaVersion}}
      Confirmed at: {{signedAt}}
      Confirmed by: {{signerName}}{{signerPositionSuffix}}""";

  private static String receiptLanguage(String language) {
    if (isBlank(language)) {
      return FALLBACK_LANGUAGE;
    }
    String code = language.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
    return "en".equals(code) ? "en" : FALLBACK_LANGUAGE;
  }

  private static String signerSubject(String language) {
    return "en".equals(language)
        ? "Confirmation of your contract documents"
        : "Bestätigung Ihrer Vertragsunterlagen";
  }

  private static String signerBody(String language) {
    return "en".equals(language) ? SIGNER_BODY_EN : SIGNER_BODY_DE;
  }

  /**
   * Bounded dispatcher for the PUBLIC hint endpoint, modelled on {@code PasswordResetService}: an
   * anonymous route must not let request floods accumulate work in memory, and here each request
   * would otherwise perform a technical-user login and an upstream read BEFORE the ledger can
   * deduplicate — so arbitrary tenant ids amplify authentication and upstream traffic. Overflow is
   * rejected and shed; the HTTP answer is 202 either way, which keeps the constant response that
   * prevents disclosure while also bounding the work.
   */
  private final ThreadPoolExecutor defaultNoticeExecutor =
      new ThreadPoolExecutor(
          1,
          2,
          30L,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(200),
          runnable -> {
            Thread thread = new Thread(runnable, "dpa-signed-notice-dispatch");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private final ThreadPoolExecutor adminRecoveryExecutor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          runnable -> {
            Thread thread = new Thread(runnable, "dpa-signed-notice-recovery");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  /** Seam: tests substitute a synchronous executor. */
  private Executor noticeExecutor = defaultNoticeExecutor;

  void useExecutor(Executor executor) {
    this.noticeExecutor = executor;
  }

  @PreDestroy
  void shutdownNoticeExecutor() {
    defaultNoticeExecutor.shutdown();
    adminRecoveryExecutor.shutdown();
  }

  private final TenantDpaSignatureReadClient signatureReadClient;
  private final DpaSignedNoticeRepository noticeRepository;
  private final AdminRepository adminRepository;
  private final AccountInviteRepository accountInviteRepository;

  private final IdentityLocaleLookup identityLocaleLookup;

  private final InviteEmailTemplateRepository templateRepository;
  private final InviteMailDispatchService inviteMailDispatchService;
  private final TenantService tenantService;
  private final TransactionTemplate requiresNewTransaction;
  private final DpaSignedNoticeMetrics metrics;
  private final String adminPanelUrl;
  private final Clock clock;

  public DpaSignedNoticeService(
      TenantDpaSignatureReadClient signatureReadClient,
      DpaSignedNoticeRepository noticeRepository,
      AdminRepository adminRepository,
      AccountInviteRepository accountInviteRepository,
      IdentityLocaleLookup identityLocaleLookup,
      InviteEmailTemplateRepository templateRepository,
      InviteMailDispatchService inviteMailDispatchService,
      TenantService tenantService,
      PlatformTransactionManager transactionManager,
      DpaSignedNoticeMetrics metrics,
      AdminPanelUrl adminPanelUrlProvider,
      Clock clock) {
    this.signatureReadClient = signatureReadClient;
    this.noticeRepository = noticeRepository;
    this.adminRepository = adminRepository;
    this.accountInviteRepository = accountInviteRepository;
    this.identityLocaleLookup = identityLocaleLookup;
    this.templateRepository = templateRepository;
    this.inviteMailDispatchService = inviteMailDispatchService;
    this.tenantService = tenantService;
    this.requiresNewTransaction = new TransactionTemplate(transactionManager);
    this.requiresNewTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.metrics = metrics;
    this.adminPanelUrl = adminPanelUrlProvider.value();
    this.clock = clock;
  }

  /**
   * Processes a signature hint for the tenant. Every failure path is silent-by-design (logged): the
   * hint endpoint must not leak whether a tenant, forward or signature exists.
   */
  public void onSignatureHint(Long tenantId) {
    if (tenantId == null) {
      return;
    }
    try {
      noticeExecutor.execute(() -> processHint(tenantId));
    } catch (RuntimeException dispatchFailure) {
      // Every RuntimeException, not only RejectedExecutionException: a saturated queue rejects,
      // but an executor already shut down during deployment throws IllegalStateException, and
      // both must still leave the caller with 202.
      log.warn(
          "DPA signed-notice hint for tenant {} was shed at dispatch ({})",
          tenantId,
          dispatchFailure.getClass().getSimpleName());
    }
  }

  /**
   * The actual work, off the request thread. Everything it can throw — an upstream read, a
   * technical-user login, the locale lookup, rendering, SMTP — is contained here by design: the
   * endpoint's 202 must not depend on each collaborator being individually defensive, because the
   * next collaborator added will not know that rule.
   */
  private void processHint(Long tenantId) {
    // Pooled worker thread: scope it to the hinted tenant and clear it for the next task.
    TenantContext.setCurrentTenant(tenantId);
    try {
      dispatchNotice(tenantId);
    } catch (RuntimeException failure) {
      log.warn(
          "DPA signed-notice for tenant {} could not be processed; it stays eligible for a later"
              + " hint",
          tenantId,
          failure);
    } finally {
      TenantContext.clear();
    }
  }

  private void dispatchNotice(Long tenantId) {
    Optional<DpaSignatureDTO> forwardedSignature = findLatestForwardedSignature(tenantId);
    if (forwardedSignature.isEmpty()) {
      log.debug("DPA signed-notice hint for tenant {} matched no forwarded signature", tenantId);
      return;
    }
    var signature = forwardedSignature.get();
    // Before anything that can bail out (recipient resolution, the ledger claim): the verified
    // signature must reach the invite row either way, because the Admin invite progress board
    // proves its final "Vertrag unterschrieben" phase from the invite's dpa_signed_at
    // (ORISO-Admin#896, epic #725). Idempotent end to end - the repository update only fills a
    // still-null timestamp, so a repeated hint can never regress or overwrite it.
    recordSignatureOnInvites(tenantId, signature);
    // Each role has its own claim and failure boundary, even when both addresses are identical.
    dispatchForRole(tenantId, signature, NoticeRole.ADMIN);
    dispatchForRole(tenantId, signature, NoticeRole.SIGNER);
  }

  private void dispatchForRole(Long tenantId, DpaSignatureDTO signature, NoticeRole role) {
    try {
      Optional<Recipient> recipient =
          role == NoticeRole.ADMIN
              ? resolveRecipient(tenantId, signature)
              : Optional.ofNullable(signature.getSignerEmail())
                  .filter(email -> !isBlank(email))
                  .map(
                      email ->
                          new Recipient(email.trim(), receiptLanguage(signature.getLanguage())));
      if (recipient.isEmpty()) {
        log.warn("DPA {} notice for tenant {} has no resolvable recipient", role, tenantId);
        return;
      }
      var claim = claimNotice(tenantId, signature, recipient.get(), role);
      if (claim.isEmpty()) {
        log.debug("DPA {} notice for tenant {} already claimed", role, tenantId);
        return;
      }
      var notice = claim.get();
      sendNotice(tenantId, signature, recipient.get(), notice, notice.getAdminNextAttemptAt());
    } catch (RuntimeException failure) {
      // Recipient resolution or claim failure must not suppress the other role's notification.
      log.warn("DPA {} notice for tenant {} could not be processed", role, tenantId, failure);
    }
  }

  /**
   * Writes the verified signature timestamp back to the tenant's onboarding invites (own
   * transaction: the surrounding dispatch is deliberately transaction-free, and the stamp must
   * survive a later mail failure). Falls back to the processing time when the upstream timestamp
   * does not parse - a wrong-but-present stamp is better for the board than a phase that never
   * completes.
   */
  private void recordSignatureOnInvites(Long tenantId, DpaSignatureDTO signature) {
    LocalDateTime signedAt =
        Optional.ofNullable(parseDateTime(signature.getSignedAt())).orElseGet(LocalDateTime::now);
    Integer stamped =
        requiresNewTransaction.execute(
            tx ->
                accountInviteRepository.markDpaSigned(
                    tenantId, AccountInviteTargetRole.TENANT_ADMIN, signedAt));
    if (stamped != null && stamped > 0) {
      log.info(
          "DPA signature for tenant {} written back to {} onboarding invite(s)", tenantId, stamped);
    }
  }

  private Stream<DpaSignatureDTO> forwardedSignatures(Long tenantId) {
    return signatureReadClient.readSignatures(tenantId).stream()
        .filter(signature -> STATUS_SIGNED.equalsIgnoreCase(signature.getStatus()))
        .filter(signature -> SOURCE_FORWARDED_EXTERNAL.equalsIgnoreCase(signature.getSource()));
  }

  private Optional<DpaSignatureDTO> findLatestForwardedSignature(Long tenantId) {
    return forwardedSignatures(tenantId)
        .max(
            Comparator.comparing(
                DpaSignatureDTO::getSignedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
  }

  void submitAdminRecovery(Runnable recovery) {
    adminRecoveryExecutor.execute(recovery);
  }

  void retryPendingAdminNotices() {
    long started = System.nanoTime();
    for (long id : noticeRepository.findDueAdminNoticeIds(nowUtc(), ADMIN_RECOVERY_BATCH)) {
      if (System.nanoTime() - started >= ADMIN_RECOVERY_BUDGET.toNanos()) {
        break;
      }
      try {
        var now = nowUtc();
        var reservedUntil = now.plus(ADMIN_RECOVERY_DELAY);
        var reserved =
            requiresNewTransaction.execute(
                tx -> noticeRepository.reserveAdminPreparation(id, now, reservedUntil));
        if (Integer.valueOf(1).equals(reserved)) {
          noticeRepository.findById(id).ifPresent(claim -> retryAdminNotice(claim, reservedUntil));
        }
      } catch (RuntimeException failure) {
        log.warn(
            "DPA administrator notice {} recovery deferred ({})",
            id,
            failure.getClass().getSimpleName());
      }
    }
  }

  private void retryAdminNotice(DpaSignedNotice claim, LocalDateTime reservedUntil) {
    if (claim.getNoticeRole() != NoticeRole.ADMIN
        || claim.getSentAt() != null
        || !reservedUntil.equals(claim.getAdminNextAttemptAt())
        || claim.getAdminRecoverySignatureAt() == null) {
      return;
    }
    var tenantId = claim.getTenantId();
    TenantContext.runIn(
        tenantId,
        () -> {
          var signature =
              forwardedSignatures(tenantId)
                  .filter(row -> Objects.equals(row.getTenantId(), tenantId))
                  .filter(row -> claim.getDpaVersion().equals(dedupKeyFor(row)))
                  .filter(row -> claim.getAdminRecoverySignatureAt().equals(row.getSignedAt()))
                  .limit(2)
                  .toList();
          if (signature.size() != 1) {
            deferMissingAdminProof(claim, reservedUntil);
            return;
          }

          var signed = signature.getFirst();
          var recipient = resolveRecipient(tenantId, signed);
          if (recipient.isEmpty()) {
            deferMissingAdminProof(claim, reservedUntil);
            return;
          }
          if (!claim.getRecipientEmail().equals(recipient.get().email())) {
            // A verified hint is still a tenant-level trigger, not authority to readdress a
            // pending notice. Wizard recipient resolution can point to a different invite.
            deferOrParkRecipientMismatch(claim, reservedUntil);
            return;
          }
          sendNotice(tenantId, signed, recipient.get(), claim, reservedUntil);
        });
  }

  private void deferMissingAdminProof(DpaSignedNotice claim, LocalDateTime reservedUntil) {
    if (transitionAdminPreparation(
        claim.getId(), reservedUntil, now -> now.plus(ADMIN_PROOF_RETRY_DELAY))) {
      log.warn(
          "DPA administrator notice {} deferred because its delivery proof changed or is unavailable",
          claim.getId());
    }
  }

  /**
   * Backs off while a mismatch may still settle (an address change being reverted, two workers
   * reading different states). Once the claim is older than {@link
   * #ADMIN_RECIPIENT_MISMATCH_PARK_AFTER} it is parked: unsent, recipient and signature evidence
   * untouched, no next attempt. The sweep then stops reading upstream for it, and nothing
   * readdresses or resends it automatically. The log carries the notice id only, never an address.
   */
  private void deferOrParkRecipientMismatch(DpaSignedNotice claim, LocalDateTime reservedUntil) {
    var createdAt = claim.getCreateDate();
    // create_date is written with the zone-local wall clock, so it is compared against the same.
    var parkFrom =
        LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
            .minus(ADMIN_RECIPIENT_MISMATCH_PARK_AFTER);
    if (createdAt == null || createdAt.isAfter(parkFrom)) {
      deferMissingAdminProof(claim, reservedUntil);
      return;
    }
    if (transitionAdminPreparation(claim.getId(), reservedUntil, now -> null)) {
      metrics.recordRecipientMismatchParked();
      log.error(
          "DPA administrator notice {} parked: its recorded recipient no longer matches; it stays"
              + " unsent until it is reconciled",
          claim.getId());
    }
  }

  private boolean transitionAdminPreparation(
      long id, LocalDateTime reservedUntil, UnaryOperator<LocalDateTime> nextAttempt) {
    return Boolean.TRUE.equals(
        requiresNewTransaction.execute(
            tx -> {
              var held = noticeRepository.lockAdminPreparation(id, reservedUntil);
              // Check the deadline after acquiring the lock so a blocked writer cannot use a
              // timestamp taken while its reservation was still live.
              var now = nowUtc();
              if (held.isEmpty() || !reservedUntil.isAfter(now)) {
                return false;
              }
              held.get().setAdminNextAttemptAt(nextAttempt.apply(now));
              return true;
            }));
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private Optional<Recipient> resolveRecipient(Long tenantId, DpaSignatureDTO signature) {
    if (!isBlank(signature.getForwardedByUserId())) {
      return adminRepository
          .findById(signature.getForwardedByUserId())
          .map(Admin::getEmail)
          .filter(email -> !isBlank(email))
          .map(email -> new Recipient(email, resolveLanguage(signature.getForwardedByUserId())));
    }
    // Pre-account wizard forward: the onboarding invite that declared the forward is the anchor.
    return accountInviteRepository
        .findFirstByTenantIdAndTargetRoleAndDpaForwardedAtIsNotNullOrderByDpaForwardedAtDesc(
            tenantId, AccountInviteTargetRole.TENANT_ADMIN)
        .map(invite -> invite.getRecipientEmail())
        .filter(email -> !isBlank(email))
        .map(email -> new Recipient(email, FALLBACK_LANGUAGE));
  }

  /** Account language of the forwarding admin; the fallback when it cannot be determined. */
  private String resolveLanguage(String userId) {
    return identityLocaleLookup
        .findLocaleById(userId)
        .filter(locale -> !isBlank(locale))
        .orElse(FALLBACK_LANGUAGE);
  }

  /**
   * What is known about delivery when the failure surfaced. Only SmtpSendException can report an
   * uncertain outcome; everything else fails before the handover, so nothing was sent.
   */
  private DeliveryDisposition dispositionOf(RuntimeException failure) {
    return failure instanceof SmtpSendException smtpFailure
        ? smtpFailure.getDeliveryDisposition()
        : DeliveryDisposition.CONFIRMED_NOT_SENT;
  }

  /** Claims the exactly-once ledger row; empty when another hint already claimed it. */
  private Optional<DpaSignedNotice> claimNotice(
      Long tenantId, DpaSignatureDTO signature, Recipient recipient, NoticeRole role) {
    var dpaVersion = dedupKeyFor(signature);
    var signedAt = signature.getSignedAt();
    boolean recoverableAdmin =
        role == NoticeRole.ADMIN
            && Objects.equals(tenantId, signature.getTenantId())
            && !isBlank(signedAt)
            && signedAt.length() <= 64;
    try {
      return Optional.ofNullable(
          requiresNewTransaction.execute(
              tx ->
                  noticeRepository.save(
                      DpaSignedNotice.builder()
                          .tenantId(tenantId)
                          .dpaVersion(dpaVersion)
                          .noticeRole(role)
                          .recipientEmail(recipient.email())
                          .signedAt(parseDateTime(signedAt))
                          .adminRecoverySignatureAt(recoverableAdmin ? signedAt : null)
                          .adminNextAttemptAt(
                              recoverableAdmin ? nowUtc().plus(ADMIN_RECOVERY_DELAY) : null)
                          .createDate(LocalDateTime.now())
                          .build())));
    } catch (DataIntegrityViolationException exception) {
      return Optional.empty();
    }
  }

  private void sendNotice(
      Long tenantId,
      DpaSignatureDTO signature,
      Recipient recipient,
      DpaSignedNotice claim,
      LocalDateTime reservedUntil) {
    PreparedNotice mail;
    try {
      mail = prepareNotice(tenantId, signature, recipient, claim);
    } catch (RuntimeException failure) {
      if (claim.getAdminRecoverySignatureAt() == null) {
        releaseClaim(claim, tenantId, failure);
      } else {
        log.warn(
            "DPA administrator notice {} remains pending after preparation failed ({})",
            claim.getId(),
            failure.getClass().getSimpleName());
      }
      return;
    }

    // The row changes before I/O and only one contender can cross this boundary. A process
    // interruption after this point is ambiguous and stays outside automatic recovery.
    if (claim.getAdminRecoverySignatureAt() != null) {
      if (!transitionAdminPreparation(claim.getId(), reservedUntil, now -> null)) {
        return;
      }
      claim.setAdminNextAttemptAt(null);
    }

    try {
      // No primary action: the notice carries its link inline and the preview also passes null.
      inviteMailDispatchService.send(
          recipient.email(),
          mail.subject(),
          mail.body(),
          null,
          tenantId,
          recipient.language(),
          InviteMailOrigin.of(tenantId, TenantSystemEmailDelivery.Purpose.DPA_SIGNED_NOTICE));
    } catch (RuntimeException failure) {
      releaseClaim(claim, tenantId, failure);
      return;
    }

    // Past the handoff the mail is out of our hands, so the claim must NEVER be released here:
    // releasing it would let a later hint mail the same administrator a second notice about the
    // same signature, and for a message about a signed contract a duplicate is worse than a
    // missing timestamp. Only the sent_at bookkeeping can be lost, and the claim keeps doing its
    // real job - blocking a second send.
    try {
      requiresNewTransaction.executeWithoutResult(
          tx -> {
            claim.setSentAt(LocalDateTime.now());
            noticeRepository.save(claim);
          });
      metrics.recordSent();
      log.info("DPA signed-notice sent for tenant {}", tenantId);
    } catch (RuntimeException afterDispatch) {
      metrics.recordSent();
      log.error(
          "DPA signed-notice for tenant {} was sent but its sent_at could not be recorded; the"
              + " claim is kept so the notice is not sent twice",
          tenantId,
          afterDispatch);
    }
  }

  private record PreparedNotice(String subject, String body) {}

  private PreparedNotice prepareNotice(
      Long tenantId, DpaSignatureDTO signature, Recipient recipient, DpaSignedNotice claim) {
    var language = recipient.language();
    var signerReceipt = claim.getNoticeRole() == NoticeRole.SIGNER;
    var template =
        signerReceipt ? Optional.<InviteEmailTemplate>empty() : findActiveTemplate(language);
    var placeholders = buildPlaceholders(tenantId, signature, language);
    return new PreparedNotice(
        render(
            template
                .map(InviteEmailTemplate::getSubject)
                .orElse(signerReceipt ? signerSubject(language) : defaultSubject(language)),
            placeholders),
        render(
            template
                .map(InviteEmailTemplate::getBody)
                .orElse(signerReceipt ? signerBody(language) : defaultBody(language)),
            placeholders));
  }

  /**
   * Frees the exactly-once claim so a later hint can retry; never masks the original failure.
   *
   * <p>Released for every disposition, on purpose. On {@link
   * DeliveryDisposition#CONFIRMED_NOT_SENT} — and for every non-SMTP failure, which by definition
   * happened before the handover — this is plain compensation and cannot duplicate anything. On
   * {@link DeliveryDisposition#DELIVERY_UNCERTAIN} the mail may already be on its way, and
   * releasing accepts that a later hint delivers a second notice: the "better twice than never"
   * rule in the class javadoc. The two cases are logged apart so a duplicate is traceable
   * afterwards; whoever changes this behaviour must change that documentation with it.
   */
  private void releaseClaim(DpaSignedNotice claim, Long tenantId, RuntimeException failure) {
    var disposition = dispositionOf(failure);
    try {
      requiresNewTransaction.executeWithoutResult(tx -> noticeRepository.delete(claim));
      metrics.recordClaimReleased(disposition);
      if (disposition == DeliveryDisposition.DELIVERY_UNCERTAIN) {
        log.warn(
            "DPA signed-notice for tenant {}: delivery outcome {} — the mail may already have been"
                + " accepted by SMTP. Releasing the claim on purpose so the notice is not lost; a"
                + " later hint may therefore send a SECOND notice for this signature (accepted"
                + " trade-off, see DpaSignedNoticeService javadoc)",
            tenantId,
            disposition,
            failure);
      } else {
        log.warn(
            "DPA signed-notice for tenant {}: delivery outcome {} — nothing was sent, claim"
                + " released so a later hint can retry",
            tenantId,
            disposition,
            failure);
      }
    } catch (RuntimeException compensationFailure) {
      // The claim is stuck: no notice will ever be sent for this tenant/version, so this line is
      // the only trace it existed. Logged loudly rather than swallowed.
      metrics.recordClaimStuck();
      log.error(
          "DPA signed-notice for tenant {} failed AND its claim could not be released; no notice"
              + " will be sent for this signature",
          tenantId,
          compensationFailure);
    }
  }

  /**
   * Exactly-once key for a signature. Prefers the DPA version, but that field is absent from the
   * provider contract currently deployed on pre-dev, so the signature timestamp is the fallback —
   * it is what the provider actually returns today and it still separates one signature from the
   * next. Only when neither is present do signatures collapse onto a shared key, and that would
   * mean a signature carrying no identifying facts at all.
   */
  private static String dedupKeyFor(DpaSignatureDTO signature) {
    if (!isBlank(signature.getDpaVersion())) {
      return signature.getDpaVersion();
    }
    if (!isBlank(signature.getSignedAt())) {
      return "signedAt:" + signature.getSignedAt();
    }
    return "unknown";
  }

  private Optional<InviteEmailTemplate> findActiveTemplate(String language) {
    // Platform templates only (tenant_id is null): this notice is sent by the platform
    // operator, so a Träger's own template must never be able to take it over
    // (ORISO-Admin#1026, template ownership).
    var templates =
        templateRepository.findByKindAndActiveTrueAndTenantIdIsNullOrderByCreateDateDesc(
            InviteEmailTemplateKind.DPA_SIGNED_NOTICE);
    return templates.stream()
        .filter(template -> language.equalsIgnoreCase(template.getLanguage()))
        .findFirst()
        .or(
            () ->
                templates.stream().filter(template -> template.getLanguage() == null).findFirst());
  }

  private Map<String, String> buildPlaceholders(
      Long tenantId, DpaSignatureDTO signature, String language) {
    var signerPosition = signature.getSignerPosition();
    return Map.of(
        "tenantName",
        resolveTenantName(tenantId, language),
        "dpaVersion",
        formatInMailZone(signature.getDpaVersion(), language),
        "signedAt",
        formatInMailZone(signature.getSignedAt(), language),
        "signerName",
        isBlank(signature.getSignerName()) ? "—" : signature.getSignerName(),
        "signerPosition",
        isBlank(signerPosition) ? "" : signerPosition,
        "signerPositionSuffix",
        isBlank(signerPosition) ? "" : " (" + signerPosition + ")",
        "adminUrl",
        adminPanelUrl);
  }

  private String resolveTenantName(Long tenantId, String language) {
    try {
      var tenant = tenantService.getRestrictedTenantData(tenantId);
      if (tenant != null && !isBlank(tenant.getName())) {
        return tenant.getName();
      }
    } catch (HttpClientErrorException.NotFound exception) {
      // the tenant may still be mid-registration (reserved id) — fall through to the generic name
    }
    return "de".equalsIgnoreCase(language) ? "Ihre Organisation" : "your organisation";
  }

  static String render(String value, Map<String, String> placeholders) {
    if (value == null) {
      return "";
    }
    var rendered = value;
    for (var entry : placeholders.entrySet()) {
      rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return rendered;
  }

  /**
   * The subject an operator sees when no DPA_SIGNED_NOTICE template is active — the same text
   * delivery falls back to. Public so {@link
   * de.caritas.cob.userservice.api.service.accountinvite.InviteEmailPreviewService} previews what
   * is actually sent instead of the generic invite sample.
   */
  public static String defaultSubject(String language) {
    return "de".equalsIgnoreCase(language) ? DEFAULT_SUBJECT_DE : DEFAULT_SUBJECT_EN;
  }

  /** The body counterpart of {@link #defaultSubject(String)}. */
  public static String defaultBody(String language) {
    return "de".equalsIgnoreCase(language) ? DEFAULT_BODY_DE : DEFAULT_BODY_EN;
  }

  private static LocalDateTime parseDateTime(String value) {
    if (isBlank(value)) {
      return null;
    }
    try {
      return LocalDateTime.parse(value);
    } catch (DateTimeParseException exception) {
      return null;
    }
  }

  private static String formatDateTime(String value, String language) {
    var parsed = parseDateTime(value);
    if (parsed == null) {
      return isBlank(value) ? "—" : value;
    }
    var pattern = "de".equalsIgnoreCase(language) ? "dd.MM.yyyy HH:mm 'Uhr'" : "yyyy-MM-dd HH:mm";
    return parsed.format(DateTimeFormatter.ofPattern(pattern));
  }

  /**
   * Version and signedAt are zoneless UTC; the reader gets German wall-clock time, the same the
   * Admin shows since #1064.
   */
  private static String formatInMailZone(String value, String language) {
    var parsed = parseDateTime(value);
    if (parsed == null) {
      return formatDateTime(value, language);
    }
    var local = parsed.atOffset(ZoneOffset.UTC).atZoneSameInstant(MAIL_ZONE).toLocalDateTime();
    return formatDateTime(local.toString(), language);
  }

  private record Recipient(String email, String language) {}
}
