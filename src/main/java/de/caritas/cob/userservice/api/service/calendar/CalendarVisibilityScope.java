package de.caritas.cob.userservice.api.service.calendar;

/**
 * Whose calendar a counsellor may look at: the Träger setting {@code calendar.visibilityScope} of
 * the TenantService (ADR-032). {@link #AGENCY} is the narrowest value and applies whenever the
 * setting is missing or unreadable.
 */
public enum CalendarVisibilityScope {
  /** Colleagues of the same Träger who share at least one counselling centre. */
  AGENCY,
  /** Every colleague of the same Träger. */
  TENANT,
  /** Colleagues of other Träger too, where their Träger opens its calendars as well. */
  PLATFORM
}
