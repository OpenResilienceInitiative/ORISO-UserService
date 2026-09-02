package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.notification.DpaSigningEmailDispatchService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DpaForwardEmailServiceTest {

  @Mock private TenantService tenantService;
  @Mock private DpaSigningEmailDispatchService dpaSigningEmailDispatchService;

  private DpaForwardEmailService service;

  @BeforeEach
  void setUp() {
    service =
        new DpaForwardEmailService(
            tenantService, dpaSigningEmailDispatchService, "https://app.oriso-dev.site");
  }

  @Test
  void sendSigningLink_validRequest_deliversAgreementInvitation() {
    when(tenantService.getRestrictedTenantData(84L))
        .thenReturn(new RestrictedTenantDTO().id(84L).name("E2E Full Gate 202607191747"));

    service.sendSigningLink(
        new DpaForwardEmailService.DpaForwardEmailCommand(
            84L,
            "bart.simpson@oriso.org",
            "https://app.oriso-dev.site/dpa-sign/single-use-token",
            LocalDateTime.parse("2026-08-03T13:27:28.243207790")));

    verify(dpaSigningEmailDispatchService)
        .send(
            "bart.simpson@oriso.org",
            "E2E Full Gate 202607191747",
            "https://app.oriso-dev.site/dpa-sign/single-use-token",
            LocalDateTime.parse("2026-08-03T13:27:28.243207790"));
  }

  /**
   * Reproduces the pre-dev break: the TenantService has no {@code app.base.url} configured, so it
   * emits a path-only sign link. The mail service used to answer 400 "signLink is invalid", which
   * the Admin panel renders as "please enter a valid e-mail address".
   */
  @Test
  void sendSigningLink_relativeSignLink_resolvesAgainstConfiguredAppOrigin() {
    when(tenantService.getRestrictedTenantData(84L))
        .thenReturn(new RestrictedTenantDTO().id(84L).name("E2E Full Gate 202607191747"));

    service.sendSigningLink(
        new DpaForwardEmailService.DpaForwardEmailCommand(
            84L,
            "bart.simpson@oriso.org",
            "/dpa-sign/single-use-token",
            LocalDateTime.parse("2026-08-03T13:27:28.243207790")));

    verify(dpaSigningEmailDispatchService)
        .send(
            "bart.simpson@oriso.org",
            "E2E Full Gate 202607191747",
            "https://app.oriso-dev.site/dpa-sign/single-use-token",
            LocalDateTime.parse("2026-08-03T13:27:28.243207790"));
  }

  /**
   * A protocol-relative reference also starts with "/" but carries its own authority. Resolving it
   * must not smuggle a foreign host past the same-origin guard.
   */
  @Test
  void sendSigningLink_protocolRelativeForeignHost_rejectsWithoutSending() {
    assertThatThrownBy(
            () ->
                service.sendSigningLink(
                    new DpaForwardEmailService.DpaForwardEmailCommand(
                        84L,
                        "bart.simpson@oriso.org",
                        "//attacker.example/dpa-sign/stolen-token",
                        LocalDateTime.parse("2026-08-03T13:27:28.243207790"))))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("signLink");

    verifyNoInteractions(tenantService);
    verify(dpaSigningEmailDispatchService, org.mockito.Mockito.never())
        .send(anyString(), anyString(), anyString(), any(LocalDateTime.class));
  }

  /** A relative path outside the signing route stays rejected. */
  @Test
  void sendSigningLink_relativeLinkOutsideSigningRoute_rejectsWithoutSending() {
    assertThatThrownBy(
            () ->
                service.sendSigningLink(
                    new DpaForwardEmailService.DpaForwardEmailCommand(
                        84L,
                        "bart.simpson@oriso.org",
                        "/admin/dashboard",
                        LocalDateTime.parse("2026-08-03T13:27:28.243207790"))))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("signLink");

    verifyNoInteractions(tenantService);
    verify(dpaSigningEmailDispatchService, org.mockito.Mockito.never())
        .send(anyString(), anyString(), anyString(), any(LocalDateTime.class));
  }

  @Test
  void sendSigningLink_foreignOrigin_rejectsWithoutSending() {
    assertThatThrownBy(
            () ->
                service.sendSigningLink(
                    new DpaForwardEmailService.DpaForwardEmailCommand(
                        84L,
                        "bart.simpson@oriso.org",
                        "https://attacker.example/dpa-sign/stolen-token",
                        LocalDateTime.parse("2026-08-03T13:27:28.243207790"))))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("signLink");

    verifyNoInteractions(tenantService);
    verify(dpaSigningEmailDispatchService, org.mockito.Mockito.never())
        .send(anyString(), anyString(), anyString(), any(LocalDateTime.class));
  }

  @Test
  void toAbsoluteSignLink_resolvesAPathOnlyLinkAgainstTheConfiguredAppOrigin() {
    // TenantService omits its base URL on Pre-Dev; the wizard must still get a shareable link.
    assertThat(service.toAbsoluteSignLink("/dpa-sign/single-use-token"))
        .isEqualTo("https://app.oriso-dev.site/dpa-sign/single-use-token");
  }

  @Test
  void toAbsoluteSignLink_rejectsAForeignOrigin() {
    assertThatThrownBy(() -> service.toAbsoluteSignLink("https://evil.example/dpa-sign/token"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class);
  }
}
