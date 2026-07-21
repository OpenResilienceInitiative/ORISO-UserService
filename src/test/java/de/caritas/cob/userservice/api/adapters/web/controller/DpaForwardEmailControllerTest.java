package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService.DpaForwardEmailCommand;
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

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DpaForwardEmailController(dpaForwardEmailService))
            .build();
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
