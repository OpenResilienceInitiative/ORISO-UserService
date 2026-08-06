package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.generated.api.adapters.web.controller.LiveproxyApi;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility tombstone for the removed LiveService transport.
 *
 * <p>The provider contract requires one deprecation cycle before the route can be removed. No event
 * is forwarded.
 */
@Deprecated(forRemoval = true)
@RestController
public class DeprecatedLiveProxyController implements LiveproxyApi {

  @Override
  public ResponseEntity<Void> sendLiveEvent(String matrixRoomId) {
    return ResponseEntity.status(HttpStatus.GONE).build();
  }
}
