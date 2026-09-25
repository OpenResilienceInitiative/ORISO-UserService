package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import lombok.NonNull;
import org.springframework.stereotype.Service;

/**
 * Sends account-invite mails via the platform's global SMTP settings with a strict
 * receipt-after-send contract (TEN-INV-U6, #890): either the SMTP server accepted the message and
 * an {@link InviteMailSendReceipt} is returned, or an {@link SmtpSendException} propagates. There
 * is no silent-failure path. Failures additionally say whether non-delivery is confirmed or the
 * SMTP outcome is uncertain, allowing callers with an existing deduplication claim to retry safely.
 *
 * <p>The platform transport comes only from deployment-owned SMTP settings.
 *
 * <p>Since ORISO-UserService#914 this service is also the single choke point where the frame is
 * applied: callers hand over the <em>authored content</em> and the primary action, never finished
 * markup. Wrapping here (instead of in each caller) is what guarantees that every mail on this path
 * — tenant-admin invite, counsellor invite, resend — carries the frame, and that the Admin preview
 * endpoint and the dispatcher cannot drift apart. The frame itself is the ORISO e-mail design
 * system (see {@link InviteFrameMailRenderer}); the send contract is untouched: receipt after
 * acceptance, {@link SmtpSendException} otherwise.
 */
@Service
public class InviteMailDispatchService {

  private final @NonNull PlatformSmtpSettingsProvider platformSmtpSettings;
  private final @NonNull InviteMailTransport inviteMailTransport;
  private final @NonNull InviteFrameMailRenderer inviteFrameMailRenderer;

  public InviteMailDispatchService(
      @NonNull PlatformSmtpSettingsProvider platformSmtpSettings,
      @NonNull InviteMailTransport inviteMailTransport,
      @NonNull InviteFrameMailRenderer inviteFrameMailRenderer) {
    this.platformSmtpSettings = platformSmtpSettings;
    this.inviteMailTransport = inviteMailTransport;
    this.inviteFrameMailRenderer = inviteFrameMailRenderer;
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
   * Renders the authored content into the canonical ORISO frame and sends it as a genuine multipart
   * mail.
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
    InviteSmtpSettings smtp;
    BrandedEmail mail;
    try {
      smtp = resolveGlobalSmtpSettings();
      mail = renderBrandedMail(subject, bodyContent, primaryActionUrl, tenantId, language);
    } catch (SmtpSendException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
          SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
          "Invite mail could not be prepared before SMTP dispatch",
          exception);
    }

    return transmit(smtp, recipient, subject, mail.html(), mail.plainText());
  }

  /**
   * Sends a mail the caller already rendered from the design system — both MIME parts plus the
   * subject — through the same global SMTP settings and the same strict contract as {@link
   * #send(String, String, String, String, Long, String)}. For mails whose template is not the
   * invite frame, e.g. the DPA signing mail ({@code avv-unterschrift}).
   *
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the global SMTP settings are unavailable/incomplete or the message
   *     could not be handed over to the SMTP server
   */
  public InviteMailSendReceipt sendRendered(String recipient, RenderedEmail mail) {
    InviteSmtpSettings smtp;
    try {
      smtp = resolveGlobalSmtpSettings();
    } catch (SmtpSendException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
          SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
          "Mail could not be prepared before SMTP dispatch",
          exception);
    }
    return transmit(smtp, recipient, mail.subject(), mail.html(), mail.text());
  }

  private InviteMailSendReceipt transmit(
      InviteSmtpSettings smtp, String recipient, String subject, String html, String text) {
    try {
      return inviteMailTransport.send(smtp, recipient, subject, html, text);
    } catch (SmtpSendException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      // A transport implementation that violates the checked contract can still fail after the
      // SMTP server accepted the message. Treat that as ambiguous so callers keep their claim.
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
          SmtpSendException.DeliveryDisposition.DELIVERY_UNCERTAIN,
          "Invite mail transport failed without a confirmed delivery outcome",
          exception);
    }
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
    return inviteFrameMailRenderer.render(
        subject, bodyContent, primaryActionUrl, tenantId, language);
  }

  private InviteSmtpSettings resolveGlobalSmtpSettings() {
    try {
      var settings = platformSmtpSettings.requireConfigured();
      return new InviteSmtpSettings(
          settings.host(),
          settings.port(),
          settings.secure(),
          settings.username(),
          settings.password(),
          settings.from());
    } catch (IllegalStateException exception) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE,
          "Invite mail not sent: " + exception.getMessage());
    }
  }
}
