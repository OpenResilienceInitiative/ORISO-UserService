package de.caritas.cob.userservice.api.model;

/** The session properties needed to classify an associated chat's visibility. */
public interface SessionRoom {

  Long getId();

  String getMatrixRoomId();

  ConversationType getConversationType();

  boolean isTeamSession();
}
