package de.caritas.cob.userservice.api.service.calendar;

import java.time.Instant;

/** A time somebody is busy: all that is kept of an imported event. */
public record BusySlot(Instant start, Instant end) {}
