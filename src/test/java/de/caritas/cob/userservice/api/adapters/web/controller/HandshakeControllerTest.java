package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService.HandshakeItem;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService.InitiateHandshakeRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class HandshakeControllerTest {

  @Mock private HandshakeService handshakeService;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private HandshakeController controller;

  @Test
  void initiate_delegatesWithTheAuthenticatedInitiator() {
    // Business reason: the initiator identity always comes from the token — a client
    // can never initiate a handshake in someone else's name.
    var request = new InitiateHandshakeRequest();
    var item = mock(HandshakeItem.class);
    when(handshakeService.initiate(authenticatedUser, request)).thenReturn(item);

    var response = controller.initiate(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isEqualTo(item);
  }

  @Test
  void confirm_delegatesWithTheAuthenticatedCounterpartAndBothBodyCredentials() {
    var item = mock(HandshakeItem.class);
    when(handshakeService.confirm(authenticatedUser, "hs-1", "pw", "123456")).thenReturn(item);
    var body = new HandshakeController.ConfirmHandshakeRequest();
    body.setPassword("pw");
    body.setOtp("123456");

    var response = controller.confirm("hs-1", body);

    // 202, not 200: the room does not exist yet at this point, so the UI must show provisioning
    // rather than claim an active session.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isEqualTo(item);
  }

  @Test
  void decline_delegatesWithTheAuthenticatedCounterpart() {
    var item = mock(HandshakeItem.class);
    when(handshakeService.decline(authenticatedUser, "hs-1")).thenReturn(item);

    var response = controller.decline("hs-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(item);
  }

  @Test
  void pending_returnsOnlyTheCallersPendingHandshakes() {
    var item = mock(HandshakeItem.class);
    when(handshakeService.pendingForCounterpart(authenticatedUser)).thenReturn(List.of(item));

    var response = controller.pending();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsExactly(item);
  }
}
