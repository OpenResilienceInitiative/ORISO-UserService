package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.RoleAuthorizationAuthorityMapper;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.keycloak.adapters.KeycloakConfigResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.hateoas.client.LinkDiscoverers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the self-availability READ endpoint (ADR-007): the indicator must read the authoritative
 * {@link ConsultantActivityRegistry}, not mirror a client flag. The endpoint reports whether the
 * calling consultant is currently within the live-chat availability window.
 */
@WebMvcTest(ConsultantLiveAvailabilityController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "spring.profiles.active=testing")
class ConsultantLiveAvailabilityControllerIT {

  private static final String AVAILABILITY_PATH = "/conversations/consultants/availability";
  private static final String CONSULTANT_ID = "consultant-1";

  @Autowired private MockMvc mockMvc;

  @MockBean private AuthenticatedUser authenticatedUser;

  @MockBean private ConsultantActivityRegistry consultantActivityRegistry;

  @MockBean private RoleAuthorizationAuthorityMapper roleAuthorizationAuthorityMapper;

  @MockBean private LinkDiscoverers linkDiscoverers;

  @MockBean private KeycloakConfigResolver keycloakConfigResolver;

  @Test
  void getLiveChatAvailability_Should_returnAvailableTrue_When_consultantIsInActiveSet()
      throws Exception {
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    when(consultantActivityRegistry.filterActive(anyCollection(), anyLong()))
        .thenReturn(Set.of(CONSULTANT_ID));

    this.mockMvc
        .perform(get(AVAILABILITY_PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true));
  }

  @Test
  void getLiveChatAvailability_Should_returnAvailableFalse_When_consultantIsNotInActiveSet()
      throws Exception {
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    when(consultantActivityRegistry.filterActive(anyCollection(), anyLong()))
        .thenReturn(Collections.emptySet());

    this.mockMvc
        .perform(get(AVAILABILITY_PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
  }

  @Test
  void getLiveChatAvailability_Should_returnAvailableFalse_And_notQueryRegistry_When_notConsultant()
      throws Exception {
    when(authenticatedUser.isConsultant()).thenReturn(false);

    this.mockMvc
        .perform(get(AVAILABILITY_PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));

    verifyNoInteractions(consultantActivityRegistry);
  }
}
