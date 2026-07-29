package de.caritas.cob.userservice.api.service.accountinvite;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
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

  static final String SAMPLE_EMAIL = "erika.musterfrau@example.org";
  static final String SAMPLE_FIRST_NAME = "Erika";
  static final String SAMPLE_LAST_NAME = "Musterfrau";
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
    String subject =
        firstNonBlank(
            safe.subject(), template == null ? null : template.getSubject(), SAMPLE_SUBJECT);
    String body =
        firstNonBlank(safe.body(), template == null ? null : template.getBody(), SAMPLE_BODY);

    AccountInvite sampleInvite =
        AccountInvite.builder()
            .targetRole(targetRoleFor(kind))
            .tenantId(safe.tenantId())
            .recipientEmail(SAMPLE_EMAIL)
            .firstName(SAMPLE_FIRST_NAME)
            .lastName(SAMPLE_LAST_NAME)
            .build();
    String acceptUrl = inviteAcceptUrlBuilder.buildAcceptUrl(targetRoleFor(kind), SAMPLE_TOKEN);

    String renderedSubject = AccountInviteService.render(subject, sampleInvite, acceptUrl);
    String renderedBody = AccountInviteService.render(body, sampleInvite, acceptUrl);

    BrandedEmail mail =
        inviteMailDispatchService.renderBrandedMail(
            renderedSubject,
            renderedBody,
            acceptUrl,
            safe.tenantId(),
            language,
            inviteMailDispatchService.resolveGlobalEmailThemeColor());

    return new InviteEmailPreview(
        template == null ? null : template.getId(),
        template == null ? null : template.getName(),
        kind,
        language,
        mail.subject(),
        mail.html(),
        mail.plainText(),
        acceptUrl);
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
