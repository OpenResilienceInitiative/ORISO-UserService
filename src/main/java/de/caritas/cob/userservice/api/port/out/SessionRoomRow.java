package de.caritas.cob.userservice.api.port.out;

/** A case id and its Matrix room: all that is needed to take a counsellor out of the room. */
public record SessionRoomRow(Long sessionId, String roomId) {}
