package de.caritas.cob.userservice.api.service.liveevents;

import java.util.List;
import org.springframework.lang.Nullable;

/** Transport-neutral live event that can be delivered to one or more recipients. */
public record LiveEvent(
    Type type,
    List<String> recipientIds,
    @Nullable FinishConversationPhase finishConversationPhase) {

  public LiveEvent {
    recipientIds = List.copyOf(recipientIds);
  }

  public static LiveEvent directMessage(List<String> recipientIds) {
    return new LiveEvent(Type.DIRECT_MESSAGE, recipientIds, null);
  }

  public static LiveEvent anonymousEnquiryAccepted(List<String> recipientIds) {
    return new LiveEvent(Type.ANONYMOUS_ENQUIRY_ACCEPTED, recipientIds, null);
  }

  public static LiveEvent newAnonymousEnquiry(List<String> recipientIds) {
    return new LiveEvent(Type.NEW_ANONYMOUS_ENQUIRY, recipientIds, null);
  }

  public static LiveEvent anonymousConversationFinished(
      List<String> recipientIds, FinishConversationPhase finishConversationPhase) {
    return new LiveEvent(
        Type.ANONYMOUS_CONVERSATION_FINISHED, recipientIds, finishConversationPhase);
  }

  public enum Type {
    DIRECT_MESSAGE,
    ANONYMOUS_ENQUIRY_ACCEPTED,
    NEW_ANONYMOUS_ENQUIRY,
    ANONYMOUS_CONVERSATION_FINISHED
  }

  public enum FinishConversationPhase {
    NEW,
    IN_PROGRESS
  }
}
