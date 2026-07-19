package de.caritas.cob.userservice.api.service.support;

import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.service.handshake.HandshakeCompletionHandler;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * First real handshake consumer (ADR-018 §2): a confirmed SUPPORT_ACCESS handshake creates the
 * fresh encrypted 1:1 support room. Runs inside the confirm transaction — if room creation fails,
 * the handshake does not count as confirmed.
 */
@Component
@RequiredArgsConstructor
public class SupportAccessCompletionHandler implements HandshakeCompletionHandler {

  private final @NonNull SupportRoomService supportRoomService;

  @Override
  public boolean supports(HandshakePurpose purpose) {
    return purpose == HandshakePurpose.SUPPORT_ACCESS;
  }

  @Override
  public void onConfirmed(HandshakeSession session) {
    supportRoomService.createForConfirmedHandshake(session);
  }
}
