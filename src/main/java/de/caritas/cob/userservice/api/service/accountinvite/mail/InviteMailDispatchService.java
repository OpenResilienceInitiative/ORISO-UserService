package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailClient;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService.Mode;
import java.time.Instant;
import java.util.UUID;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sends account-invite and DPA mails with a strict receipt-after-send contract (TEN-INV-U6, #890):
 * either the SMTP server accepted the message and an {@link InviteMailSendReceipt} is returned, or
 * an {@link SmtpSendException} propagates. There is no silent-failure path. Failures additionally
 * say whether non-delivery is confirmed or the SMTP outcome is uncertain, allowing callers with an
 * existing deduplication claim to retry safely.
 *
 * <p>The platform transport comes only from Admin Settings through the technical identity. A Träger
 * whose settings select its own mail server (smtpMode OWN) sends through TenantService's relay
 * instead, so the SMTP password never leaves TenantService (#1251).
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
@Slf4j
public class InviteMailDispatchService {

  private final @NonNull PlatformSmtpSettingsProvider platformSmtpSettings;
  private final @NonNull InviteMailTransport inviteMailTransport;
  private final @NonNull InviteFrameMailRenderer inviteFrameMailRenderer;
  private final @NonNull TenantSystemEmailRouteService tenantRoutes;
  private final @NonNull TenantSystemEmailClient tenantRelay;

  public InviteMailDispatchService(
      @NonNull PlatformSmtpSettingsProvider platformSmtpSettings,
      @NonNull InviteMailTransport inviteMailTransport,
      @NonNull InviteFrameMailRenderer inviteFrameMailRenderer,
      @NonNull TenantSystemEmailRouteService tenantRoutes,
      @NonNull TenantSystemEmailClient tenantRelay) {
    this.platformSmtpSettings = platformSmtpSettings;
    this.inviteMailTransport = inviteMailTransport;
    this.inviteFrameMailRenderer = inviteFrameMailRenderer;
    this.tenantRoutes = tenantRoutes;
    this.tenantRelay = tenantRelay;
  }

  /**
   * Renders the authored content into the canonical ORISO frame and sends it as a genuine multipart
   * mail through the transport of {@code origin}.
   *
   * @param bodyContent the authored template body — plain text or simple markup, sanitised by the
   *     layout renderer; callers must not pass finished HTML
   * @param primaryActionUrl the invite/onboarding link rendered as a button plus a visible
   *     copy-paste fallback line, or {@code null} for mails without an action
   * @param tenantId tenant whose theming should brand the mail, or {@code null} for platform
   *     branding (the normal case for a tenant-admin invite: the tenant does not exist yet)
   * @param language BCP-47 tag selecting the frame wording; {@code null} means German
   * @param origin whose mail server sends the mail (#1251)
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the transport is unavailable/incomplete or the message could not
   *     be handed over to the SMTP server
   */
  public InviteMailSendReceipt send(
      String recipient,
      String subject,
      String bodyContent,
      String primaryActionUrl,
      Long tenantId,
      String language,
      @NonNull InviteMailOrigin origin) {
    Mode mode = resolveMode(origin);
    InviteSmtpSettings smtp;
    BrandedEmail mail;
    try {
      smtp = mode == Mode.OWN ? null : resolveGlobalSmtpSettings();
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
    if (mode == Mode.OWN) {
      return relay(origin, recipient, new RenderedEmail(subject, mail.html(), mail.plainText()));
    }
    return transmit(smtp, recipient, subject, mail.html(), mail.plainText());
  }

  /**
   * Sends a mail the caller already rendered from the design system — both MIME parts plus the
   * subject — through the transport of {@code origin} and the same strict contract as {@link
   * #send(String, String, String, String, Long, String, InviteMailOrigin)}. For mails whose
   * template is not the invite frame, e.g. the DPA signing mail ({@code avv-unterschrift}).
   *
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the transport is unavailable/incomplete or the message could not
   *     be handed over to the SMTP server
   */
  public InviteMailSendReceipt sendRendered(
      String recipient, RenderedEmail mail, @NonNull InviteMailOrigin origin) {
    if (resolveMode(origin) == Mode.OWN) {
      return relay(origin, recipient, mail);
    }
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

  // Never fall back to the platform server: a Träger's From domain would fail SPF/DMARC there.
  private Mode resolveMode(InviteMailOrigin origin) {
    try {
      return tenantRoutes.resolveTransport(origin.tenantId()).mode();
    } catch (TenantSystemEmailRouteService.ConfigurationException incomplete) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE,
          SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
          "Tenant mail not sent: " + incomplete.getMessage(),
          incomplete);
    } catch (RuntimeException unreadable) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE,
          SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
          "Tenant mail transport could not be resolved",
          unreadable);
    }
  }

  private InviteMailSendReceipt relay(
      InviteMailOrigin origin, String recipient, RenderedEmail mail) {
    UUID correlationId = UUID.randomUUID();
    tenantRelay.deliverStrict(
        origin.tenantId(), origin.purpose().name(), recipient, mail, correlationId);
    log.info(
        "{} mail for tenant {} accepted by its own SMTP server (correlation {})",
        origin.purpose(),
        origin.tenantId(),
        correlationId);
    return new InviteMailSendReceipt(recipient, Instant.now());
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
