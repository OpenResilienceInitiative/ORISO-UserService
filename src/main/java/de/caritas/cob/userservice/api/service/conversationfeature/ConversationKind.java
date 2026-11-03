package de.caritas.cob.userservice.api.service.conversationfeature;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;

/**
 * The four kinds of conversation an admin's feature switches are set for (anonymous chat, 1:1
 * counselling, group chat, supervision room). The same split the counselling app uses to pick the
 * switch.
 */
public enum ConversationKind {
  ANONYMOUS,
  ONE_ON_ONE,
  GROUP,
  SUPERVISION;

  /** The kind of a session; a live chat is anonymous, internal and self-help groups are groups. */
  public static ConversationKind of(Session session) {
    ConversationType type = session.getConversationType();
    if (type == ConversationType.LIVE_CHAT
        || (type == null && session.getRegistrationType() == Session.RegistrationType.ANONYMOUS)) {
      return ANONYMOUS;
    }
    if (type == ConversationType.INTERNAL_GROUP || type == ConversationType.SELF_HELP) {
      return GROUP;
    }
    return ONE_ON_ONE;
  }
}
