package de.caritas.cob.userservice.api.service.calendar;

import java.time.Instant;

/** A time an advice seeker may book with a counsellor (ORISO-Frontend#1050). */
public record FreeSlot(Instant start, Instant end) {}
