package de.caritas.cob.userservice.api.service.conversationfeature;

/** A feature switch the server refuses an action for when it is off. */
public enum ConversationFeature {
  /** "Supervision": attaching a colleague to a case (and with it opening the supervision room). */
  SUPERVISION,
  /** "Dateianhänge": putting a file into a conversation. */
  MEDIA_UPLOAD,
  /** "Sprachnachrichten": an audio file becomes a voice message (an {@code m.audio} event). */
  VOICE_MESSAGES,
  /**
   * "Team-Chat": the team-only side room on an open enquiry (ADR-016, ORISO-Frontend#2491). A
   * master switch only; it has no switch per kind of conversation.
   */
  TEAM_DISCUSSION,
  /**
   * "Team-Chat nach Annahme": the team chat continues after the enquiry is accepted instead of
   * being archived (ORISO-Frontend#2491). Unlike every other switch, <b>unset is off</b>: archiving
   * at accept is the behaviour ADR-016 started with.
   */
  TEAM_DISCUSSION_AFTER_ACCEPT
}
