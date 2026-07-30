package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.RoleAuthorizationAuthorityMapper;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.hateoas.client.LinkDiscoverers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiveProxyController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "spring.profiles.active=testing")
class LiveProxyControllerIT {

  private static final String LIVE_EVENT_PATH = "/liveproxy/send";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LiveEventNotificationService liveEventNotificationService;

  @MockitoBean private RoleAuthorizationAuthorityMapper roleAuthorizationAuthorityMapper;

  @MockitoBean private LinkDiscoverers linkDiscoverers;

  @Test
  void sendLiveEvent_Should_returnBadRequest_When_matrixRoomIdIsNotProvided() throws Exception {
    this.mockMvc.perform(post(LIVE_EVENT_PATH)).andExpect(status().isBadRequest());

    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void sendLiveEvent_Should_returnStatusOkAndUseMock_When_matrixRoomIdIsProvided()
      throws Exception {
    this.mockMvc
        .perform(post(LIVE_EVENT_PATH).param("matrixRoomId", "id"))
        .andExpect(status().isOk());

    verify(liveEventNotificationService, times(1)).sendLiveDirectMessageEventToUsers(eq("id"));
  }
}
