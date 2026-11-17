package de.caritas.cob.userservice.api.adapters.web.controller;

import static java.nio.charset.StandardCharsets.UTF_8;

import de.caritas.cob.userservice.api.adapters.web.dto.BlockerCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.BlockerTimeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarColleaguesDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarConsultationHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarImportDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarMoveProposalCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarMoveProposalDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarMoveProposalsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarSyncDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingDayExceptionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingDayExceptionWindowsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO;
import de.caritas.cob.userservice.api.service.calendar.CalendarBlockerService;
import de.caritas.cob.userservice.api.service.calendar.CalendarConsultationHoursService;
import de.caritas.cob.userservice.api.service.calendar.CalendarFeedService;
import de.caritas.cob.userservice.api.service.calendar.CalendarMoveProposalService;
import de.caritas.cob.userservice.api.service.calendar.CalendarService;
import de.caritas.cob.userservice.api.service.calendar.CalendarSyncService;
import de.caritas.cob.userservice.api.service.calendar.CalendarWorkingHoursService;
import de.caritas.cob.userservice.generated.api.calendar.controller.UsersApi;
import io.swagger.annotations.Api;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The counsellor calendar (ADR-032, ORISO-UserService#1000 and #1645, addenda v1.1 and v1.2, move
 * proposals v1).
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "calendar-controller")
public class CalendarController implements UsersApi {

  private static final MediaType TEXT_CALENDAR = new MediaType("text", "calendar", UTF_8);

  private final CalendarService calendar;
  private final CalendarBlockerService blockers;
  private final CalendarSyncService sync;
  private final CalendarFeedService feed;
  private final CalendarWorkingHoursService workingHours;
  private final CalendarConsultationHoursService consultationHours;
  private final CalendarMoveProposalService proposals;

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

  @Override
  public ResponseEntity<CalendarSyncDTO> getCalendarSync() {
    return ResponseEntity.ok(sync.status());
  }

  @Override
  public ResponseEntity<CalendarSyncDTO> resetCalendarFeed() {
    return ResponseEntity.ok(sync.resetFeed());
  }

  @Override
  public ResponseEntity<CalendarSyncDTO> setCalendarImport(CalendarImportDTO request) {
    return ResponseEntity.ok(sync.configureImport(request));
  }

  @Override
  public ResponseEntity<CalendarSyncDTO> removeCalendarImport() {
    return ResponseEntity.ok(sync.removeImport());
  }

  @Override
  public ResponseEntity<CalendarConsultationHoursDTO> getCalendarConsultationHours() {
    return ResponseEntity.ok(consultationHours.consultationHours());
  }

  @Override
  public ResponseEntity<CalendarConsultationHoursDTO> setCalendarConsultationHours(
      CalendarConsultationHoursDTO request) {
    return ResponseEntity.ok(consultationHours.replace(request));
  }

  @Override
  public ResponseEntity<CalendarWorkingHoursDTO> getCalendarWorkingHours() {
    return ResponseEntity.ok(workingHours.workingHours());
  }

  @Override
  public ResponseEntity<CalendarWorkingHoursDTO> setCalendarWorkingHours(
      CalendarWorkingHoursDTO request) {
    return ResponseEntity.ok(workingHours.replace(request));
  }

  @Override
  public ResponseEntity<CalendarWorkingDayExceptionDTO> setCalendarWorkingDayException(
      LocalDate date, CalendarWorkingDayExceptionWindowsDTO request) {
    return ResponseEntity.ok(workingHours.replaceException(date, request));
  }

  @Override
  public ResponseEntity<Void> deleteCalendarWorkingDayException(LocalDate date) {
    workingHours.removeException(date);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<CalendarMoveProposalsDTO> getCalendarMoveProposals() {
    return ResponseEntity.ok(proposals.open());
  }

  @Override
  public ResponseEntity<CalendarMoveProposalDTO> createCalendarMoveProposal(
      CalendarMoveProposalCreateDTO request) {
    return new ResponseEntity<>(proposals.create(request), HttpStatus.CREATED);
  }

  @Override
  public ResponseEntity<Void> withdrawCalendarMoveProposal(UUID proposalId) {
    proposals.withdraw(proposalId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<CalendarEntryDTO> acceptCalendarMoveProposal(UUID proposalId) {
    return ResponseEntity.ok(proposals.accept(proposalId));
  }

  @Override
  public ResponseEntity<Void> declineCalendarMoveProposal(UUID proposalId) {
    proposals.decline(proposalId);
    return ResponseEntity.noContent().build();
  }

  /** Public: the signed address is the credential. A calendar app polls it, so it may cache. */
  @Override
  public ResponseEntity<String> getCalendarFeed(String feedId, String signature) {
    return ResponseEntity.ok()
        .contentType(TEXT_CALENDAR)
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
        .body(feed.feed(feedId, signature));
  }
}
