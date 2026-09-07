package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.email.GlobalSmtpSettingsResolver;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmailLayoutRenderer;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmailRequest;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sends account-invite mails via the platform's global SMTP settings with a strict
 * receipt-after-send contract (TEN-INV-U6, #890): either the SMTP server accepted the message and
 * an {@link InviteMailSendReceipt} is returned, or an {@link SmtpSendException} propagates. There
 * is no silent-failure path — a caller that does not receive a receipt must never persist SENT.
 *
 * <p>Settings resolution mirrors {@link
 * de.caritas.cob.userservice.api.service.auth.PasswordResetService}: connection settings come from
 * the public ConsultingTypeService {@code /settings} payload (which deliberately omits credentials
 * since CTS-C01), the credentials from the operator-provided {@code smtp.user}/{@code
 * smtp.password} properties, falling back to the super-admin-guarded credentials endpoint when the
 * request context allows it.
 *
 * <p>Since ORISO-UserService#914 this service is also the single choke point where the branded
 * layout is applied: callers hand over the <em>authored content</em> and the primary action, never
 * finished markup. Wrapping here (instead of in each caller) is what guarantees that every mail on
 * this path — tenant-admin invite, counsellor invite, resend — is branded, and that the Admin
 * preview endpoint and the dispatcher cannot drift apart. The send contract itself is untouched:
 * receipt after acceptance, {@link SmtpSendException} otherwise.
 */
@Slf4j
@Service
public class InviteMailDispatchService {

  private final GlobalSmtpSettingsResolver smtpSettingsResolver;
  private final @NonNull InviteMailTransport inviteMailTransport;
  private final @NonNull EmailBrandingResolver emailBrandingResolver;
  private final @NonNull BrandedEmailLayoutRenderer brandedEmailLayoutRenderer;

  public InviteMailDispatchService(
      @NonNull GlobalSmtpSettingsResolver smtpSettingsResolver,
      @NonNull InviteMailTransport inviteMailTransport,
      @NonNull EmailBrandingResolver emailBrandingResolver,
      @NonNull BrandedEmailLayoutRenderer brandedEmailLayoutRenderer) {
    this.smtpSettingsResolver = smtpSettingsResolver;
    this.inviteMailTransport = inviteMailTransport;
    this.emailBrandingResolver = emailBrandingResolver;
    this.brandedEmailLayoutRenderer = brandedEmailLayoutRenderer;
  }

  /**
   * Sends the given mail content without a call-to-action button and without tenant branding.
   *
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the global SMTP settings are unavailable/incomplete or the message
   *     could not be handed over to the SMTP server
   */
  public InviteMailSendReceipt send(String recipient, String subject, String bodyContent) {
    return send(recipient, subject, bodyContent, null, null, null);
  }

  /**
   * Renders the authored content into the canonical branded layout and sends it as a genuine
   * multipart mail.
   *
   * @param bodyContent the authored template body — plain text or simple markup, sanitised by the
   *     layout renderer; callers must not pass finished HTML
   * @param primaryActionUrl the invite/onboarding link rendered as a button plus a visible
   *     copy-paste fallback line, or {@code null} for mails without an action
   * @param tenantId tenant whose theming should brand the mail, or {@code null} for platform
   *     branding (the normal case for a tenant-admin invite: the tenant does not exist yet)
   * @param language BCP-47 tag selecting the frame wording; {@code null} means German
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the global SMTP settings are unavailable/incomplete or the message
   *     could not be handed over to the SMTP server
   */
  public InviteMailSendReceipt send(
      String recipient,
      String subject,
      String bodyContent,
      String primaryActionUrl,
      Long tenantId,
      String language) {
    InviteSmtpSettings smtp = smtpSettingsResolver.resolve();
    BrandedEmail mail =
        renderBrandedMail(subject, bodyContent, primaryActionUrl, tenantId, language);
    return inviteMailTransport.send(smtp, recipient, subject, mail.html(), mail.plainText());
  }

  /**
   * Renders exactly what {@link #send} would transmit. The Admin preview endpoint calls this, so a
   * preview can never show markup the dispatcher would not produce.
   *
   * <p>The palette comes from the tenant theming alone (#914, final decision). The SMTP settings
   * payload also carries {@code globalSmtpEmailThemeColor} ("E-Mail Designfarbe"), but that value
   * is deliberately not read: a transport setting is not a design token, and mixing it in produced
   * mails in a colour the product never uses.
   */
  public BrandedEmail renderBrandedMail(
      String subject, String bodyContent, String primaryActionUrl, Long tenantId, String language) {
    EmailBranding branding = emailBrandingResolver.resolve(tenantId);
    return brandedEmailLayoutRenderer.render(
        branding, new BrandedEmailRequest(subject, bodyContent, primaryActionUrl, null, language));
  }
}
