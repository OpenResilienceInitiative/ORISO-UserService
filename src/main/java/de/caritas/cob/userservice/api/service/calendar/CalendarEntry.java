package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import java.time.Instant;

/**
 * One thing in a calendar as its owner knows it, before any view decides what to show of it.
 *
 * @param title asker username, counsellor display name, group topic or blocker note
 * @param allDay a blocker entered as whole days (ORISO-UserService#1663); false for all else
 * @param requested an advice seeker's appointment request still waiting for the counsellor
 *     (ORISO-Frontend#2287): shown provisionally, never moved
 */
record CalendarEntry(
    String id,
    CalendarEntryType type,
    Instant start,
    Instant end,
    SessionAppointment.Kind kind,
    String title,
    Long sessionId,
    String seriesId,
    boolean allDay,
    boolean requested) {

  CalendarEntry(
      String id,
      CalendarEntryType type,
      Instant start,
      Instant end,
      SessionAppointment.Kind kind,
      String title,
      Long sessionId,
      String seriesId,
      boolean allDay) {
    this(id, type, start, end, kind, title, sessionId, seriesId, allDay, false);
  }

  CalendarEntry(
      String id,
      CalendarEntryType type,
      Instant start,
      Instant end,
      SessionAppointment.Kind kind,
      String title,
      Long sessionId,
      String seriesId) {
    this(id, type, start, end, kind, title, sessionId, seriesId, false, false);
  }
}
