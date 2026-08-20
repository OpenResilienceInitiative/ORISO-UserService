package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.DpaSignedNoticeRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/**
 * DPA_SIGNED_NOTICE (ORISO-UserService#1005, epic ORISO-Admin#722): tells the administrator who
 * forwarded the data processing agreement that the signature has landed.
 *
 * <p>Trigger model — untrusted hint, verified facts: TenantService fires an unauthenticated
 * fire-and-forget hint after a public sign-link confirmation. This service then reads the signature
 * audit list back through the authenticated technical-user client and derives everything (signer,
 * version, timestamp, forward origin) from that authoritative answer. A spoofed or repeated hint
 * can never fabricate a notice; a hint for a self-signed or unsigned tenant finds no forwarded
 * signature and dies silently.
 *
 * <p>Exactly-once: a ledger row per (tenant, signed version) is claimed in its own transaction
 * BEFORE sending — of two concurrent hints exactly one wins the unique constraint. When the SMTP
 * handover fails afterwards, the claim is compensated (deleted) so a later hint can retry.
 *
 * <p>Recipient resolution per issue spec: {@code forwardedByUserId} → that admin's account e-mail
 * and account language (identity {@code locale}, fallback {@code de}); a pre-account wizard forward
 * (null forwarder) → the onboarding invite's contact address, fallback language.
 */
@Service
@Slf4j
public class DpaSignedNoticeService {

  static final String SOURCE_FORWARDED_EXTERNAL = "FORWARDED_EXTERNAL";
  static final String STATUS_SIGNED = "SIGNED";
  static final String FALLBACK_LANGUAGE = "de";

  static final String DEFAULT_SUBJECT_DE =
      "Auftragsverarbeitungsvertrag unterzeichnet – {{tenantName}}";
  static final String DEFAULT_SUBJECT_EN = "Data processing agreement signed – {{tenantName}}";

  static final String DEFAULT_BODY_DE =
      """
      Guten Tag,

      der Auftragsverarbeitungsvertrag für {{tenantName}} wurde unterzeichnet.

      Vertragsversion: {{dpaVersion}}
      Unterzeichnet am: {{signedAt}}
      Unterzeichnet von: {{signerName}}{{signerPositionSuffix}}

      Damit ist die rechtliche Freigabe erteilt. Sie können die Einrichtung Ihrer
      Organisation im Admin-Bereich fortsetzen:

      {{adminUrl}}""";

  static final String DEFAULT_BODY_EN =
      """
      Hello,

      the data processing agreement for {{tenantName}} has been signed.

      Contract version: {{dpaVersion}}
      Signed at: {{signedAt}}
      Signed by: {{signerName}}{{signerPositionSuffix}}

      The legal approval is now in place. You can continue setting up your
      organisation in the admin panel:

      {{adminUrl}}""";

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

  /** Seam: tests substitute a synchronous executor. */
  private Executor noticeExecutor = defaultNoticeExecutor;

  void useExecutor(Executor executor) {
    this.noticeExecutor = executor;
  }

  @PreDestroy
  void shutdownNoticeExecutor() {
    defaultNoticeExecutor.shutdown();
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
  private final String adminPanelUrl;

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
      AdminPanelUrl adminPanelUrlProvider) {
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
    this.adminPanelUrl = adminPanelUrlProvider.value();
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
    try {
      dispatchNotice(tenantId);
    } catch (RuntimeException failure) {
      log.warn(
          "DPA signed-notice for tenant {} could not be processed; it stays eligible for a later"
              + " hint",
          tenantId,
          failure);
    }
  }

  private void dispatchNotice(Long tenantId) {
    Optional<DpaSignatureDTO> forwardedSignature = findLatestForwardedSignature(tenantId);
    if (forwardedSignature.isEmpty()) {
      log.debug("DPA signed-notice hint for tenant {} matched no forwarded signature", tenantId);
      return;
    }
    var signature = forwardedSignature.get();
    Optional<Recipient> recipient = resolveRecipient(tenantId, signature);
    if (recipient.isEmpty()) {
      log.warn(
          "DPA signed-notice for tenant {} has no resolvable recipient — no notice sent", tenantId);
      return;
    }
    var claim = claimNotice(tenantId, signature, recipient.get());
    if (claim.isEmpty()) {
      log.debug("DPA signed-notice for tenant {} was already sent — skipping duplicate", tenantId);
      return;
    }
    sendNotice(tenantId, signature, recipient.get(), claim.get());
  }

  private Optional<DpaSignatureDTO> findLatestForwardedSignature(Long tenantId) {
    return signatureReadClient.readSignatures(tenantId).stream()
        .filter(signature -> STATUS_SIGNED.equalsIgnoreCase(signature.getStatus()))
        .filter(signature -> SOURCE_FORWARDED_EXTERNAL.equalsIgnoreCase(signature.getSource()))
        .max(
            Comparator.comparing(
                DpaSignatureDTO::getSignedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
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

  /** Claims the exactly-once ledger row; empty when another hint already claimed it. */
  private Optional<DpaSignedNotice> claimNotice(
      Long tenantId, DpaSignatureDTO signature, Recipient recipient) {
    var dpaVersion = dedupKeyFor(signature);
    try {
      return Optional.ofNullable(
          requiresNewTransaction.execute(
              tx ->
                  noticeRepository.save(
                      DpaSignedNotice.builder()
                          .tenantId(tenantId)
                          .dpaVersion(dpaVersion)
                          .recipientEmail(recipient.email())
                          .signedAt(parseDateTime(signature.getSignedAt()))
                          .createDate(LocalDateTime.now())
                          .build())));
    } catch (DataIntegrityViolationException exception) {
      return Optional.empty();
    }
  }

  private void sendNotice(
      Long tenantId, DpaSignatureDTO signature, Recipient recipient, DpaSignedNotice claim) {
    // Everything from here until the mail is handed over must be compensated on failure. The claim
    // is already committed in its own transaction, and its unique key is what makes the notice
    // exactly-once — so a claim left behind with sent_at still null does not merely lose THIS
    // attempt, it makes every later hint lose the race and the notice is never sent at all.
    // Loading a template, resolving the tenant name and rendering all reach out or can throw.
    try {
      var language = recipient.language();
      var template = findActiveTemplate(language);
      var placeholders = buildPlaceholders(tenantId, signature, language);
      var subject =
          render(
              template.map(InviteEmailTemplate::getSubject).orElse(defaultSubject(language)),
              placeholders);
      var body =
          render(
              template.map(InviteEmailTemplate::getBody).orElse(defaultBody(language)),
              placeholders);
      inviteMailDispatchService.send(
          recipient.email(), subject, body, adminPanelUrl, tenantId, language);
    } catch (RuntimeException beforeDispatch) {
      // Every failure up to the handoff, not only SmtpSendException: a template load, the
      // tenant-name lookup or the rendering can fail too, and a stranded claim silently disables
      // the notice forever.
      releaseClaim(claim, tenantId, beforeDispatch);
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
      log.info("DPA signed-notice sent for tenant {}", tenantId);
    } catch (RuntimeException afterDispatch) {
      log.error(
          "DPA signed-notice for tenant {} was sent but its sent_at could not be recorded; the"
              + " claim is kept so the notice is not sent twice",
          tenantId,
          afterDispatch);
    }
  }

  /** Frees the exactly-once claim so a later hint can retry; never masks the original failure. */
  private void releaseClaim(DpaSignedNotice claim, Long tenantId, RuntimeException failure) {
    try {
      requiresNewTransaction.executeWithoutResult(tx -> noticeRepository.delete(claim));
      log.warn(
          "DPA signed-notice for tenant {} failed before dispatch — claim released for retry",
          tenantId,
          failure);
    } catch (RuntimeException compensationFailure) {
      // The claim is stuck: no notice will ever be sent for this tenant/version, so this line is
      // the only trace it existed. Logged loudly rather than swallowed.
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
    var templates =
        templateRepository.findByKindAndActiveTrueOrderByCreateDateDesc(
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
        formatDateTime(signature.getDpaVersion(), language),
        "signedAt",
        formatDateTime(signature.getSignedAt(), language),
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

  private static String defaultSubject(String language) {
    return "de".equalsIgnoreCase(language) ? DEFAULT_SUBJECT_DE : DEFAULT_SUBJECT_EN;
  }

  private static String defaultBody(String language) {
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

  private record Recipient(String email, String language) {}
}
