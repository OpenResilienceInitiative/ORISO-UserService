package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DeprecatedLiveProxyControllerTest {

  @Test
  void sendLiveEvent_returnsGoneWithoutForwarding() {
    var controller = new DeprecatedLiveProxyController();

    var response = controller.sendLiveEvent("!legacy-room:matrix.example");

    assertEquals(HttpStatus.GONE, response.getStatusCode());
  }

  @Test
  void compatibilityController_isMarkedForRemoval() {
    Deprecated annotation = DeprecatedLiveProxyController.class.getAnnotation(Deprecated.class);

    assertTrue(annotation.forRemoval());
  }
}
