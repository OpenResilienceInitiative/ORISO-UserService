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
  private final String askerUsername;
  private final String consultantUsername;
  private final String consultantDisplayName;
  private final String consultantInternalDisplayName;
  private final String consultantFirstName;
  private final String consultantLastName;

  public String getConsultantInternalDisplayNameOrRealName() {
    return Consultant.resolveInternalDisplayNameOrRealName(
        consultantInternalDisplayName, consultantFirstName, consultantLastName);
  }
}
