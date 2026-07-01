package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the self-availability READ endpoint (ADR-007): the indicator must read the authoritative
 * {@link ConsultantActivityRegistry}, not mirror a client flag. The endpoint reports whether the
 * calling consultant is currently within the live-chat availability window.
 */
@ExtendWith(MockitoExtension.class)
class ConsultantLiveAvailabilityControllerIT {

  private static final String AVAILABILITY_PATH = "/conversations/consultants/availability";
  private static final String CONSULTANT_ID = "consultant-1";

  private MockMvc mockMvc;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private ConsultantActivityRegistry consultantActivityRegistry;

  @BeforeEach
  void setUp() {
    var controller =
        new ConsultantLiveAvailabilityController(authenticatedUser, consultantActivityRegistry);
    ReflectionTestUtils.setField(controller, "activeWindowMs", 120_000L);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

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

  @Test
  void getLiveChatAvailability_Should_notRefreshTtl_When_pollingAvailability() throws Exception {
    // Documents the pure-read contract at the controller level.
    // Note: @WebMvcTest does not load ConsultantActivityInterceptor, so this test only verifies
    // the controller itself does not mutate the registry. The interceptor's TTL-skip behavior
    // for GET requests is verified in ConsultantActivityInterceptorTest.
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    when(consultantActivityRegistry.filterActive(anyCollection(), anyLong()))
        .thenReturn(Set.of(CONSULTANT_ID));

    this.mockMvc
        .perform(get(AVAILABILITY_PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true));

    // Registry was queried (filterActive) but never mutated (no markAvailable/refreshIfAvailable)
    verify(consultantActivityRegistry).filterActive(anyCollection(), anyLong());
    verify(consultantActivityRegistry, never()).refreshIfAvailable(any());
    verify(consultantActivityRegistry, never()).markAvailable(any());
  }
}
