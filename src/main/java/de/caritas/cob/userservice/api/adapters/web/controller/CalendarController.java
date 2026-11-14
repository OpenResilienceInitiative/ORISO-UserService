package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.BlockerCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.BlockerTimeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarColleaguesDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.service.calendar.CalendarBlockerService;
import de.caritas.cob.userservice.api.service.calendar.CalendarService;
import de.caritas.cob.userservice.generated.api.calendar.controller.UsersApi;
import io.swagger.annotations.Api;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The counsellor calendar (ADR-032, ORISO-UserService#1000). */
@RestController
@RequiredArgsConstructor
@Api(tags = "calendar-controller")
public class CalendarController implements UsersApi {

  private final CalendarService calendar;
  private final CalendarBlockerService blockers;

  @Override
  public ResponseEntity<List<CalendarEntryDTO>> getCalendarEntries(
      OffsetDateTime from, OffsetDateTime to) {
    return ResponseEntity.ok(calendar.ownEntries(from, to));
  }

  @Override
  public ResponseEntity<CalendarColleaguesDTO> getCalendarColleagues(String search) {
    return ResponseEntity.ok(calendar.colleagues(search));
  }

  @Override
  public ResponseEntity<List<CalendarEntryDTO>> getCalendarColleagueEntries(
      String consultantId, OffsetDateTime from, OffsetDateTime to) {
    return ResponseEntity.ok(calendar.colleagueEntries(consultantId, from, to));
  }

  @Override
  public ResponseEntity<List<CalendarEntryDTO>> createCalendarBlockers(BlockerCreateDTO request) {
    return new ResponseEntity<>(blockers.create(request), HttpStatus.CREATED);
  }

  @Override
  public ResponseEntity<CalendarEntryDTO> moveCalendarBlocker(UUID blockerId, BlockerTimeDTO time) {
    return ResponseEntity.ok(blockers.move(blockerId, time));
  }

  @Override
  public ResponseEntity<Void> deleteCalendarBlocker(UUID blockerId) {
    blockers.delete(blockerId);
    return ResponseEntity.noContent().build();
  }
}
