package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import java.time.Instant;

/**
 * One thing in a calendar as its owner knows it, before any view decides what to show of it.
 *
 * @param title asker username, counsellor display name, group topic or blocker note
 */
record CalendarEntry(
    String id,
    CalendarEntryType type,
    Instant start,
    Instant end,
    SessionAppointment.Kind kind,
    String title,
    Long sessionId,
    String seriesId) {}
