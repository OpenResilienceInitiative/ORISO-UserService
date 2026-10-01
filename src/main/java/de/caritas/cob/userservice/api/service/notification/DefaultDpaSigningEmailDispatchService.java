package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NonNull;
import org.springframework.stereotype.Service;

/**
 * Renders the DPA signing mail from the design system and sends it over the platform's global SMTP
 * settings, like every other mail of the tenant onboarding. It used to be rendered and sent by
 * ConsultingTypeService from hand-written HTML; that endpoint is no longer called.
 */
@Service
public class DefaultDpaSigningEmailDispatchService implements DpaSigningEmailDispatchService {

  private final DpaSigningMailRenderer dpaSigningMailRenderer;
  private final InviteMailDispatchService inviteMailDispatchService;
  private final Clock clock;

  public DefaultDpaSigningEmailDispatchService(
      @NonNull DpaSigningMailRenderer dpaSigningMailRenderer,
      @NonNull InviteMailDispatchService inviteMailDispatchService,
      @NonNull Clock clock) {
    this.dpaSigningMailRenderer = dpaSigningMailRenderer;
    this.inviteMailDispatchService = inviteMailDispatchService;
    this.clock = clock;
  }

  @Override
  public void send(
      Long tenantId,
      String recipientEmail,
      String tenantName,
      String signLink,
      LocalDateTime expiresAt) {
    inviteMailDispatchService.sendRendered(
        recipientEmail, render(tenantId, tenantName, signLink, expiresAt));
  }

  @Override
  public DpaSigningEmailPreview preview(
      Long tenantId,
      String recipientEmail,
      String tenantName,
      String signLink,
      LocalDateTime expiresAt) {
    RenderedEmail mail = render(tenantId, tenantName, signLink, expiresAt);
    return new DpaSigningEmailPreview(mail.subject(), mail.html());
  }

  // "Provided at" is the moment the link goes out; the mail is sent when it is provided.
  private RenderedEmail render(
      Long tenantId, String tenantName, String signLink, LocalDateTime expiresAt) {
    return dpaSigningMailRenderer.render(
        tenantId, tenantName, signLink, clock.instant(), expiresAt.toInstant(ZoneOffset.UTC));
  }
}
