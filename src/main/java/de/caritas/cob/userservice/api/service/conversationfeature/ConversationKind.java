package de.caritas.cob.userservice.api.service.conversationfeature;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.session.AnonymousSessionRegistration;
import java.util.function.Predicate;

/**
 * The kinds of conversation an admin's feature switches are set for (anonymous chat, 1:1
 * counselling, group chat). The same split the counselling app uses to pick the switch.
 *
 * <p>There is no kind for the supervision room: the actions this gate guards (adding a supervisor,
 * uploading through the legacy endpoint) never address it. The room is reached by its own Matrix
 * id, and that path is not served by UserService.
 */
public enum ConversationKind {
  ANONYMOUS,
  ONE_ON_ONE,
  GROUP;

  /**
   * The kind of a session. A live chat is anonymous; internal and self-help groups are groups. A
   * session that was stored without a kind is read the way the app reads it: registered as
   * anonymous, postcode 00000 or an {@code Anonymous-} name.
   *
   * <p>The technical session of a members-only group chat carries the dummy postcode 00000 too, so
   * the anonymous rule alone would call an internal chat anonymous. It is asked first, and only for
   * a session without a kind that the anonymous rule would take.
   *
   * @param membersOnlyChatCase whether the session is the technical case of a members-only chat
   */
  public static ConversationKind of(Session session, Predicate<Session> membersOnlyChatCase) {
    ConversationType type = session.getConversationType();
    if (type == ConversationType.LIVE_CHAT) {
      return ANONYMOUS;
    }
    if (type == null && AnonymousSessionRegistration.matches(session)) {
      return membersOnlyChatCase.test(session) ? GROUP : ANONYMOUS;
    }
    if (type == ConversationType.INTERNAL_GROUP || type == ConversationType.SELF_HELP) {
      return GROUP;
    }
    return ONE_ON_ONE;
  }
}
