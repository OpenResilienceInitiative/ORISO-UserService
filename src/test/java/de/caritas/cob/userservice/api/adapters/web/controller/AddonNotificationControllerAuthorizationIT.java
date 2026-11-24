package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.NOTIFICATIONS_TECHNICAL;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AddonNotificationRequestDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.service.notification.AddonNotificationService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class AddonNotificationControllerAuthorizationIT {

  private static final String ENDPOINT = "/users/event-notifications/addon";
  private static final String BODY =
      """
      {"userId":"8d3c3c2e-4a8e-4b8a-9f43-6c0a0c7d1f10","tenantId":1,
       "addon":"digisucht-tools","appId":"goals","slot":"asker.navigation",
       "sessionId":null,"important":true,
       "title":{"de":"Neues Tool freigegeben","en":"New tool released"},
       "text":{"de":"Ihre Beraterin hat ein Tool freigegeben."}}
      """;

  @Autowired private MockMvc mvc;

  @MockitoBean private AddonNotificationService addonNotificationService;

  @Test
  void technicalServiceTokenCreatesTheNotification() throws Exception {
    when(addonNotificationService.create(any())).thenReturn(42L);

    mvc.perform(request().with(token(TECHNICAL_DEFAULT, "technical")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(42));

    var captor = ArgumentCaptor.forClass(AddonNotificationRequestDTO.class);
    verify(addonNotificationService).create(captor.capture());
    var request = captor.getValue();
    assertThat(request.addon()).isEqualTo("digisucht-tools");
    assertThat(request.slot()).isEqualTo("asker.navigation");
    assertThat(request.important()).isTrue();
    assertThat(request.title())
        .containsEntry("de", "Neues Tool freigegeben")
        .containsEntry("en", "New tool released");
  }

  @Test
  void validationFailuresAndUnknownRecipientsKeepTheirStatus() throws Exception {
    when(addonNotificationService.create(any()))
        .thenThrow(new BadRequestException("title.de is required"))
        .thenThrow(new NotFoundException("Recipient not found"));

    mvc.perform(request().with(token(TECHNICAL_DEFAULT, "technical")))
        .andExpect(status().isBadRequest());
    mvc.perform(request().with(token(TECHNICAL_DEFAULT, "technical")))
        .andExpect(status().isNotFound());
  }

  @Test
  void machineClientNeedsNoCsrfPair() throws Exception {
    when(addonNotificationService.create(any())).thenReturn(7L);

    mvc.perform(
            post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .with(token(TECHNICAL_DEFAULT, "technical")))
        .andExpect(status().isCreated());
  }

  @Test
  void requestWithoutTokenIsUnauthorized() throws Exception {
    mvc.perform(request()).andExpect(status().isUnauthorized());

    verifyNoInteractions(addonNotificationService);
  }

  @Test
  void tokensWithoutTheTechnicalRoleAreForbidden() throws Exception {
    for (var token :
        List.of(
            token(USER_DEFAULT, "user"),
            token(CONSULTANT_DEFAULT, "consultant"),
            token(USER_ADMIN, "user-admin"),
            token(NOTIFICATIONS_TECHNICAL, "notifications-technical"))) {
      mvc.perform(request().with(token)).andExpect(status().isForbidden());
    }

    verifyNoInteractions(addonNotificationService);
  }

  private static MockHttpServletRequestBuilder request() {
    return post(ENDPOINT)
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY)
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }

  private static JwtRequestPostProcessor token(String authority, String realmRole) {
    return jwt()
        .authorities(new SimpleGrantedAuthority(authority))
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of(realmRole)))
                    .claim("tenantId", 1));
  }
}
