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
 * @param meeting set on an appointment with a colleague (ORISO-Frontend#2305), else null
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
    boolean requested,
    Meeting meeting) {

  /**
   * The other side of an appointment between two counsellors, seen from the calendar's owner.
   *
   * @param confirmed false while the invited counsellor has not answered
   * @param invited true when the calendar's owner was invited (and may answer)
   */
  record Meeting(
      String colleagueId,
      String colleagueName,
      boolean confirmed,
      boolean invited,
      String matrixRoomId,
      Long chatId) {}

  CalendarEntry(
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
    this(id, type, start, end, kind, title, sessionId, seriesId, allDay, requested, null);
  }

  CalendarEntry(
      String id,
      CalendarEntryType type,
      Instant start,
      Instant end,
      SessionAppointment.Kind kind,
      String title,
      Long sessionId,
      String seriesId,
      boolean allDay,
      Meeting meeting) {
    this(id, type, start, end, kind, title, sessionId, seriesId, allDay, false, meeting);
  }

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
    this(id, type, start, end, kind, title, sessionId, seriesId, allDay, false, null);
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
    this(id, type, start, end, kind, title, sessionId, seriesId, false, false, null);
  }
}
