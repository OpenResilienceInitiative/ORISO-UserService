package de.caritas.cob.userservice.api.service.accountinvite;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import de.caritas.cob.userservice.api.service.notification.AdminPanelUrl;
import de.caritas.cob.userservice.api.service.notification.DpaSignedNoticeService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders the branded mail for the Admin preview (ORISO-UserService#914).
 *
 * <p>The preview deliberately owns <em>no</em> markup: it assembles the same inputs the send path
 * assembles (placeholder substitution via {@link AccountInviteService#render}, branding + layout
 * via {@link InviteMailDispatchService#renderBrandedMail}) and returns the result. That is the
 * whole point of the issue's "one canonical layout, rendered once" decision — the Admin shows what
 * is actually sent, and no second implementation can drift.
 *
 * <p>Sample values are used for the recipient-specific placeholders, and the accept URL is built by
 * the real {@link InviteAcceptUrlBuilder} with a clearly marked sample token, so the preview shows
 * the true link shape without ever minting a usable invite token.
 */
@Service
@RequiredArgsConstructor
public class InviteEmailPreviewService {

  /** Obvious non-token: a preview must never render something that looks like a live link. */
  static final String SAMPLE_TOKEN = "SAMPLE-PREVIEW-TOKEN";

  static final String SAMPLE_EMAIL = "maren.muster@example.org";
  static final String SAMPLE_FIRST_NAME = "Maren";
  static final String SAMPLE_LAST_NAME = "Muster";
  static final String SAMPLE_SUBJECT = "Ihre Einladung zu ORISO";
  static final String SAMPLE_BODY =
      """
      Hallo {{firstName}} {{lastName}},

      Sie wurden eingeladen, ein Konto auf der ORISO-Plattform einzurichten.
      Bitte schliessen Sie die Einrichtung ueber den folgenden Link ab:

      {{inviteLink}}

      Der Link ist 30 Tage gueltig. Bei Fragen antworten Sie bitte nicht auf diese
      E-Mail, sondern wenden Sie sich an Ihre Ansprechperson.""";

  private final @NonNull InviteEmailTemplateRepository templateRepository;
  private final @NonNull InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  private final @NonNull InviteMailDispatchService inviteMailDispatchService;

  /**
   * The same Admin panel URL the delivered notice carries. Injected as the shared component rather
   * than re-derived here, so a preview can never advertise a different destination than delivery.
   */
  private final @NonNull AdminPanelUrl adminPanelUrl;

  @Transactional(readOnly = true)
  public InviteEmailPreview preview(PreviewCommand command) {
    PreviewCommand safe =
        command == null ? new PreviewCommand(null, null, null, null, null, null) : command;
    InviteEmailTemplate template =
        safe.templateId() == null ? null : findTemplate(safe.templateId());

    InviteEmailTemplateKind kind =
        safe.kind() != null
            ? safe.kind()
            : (template == null ? InviteEmailTemplateKind.TENANT_INVITE : template.getKind());
    String language =
        !isBlank(safe.language())
            ? safe.language()
            : (template == null ? null : template.getLanguage());
    // The last-resort sample has to be the one the DISPATCHER falls back to, per kind. With no
    // active template, DpaSignedNoticeService sends its own signed-notice defaults; falling back to
    // the generic invite sample here showed the operator invitation prose with a literal
    // {{inviteLink}} — a preview of a mail that is never sent.
    boolean signedNotice = kind == InviteEmailTemplateKind.DPA_SIGNED_NOTICE;
    String fallbackSubject =
        signedNotice ? DpaSignedNoticeService.defaultSubject(language) : SAMPLE_SUBJECT;
    String fallbackBody = signedNotice ? DpaSignedNoticeService.defaultBody(language) : SAMPLE_BODY;
    String subject =
        firstNonBlank(
            safe.subject(), template == null ? null : template.getSubject(), fallbackSubject);
    String body =
        firstNonBlank(safe.body(), template == null ? null : template.getBody(), fallbackBody);

    AccountInvite sampleInvite =
        AccountInvite.builder()
            .targetRole(targetRoleFor(kind))
            .tenantId(safe.tenantId())
            .recipientEmail(SAMPLE_EMAIL)
            .firstName(SAMPLE_FIRST_NAME)
            .lastName(SAMPLE_LAST_NAME)
            .build();
    String acceptUrl = inviteAcceptUrlBuilder.buildAcceptUrl(targetRoleFor(kind), SAMPLE_TOKEN);

    // DPA_SIGNED_NOTICE speaks a different placeholder dialect than the invite mails and carries
    // no accept link at all: rendered through the invite renderer an operator would see raw
    // {{tenantName}} / {{signerName}} left standing and a counsellor accept URL that this mail
    // never contains. Sample values keep the preview truthful about what is actually sent.
    //
    // The invite path keeps pre-dev's renderBody, which lifts the {{inviteLink}} token line out of
    // the body because the layout renders that action as a button. The notice has no such token —
    // its link is {{adminUrl}} inline in the prose — so it needs neither the stripping nor a
    // primary action.
    String renderedSubject =
        kind == InviteEmailTemplateKind.DPA_SIGNED_NOTICE
            ? renderSignedNoticeSample(subject)
            : AccountInviteService.render(subject, sampleInvite, acceptUrl);
    String renderedBody =
        kind == InviteEmailTemplateKind.DPA_SIGNED_NOTICE
            ? renderSignedNoticeSample(body)
            : AccountInviteService.renderBody(body, sampleInvite, acceptUrl);

    // the signed notice links to the Admin panel, not to an invite accept route
    String primaryAction = kind == InviteEmailTemplateKind.DPA_SIGNED_NOTICE ? null : acceptUrl;
    BrandedEmail mail =
        inviteMailDispatchService.renderBrandedMail(
            renderedSubject, renderedBody, primaryAction, safe.tenantId(), language);

    return new InviteEmailPreview(
        template == null ? null : template.getId(),
        template == null ? null : template.getName(),
        kind,
        language,
        mail.subject(),
        mail.html(),
        mail.plainText(),
        // primaryAction is already null for DPA_SIGNED_NOTICE: the notice carries no accept
        // route, so the preview must not advertise one either.
        primaryAction);
  }

  /** Sample values for the DPA_SIGNED_NOTICE dialect (see DpaSignedNoticeService placeholders). */
  String renderSignedNoticeSample(String value) {
    if (value == null) {
      return "";
    }
    var samples =
        java.util.Map.of(
            "tenantName", "Träger Nord e.V.",
            "dpaVersion", "01.07.2026 12:00 Uhr",
            "signedAt", "14.08.2026 09:15 Uhr",
            "signerName", SAMPLE_FIRST_NAME + " " + SAMPLE_LAST_NAME,
            "signerPosition", "Geschäftsführung",
            "signerPositionSuffix", " (Geschäftsführung)",
            "adminUrl", adminPanelUrl.value());
    var rendered = value;
    for (var entry : samples.entrySet()) {
      rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return rendered;
  }

  private InviteEmailTemplate findTemplate(Long templateId) {
    return templateRepository
        .findById(templateId)
        .orElseThrow(() -> new NotFoundException("Invite e-mail template not found"));
  }

  /** Mirrors the send path: only tenant invites land on the Admin onboarding route. */
  static AccountInviteTargetRole targetRoleFor(InviteEmailTemplateKind kind) {
    return kind == InviteEmailTemplateKind.TENANT_INVITE
        ? AccountInviteTargetRole.TENANT_ADMIN
        : AccountInviteTargetRole.COUNSELLOR;
  }

  private static String firstNonBlank(String... candidates) {
    for (String candidate : candidates) {
      if (!isBlank(candidate)) {
        return candidate;
      }
    }
    return "";
  }

  /**
   * @param templateId render a stored template, or {@code null} to render {@code subject}/{@code
   *     body} directly (live preview of unsaved editor content)
   * @param tenantId brand the preview with this tenant's theming, or {@code null} for platform
   *     branding
   */
  public record PreviewCommand(
      Long templateId,
      InviteEmailTemplateKind kind,
      String subject,
      String body,
      Long tenantId,
      String language) {

    public PreviewCommand(
        Long templateId, InviteEmailTemplateKind kind, String subject, String body, Long tenantId) {
      this(templateId, kind, subject, body, tenantId, null);
    }
  }

  public record InviteEmailPreview(
      Long templateId,
      String templateName,
      InviteEmailTemplateKind kind,
      String language,
      String subject,
      String html,
      String plainText,
      String sampleAcceptUrl) {}
}
