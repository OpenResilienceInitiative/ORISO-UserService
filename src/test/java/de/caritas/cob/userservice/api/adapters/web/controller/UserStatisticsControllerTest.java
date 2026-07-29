package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.statistics.SessionStatisticsService;
import de.caritas.cob.userservice.api.statistics.model.SessionStatisticsResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserStatisticsControllerTest {

  @Mock private SessionStatisticsService sessionStatisticsService;

  private UserStatisticsController controller;

  @BeforeEach
  void setUp() {
    controller = new UserStatisticsController(sessionStatisticsService);
  }

  @Test
  void getSession_matrixRoomIdOnly_returnsOk() {
    // Business reason: statistics endpoint must support lookup by Matrix room id.
    when(sessionStatisticsService.retrieveSession(null, "group-1"))
        .thenReturn(new SessionStatisticsResultDTO().id(10L).matrixRoomId("group-1"));

    var response = controller.getSession(null, "group-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("group-1", response.getBody().getMatrixRoomId());
  }

  @Test
  void getSession_sessionIdOnly_returnsOk() {
    // Business reason: session-id lookup is the primary path for admin statistics retrieval.
    when(sessionStatisticsService.retrieveSession(22L, null))
        .thenReturn(new SessionStatisticsResultDTO().id(22L));

    var response = controller.getSession(22L, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(22L, response.getBody().getId());
  }

  @Test
  void getSession_bothParamsPresent_forwardsBothToService() {
    // Business reason: controller must preserve full query context for service-side precedence
    // logic.
    when(sessionStatisticsService.retrieveSession(33L, "group-33"))
        .thenReturn(new SessionStatisticsResultDTO().id(33L).matrixRoomId("group-33"));

    var response = controller.getSession(33L, "group-33");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var sessionIdCaptor = ArgumentCaptor.forClass(Long.class);
    var groupCaptor = ArgumentCaptor.forClass(String.class);
    verify(sessionStatisticsService)
        .retrieveSession(sessionIdCaptor.capture(), groupCaptor.capture());
    assertEquals(33L, sessionIdCaptor.getValue());
    assertEquals("group-33", groupCaptor.getValue());
  }

  @Test
  void getSession_missingBothParams_throwsBadRequestFromService() {
    // Business reason: incomplete request must be rejected to avoid ambiguous statistics queries.
    when(sessionStatisticsService.retrieveSession(null, null))
        .thenThrow(new BadRequestException("sessionId or matrixRoomId required"));

    assertThrows(BadRequestException.class, () -> controller.getSession(null, null));
  }

  @Test
  void getSession_downstreamException_propagates() {
    // Business reason: downstream failures must propagate for global exception mapping consistency.
    when(sessionStatisticsService.retrieveSession(44L, null))
        .thenThrow(new IllegalStateException("downstream failed"));

    assertThrows(IllegalStateException.class, () -> controller.getSession(44L, null));
  }
}
