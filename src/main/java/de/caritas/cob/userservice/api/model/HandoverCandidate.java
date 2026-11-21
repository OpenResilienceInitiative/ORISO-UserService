package de.caritas.cob.userservice.api.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The ordered candidate scan needs these values before it loads a page of sessions. */
@Getter
@RequiredArgsConstructor
public class HandoverCandidate implements SessionRoom {

  private final Long id;
  private final Long agencyId;
  private final Long mainTopicId;
  private final String postcode;
  private final String matrixRoomId;
  private final ConversationType conversationType;
  private final boolean teamSession;
  private final User user;
  private final Consultant consultant;
}
