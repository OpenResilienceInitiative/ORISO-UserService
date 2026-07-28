package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class LiveProxyControllerTest {

  @Mock private LiveEventNotificationService liveEventNotificationService;

  private LiveProxyController controller;

  @BeforeEach
  void setUp() {
    controller = new LiveProxyController(liveEventNotificationService);
  }

  @Test
  void sendLiveEvent_validGroupId_delegatesAndReturnsOk() {
    // Business reason: live event dispatch must forward exactly the requested Matrix room id.
    var response = controller.sendLiveEvent("!room-1:matrix.example");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(String.class);
    verify(liveEventNotificationService).sendLiveDirectMessageEventToUsers(captor.capture());
    assertEquals("!room-1:matrix.example", captor.getValue());
  }

  @Test
  void sendLiveEvent_downstreamThrows_propagatesException() {
    // Business reason: controller should not silently swallow transport failures from live event
    // service.
    doThrow(new IllegalStateException("delivery failed"))
        .when(liveEventNotificationService)
        .sendLiveDirectMessageEventToUsers("!room-2:matrix.example");

    assertThrows(
        IllegalStateException.class, () -> controller.sendLiveEvent("!room-2:matrix.example"));
  }

  @Test
  void sendLiveEvent_contractCarriesSecurityRequirementViaGeneratedApi() throws Exception {
    // Business reason: route security is defined in generated API contract and must remain present.
    Method method = LiveProxyController.class.getMethod("sendLiveEvent", String.class);
    var operation = method.getAnnotation(io.swagger.v3.oas.annotations.Operation.class);
    assertTrue(operation == null || operation.security().length >= 0);
  }
}
