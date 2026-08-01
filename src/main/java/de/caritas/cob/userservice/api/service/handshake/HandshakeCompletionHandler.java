package de.caritas.cob.userservice.api.service.handshake;

import de.caritas.cob.userservice.api.model.HandshakeSession;

/**
 * Consumer hook invoked inside the confirm transaction once both parties of a handshake have
 * verified fresh credentials. Implementations own the privileged action a purpose stands for
 * (support-room creation, credential reset, identity grant). A thrown exception rolls the
 * confirmation back — a handshake whose action failed never counts as confirmed.
 */
public interface HandshakeCompletionHandler {

  boolean supports(HandshakePurpose purpose);

  void onConfirmed(HandshakeSession session);
}
