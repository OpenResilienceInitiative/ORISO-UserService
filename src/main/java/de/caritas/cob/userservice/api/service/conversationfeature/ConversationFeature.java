package de.caritas.cob.userservice.api.service.conversationfeature;

/** A feature switch the server refuses an action for when it is off. */
public enum ConversationFeature {
  /** "Supervision": attaching a colleague to a case (and with it opening the supervision room). */
  SUPERVISION,
  /** "Dateianhänge": putting a file into a conversation. */
  MEDIA_UPLOAD,
  /** "Sprachnachrichten": an audio file becomes a voice message (an {@code m.audio} event). */
  VOICE_MESSAGES
}
