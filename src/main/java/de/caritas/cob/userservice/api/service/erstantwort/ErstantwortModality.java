package de.caritas.cob.userservice.api.service.erstantwort;

/**
 * The conversation modalities an Erstantwort can be spoken into.
 *
 * <p>Deliberately a small enum of its own rather than a reuse of the session's own type: the
 * modality assignment of a Baustein is a product decision (ADR-018 §5), and some of it follows from
 * the domain rather than from the schema — Live Chat has neither teams nor case handover, so "who
 * reads along" has nothing to render there, and it is synchronous, so a reply deadline would be a
 * promise the system cannot keep.
 *
 * <p>{@code INTERNAL_GROUP} is absent on purpose. It is a counsellor-side room and never receives
 * an Erstantwort at all.
 */
public enum ErstantwortModality {
  AGENCY_COUNSELLING,
  LIVE_CHAT,
  SELF_HELP;

  /** Whether this modality has an enquiry, and therefore a promised time to a first reply. */
  public boolean isAsynchronous() {
    return this != LIVE_CHAT;
  }
}
