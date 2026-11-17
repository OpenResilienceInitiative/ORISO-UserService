package de.caritas.cob.userservice.api.service.calendar;

import java.time.Instant;

/**
 * A time an advice seeker may book with a counsellor (ORISO-Frontend#1050, #2287), and whether a
 * booking of it is confirmed at once or is a request the counsellor confirms.
 */
public record FreeSlot(Instant start, Instant end, BookingMode mode) {

  public FreeSlot(Instant start, Instant end) {
    this(start, end, BookingMode.INSTANT);
  }

  public FreeSlot withMode(BookingMode next) {
    return new FreeSlot(start, end, next);
  }

  public enum BookingMode {
    INSTANT,
    REQUEST
  }
}
