package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultDpaSigningEmailDispatchServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-23T08:15:00Z");
  private static final String SIGN_LINK = "https://app.example.org/dpa-sign/single-use-token";
  private static final RenderedEmail MAIL =
      new RenderedEmail("Vertragsunterlagen für Träger Nord", "<html/>", "text");

  @Mock private DpaSigningMailRenderer renderer;
  @Mock private InviteMailDispatchService inviteMailDispatchService;

  private DefaultDpaSigningEmailDispatchService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultDpaSigningEmailDispatchService(
            renderer, inviteMailDispatchService, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void send_rendersWithTheSendMomentAndTheUtcExpiry_andHandsBothPartsToSmtp() {
    when(renderer.render(84L, "Träger Nord", SIGN_LINK, NOW, Instant.parse("2026-10-06T22:43:00Z")))
        .thenReturn(MAIL);

    service.send(
        84L,
        "legal@example.org",
        "Träger Nord",
        SIGN_LINK,
        LocalDateTime.parse("2026-10-06T22:43:00"));

    verify(inviteMailDispatchService).sendRendered("legal@example.org", MAIL);
  }

  /** The onboarding wizard degrades to manual sharing on this exception; it must not be eaten. */
  @Test
  void send_propagatesTheSmtpFailure() {
    when(renderer.render(any(), any(), any(), any(), any())).thenReturn(MAIL);
    SmtpSendException failure =
        new SmtpSendException(SmtpSendException.Category.SMTP_CREDENTIALS_MISSING, "no creds");
    when(inviteMailDispatchService.sendRendered("legal@example.org", MAIL)).thenThrow(failure);

    assertThatThrownBy(
            () ->
                service.send(
                    84L,
                    "legal@example.org",
                    "Träger Nord",
                    SIGN_LINK,
                    LocalDateTime.parse("2026-10-06T22:43:00")))
        .isSameAs(failure);
  }

  @Test
  void preview_returnsTheRenderedSubjectAndHtml_withoutTouchingSmtp() {
    when(renderer.render(any(), any(), any(), any(), any())).thenReturn(MAIL);

    var preview =
        service.preview(
            84L,
            "preview@example.org",
            "Träger Nord",
            SIGN_LINK,
            LocalDateTime.parse("2026-10-06T22:43:00"));

    assertThat(preview).isEqualTo(new DpaSigningEmailPreview(MAIL.subject(), MAIL.html()));
    verifyNoInteractions(inviteMailDispatchService);
  }
}
