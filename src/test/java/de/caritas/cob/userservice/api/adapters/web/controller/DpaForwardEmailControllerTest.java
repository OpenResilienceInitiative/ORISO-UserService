package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService.DpaForwardEmailCommand;
import de.caritas.cob.userservice.api.service.notification.DpaSigningEmailPreview;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DpaForwardEmailControllerTest {

  @Mock private DpaForwardEmailService dpaForwardEmailService;
  @Mock private AuthenticatedUser authenticatedUser;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new DpaForwardEmailController(dpaForwardEmailService, authenticatedUser))
            .build();
  }

  @Test
  void previewSigningMail_rejectsOtherTenantWithoutCallingRenderer() {
    when(authenticatedUser.getTenantId()).thenReturn(42L);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    var controller = new DpaForwardEmailController(dpaForwardEmailService, authenticatedUser);
    var request = new DpaForwardEmailController.DpaForwardEmailPreviewRequest();
    request.setTenantId(84L);

    assertThatThrownBy(() -> controller.previewSigningMail(request))
        .isInstanceOf(ForbiddenException.class);

    verify(dpaForwardEmailService, never()).previewSigningMail(84L);
  }

  @Test
  void previewSigningMail_rejectsMissingCallerTenantWithoutCallingRenderer() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    var controller = new DpaForwardEmailController(dpaForwardEmailService, authenticatedUser);
    var request = new DpaForwardEmailController.DpaForwardEmailPreviewRequest();
    request.setTenantId(84L);

    assertThatThrownBy(() -> controller.previewSigningMail(request))
        .isInstanceOf(ForbiddenException.class);

    verify(dpaForwardEmailService, never()).previewSigningMail(84L);
  }

  @Test
  void previewSigningMail_rejectsMissingRequestedTenantEvenForPlatformAdmin() {
    var controller = new DpaForwardEmailController(dpaForwardEmailService, authenticatedUser);
    var request = new DpaForwardEmailController.DpaForwardEmailPreviewRequest();

    assertThatThrownBy(() -> controller.previewSigningMail(request))
        .isInstanceOf(ForbiddenException.class);

    verify(dpaForwardEmailService, never()).previewSigningMail(null);
  }

  @Test
  void previewSigningMail_platformAdminMayPreviewAnotherTenant() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(dpaForwardEmailService.previewSigningMail(84L))
        .thenReturn(new DpaSigningEmailPreview("Vertragsunterlagen", "<p>canonical preview</p>"));
    var controller = new DpaForwardEmailController(dpaForwardEmailService, authenticatedUser);
    var request = new DpaForwardEmailController.DpaForwardEmailPreviewRequest();
    request.setTenantId(84L);

    assertThat(controller.previewSigningMail(request).getStatusCode().value()).isEqualTo(200);

    verify(dpaForwardEmailService).previewSigningMail(84L);
  }

  @Test
  void previewSigningMail_usesOnlyTenantContextAndReturnsCanonicalMail() throws Exception {
    when(authenticatedUser.getTenantId()).thenReturn(84L);
    when(dpaForwardEmailService.previewSigningMail(84L))
        .thenReturn(new DpaSigningEmailPreview("Vertragsunterlagen", "<p>canonical preview</p>"));

    mockMvc
        .perform(
            post("/useradmin/dpa-invites/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":84}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("Vertragsunterlagen"))
        .andExpect(jsonPath("$.html").value("<p>canonical preview</p>"));

    verify(dpaForwardEmailService).previewSigningMail(84L);
  }

  @Test
  void forwardSigningLink_validRequest_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            post("/useradmin/dpa-invites/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId": 84,
                      "recipientEmail": "bart.simpson@oriso.org",
                      "signLink": "https://app.oriso-dev.site/dpa-sign/single-use-token",
                      "expiresAt": "2026-08-03T13:27:28.243207790"
                    }
                    """))
        .andExpect(status().isNoContent());

    ArgumentCaptor<DpaForwardEmailCommand> command =
        ArgumentCaptor.forClass(DpaForwardEmailCommand.class);
    verify(dpaForwardEmailService).sendSigningLink(command.capture());
    assertThat(command.getValue().tenantId()).isEqualTo(84L);
    assertThat(command.getValue().recipientEmail()).isEqualTo("bart.simpson@oriso.org");
    assertThat(command.getValue().signLink())
        .isEqualTo("https://app.oriso-dev.site/dpa-sign/single-use-token");
    assertThat(command.getValue().expiresAt())
        .isEqualTo(LocalDateTime.parse("2026-08-03T13:27:28.243207790"));
  }
}
