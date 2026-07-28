package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.service.liveevents.LiveEvent;

/** Delivers best-effort live events without exposing a concrete transport to the caller. */
public interface LiveEventGateway {

  void send(LiveEvent event);
}
