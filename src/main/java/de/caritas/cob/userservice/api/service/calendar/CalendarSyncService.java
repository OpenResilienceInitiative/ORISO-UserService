package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarImportDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarSyncDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.model.CalendarSync;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarSyncRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A counsellor's calendar connections (ADR-032, ORISO-UserService#1645): the address of their
 * subscription feed, and the import of busy times from their own calendar.
 *
 * <p>The import address is private: it is never logged and never returned, only its host. An import
 * reads the calendar outside any database transaction and then replaces all imported busy times of
 * the counsellor in one. A failed import keeps the busy times of the last good one and records why
 * it failed as a code. Every transaction that reads the connection row and writes it back locks it
 * first, so none of them overwrites what another changed meanwhile. A write that fails rolls back
 * whole; its code is then recorded in a transaction of its own, so the last good import is not
 * shown as the latest without an error. The connection of a counsellor who is deleted is dropped by
 * the next import run instead of being read.
 */
@Service
@Slf4j
public class CalendarSyncService {

  static final Duration IMPORT_PAST = Duration.ofDays(7);
  static final Duration IMPORT_FUTURE = Duration.ofDays(90);
  static final String FEED_PATH = "/service/users/calendar/feed/";

  private final AuthenticatedUser caller;
  private final CalendarSyncRepository syncs;
  private final CalendarBlockerRepository blockers;
  private final ConsultantRepository consultants;
  private final CalendarFeedSigner signer;
  private final CalendarImportUrlValidator validator;
  private final CalendarImportFetcher fetcher;
  private final CalendarBusyParser parser;
  private final TransactionTemplate transaction;
  private final TransactionTemplate ownTransaction;
  private final Clock clock;
  private final String appBaseUrl;
  private final ZoneId zone;

  public CalendarSyncService(
      AuthenticatedUser caller,
      CalendarSyncRepository syncs,
      CalendarBlockerRepository blockers,
      ConsultantRepository consultants,
      CalendarFeedSigner signer,
      CalendarImportUrlValidator validator,
      CalendarImportFetcher fetcher,
      CalendarBusyParser parser,
      PlatformTransactionManager transactionManager,
      Clock clock,
      @Value("${app.base.url}") String appBaseUrl,
      @Value("${calendar.zone:Europe/Berlin}") String zone) {
    this.caller = caller;
    this.syncs = syncs;
    this.blockers = blockers;
    this.consultants = consultants;
    this.signer = signer;
    this.validator = validator;
    this.fetcher = fetcher;
    this.parser = parser;
    this.transaction = new TransactionTemplate(transactionManager);
    this.ownTransaction = new TransactionTemplate(transactionManager);
    ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
    this.zone = ZoneId.of(zone);
  }

  /** The caller's connections; the feed id is made on first use. */
  public CalendarSyncDTO status() {
    requireConsultant();
    return toDto(ownSync());
  }

  /** A new feed id: the old address stops answering at once. */
  public CalendarSyncDTO resetFeed() {
    requireConsultant();
    ownSync();
    var consultantId = caller.getUserId();
    return toDto(
        transaction.execute(
            status -> {
              var sync = syncs.findForUpdate(consultantId).orElseThrow();
              sync.setFeedId(UUID.randomUUID());
              sync.setFeedLastFetchedAt(null);
              return syncs.save(sync);
            }));
  }

  /**
   * Stores the address, drops what an earlier address imported and imports once. Saving the address
   * already stored only refreshes it: its busy times stay until a refresh succeeds.
   */
  public CalendarSyncDTO configureImport(CalendarImportDTO request) {
    requireConsultant();
    var address = validator.parse(request.getIcsUrl()).toString();
    ownSync();
    var consultantId = caller.getUserId();
    transaction.executeWithoutResult(
        status -> {
          var sync = syncs.findForUpdate(consultantId).orElseThrow();
          if (address.equals(sync.getImportUrl())) {
            return;
          }
          sync.setImportUrl(address);
          sync.setImportLastSyncAt(null);
          sync.setImportError(null);
          syncs.save(sync);
          blockers.deleteByConsultantIdAndSource(consultantId, Source.EXTERNAL);
        });
    importOnce(consultantId, address);
    return toDto(syncs.findById(consultantId).orElseThrow());
  }

  /** Forgets the address and removes every imported busy time. */
  public CalendarSyncDTO removeImport() {
    requireConsultant();
    ownSync();
    var consultantId = caller.getUserId();
    return toDto(
        transaction.execute(
            status -> {
              var sync = syncs.findForUpdate(consultantId).orElseThrow();
              sync.setImportUrl(null);
              sync.setImportLastSyncAt(null);
              sync.setImportError(null);
              blockers.deleteByConsultantIdAndSource(consultantId, Source.EXTERNAL);
              return syncs.save(sync);
            }));
  }

  /** Imports every configured calendar once; one that fails does not stop the others. */
  public void importAll() {
    for (var sync : dueImports()) {
      try {
        importOnce(sync.getConsultantId(), sync.getImportUrl());
      } catch (RuntimeException failure) {
        // Neither the address nor a message that may quote it goes into the log.
        log.warn("A calendar import failed: {}", failure.getClass().getSimpleName());
      }
    }
  }

  /**
   * The configured calendars of counsellors who exist and are not deleted. The connection of a
   * deleted counsellor is dropped here, so the private address is not kept and the calendar is not
   * read again.
   */
  List<CalendarSync> dueImports() {
    var configured = syncs.findByImportUrlIsNotNull();
    if (configured.isEmpty()) {
      return List.of();
    }
    var active =
        new HashSet<>(
            consultants.findIdsNotDeletedAmong(
                configured.stream().map(CalendarSync::getConsultantId).toList()));
    var due = new ArrayList<CalendarSync>();
    for (var sync : configured) {
      if (active.contains(sync.getConsultantId())) {
        due.add(sync);
      } else {
        forgetDeleted(sync.getConsultantId());
      }
    }
    return due;
  }

  /** Drops the connection of a deleted counsellor and the busy times imported with it. */
  private void forgetDeleted(String consultantId) {
    try {
      transaction.executeWithoutResult(
          status ->
              syncs
                  .findForUpdate(consultantId)
                  .ifPresent(
                      sync -> {
                        blockers.deleteByConsultantIdAndSource(consultantId, Source.EXTERNAL);
                        syncs.delete(sync);
                      }));
    } catch (RuntimeException notDropped) {
      log.warn(
          "The calendar connection of a deleted counsellor could not be dropped: {}",
          notDropped.getClass().getSimpleName());
    }
  }

  private void importOnce(String consultantId, String address) {
    var now = Instant.now(clock);
    CalendarImportError error = null;
    List<BusySlot> slots = List.of();
    try {
      var uri = URI.create(address);
      var validated = validator.publicAddressesOf(uri);
      slots =
          parser.parse(
              fetcher.fetch(uri, validated), now.minus(IMPORT_PAST), now.plus(IMPORT_FUTURE), zone);
    } catch (CalendarImportException failure) {
      error = failure.error();
    } catch (RuntimeException failure) {
      log.warn("A calendar import failed: {}", failure.getClass().getSimpleName());
      error = CalendarImportError.UNREACHABLE;
    }
    try {
      store(consultantId, address, now, error, slots);
    } catch (RuntimeException notStored) {
      // Rolled back: the busy times of the last good import stay, and so would its time without
      // an error. The caller logs the failure.
      recordError(consultantId, address, error == null ? CalendarImportError.UNREACHABLE : error);
      throw notStored;
    }
  }

  /** In a transaction of its own; the address must still be the one that was read. */
  private void recordError(String consultantId, String address, CalendarImportError error) {
    try {
      ownTransaction.executeWithoutResult(
          status -> {
            var sync = syncs.findForUpdate(consultantId).orElse(null);
            if (sync == null || !address.equals(sync.getImportUrl())) {
              return;
            }
            sync.setImportError(error.name());
            syncs.save(sync);
          });
    } catch (RuntimeException alsoFailed) {
      log.warn(
          "The error of a calendar import could not be recorded: {}",
          alsoFailed.getClass().getSimpleName());
    }
  }

  private void store(
      String consultantId,
      String address,
      Instant now,
      CalendarImportError failed,
      List<BusySlot> imported) {
    transaction.executeWithoutResult(
        status -> {
          var sync = syncs.findForUpdate(consultantId).orElse(null);
          // Removed or pointed elsewhere while the calendar was being read: not this result.
          if (sync == null || !address.equals(sync.getImportUrl())) {
            return;
          }
          if (failed != null) {
            sync.setImportError(failed.name());
            syncs.save(sync);
            return;
          }
          blockers.deleteByConsultantIdAndSource(consultantId, Source.EXTERNAL);
          var rows = new ArrayList<CalendarBlocker>();
          for (var slot : imported) {
            var busy = new CalendarBlocker();
            busy.setConsultantId(consultantId);
            busy.setTenantId(sync.getTenantId());
            busy.setStartTime(slot.start());
            busy.setEndTime(slot.end());
            busy.setSource(Source.EXTERNAL);
            busy.setCreateDate(now);
            rows.add(busy);
          }
          blockers.saveAll(rows);
          sync.setImportLastSyncAt(now);
          sync.setImportError(null);
          syncs.save(sync);
        });
  }

  private CalendarSync ownSync() {
    var consultantId = caller.getUserId();
    var existing = syncs.findById(consultantId);
    if (existing.isPresent()) {
      return existing.get();
    }
    try {
      return transaction.execute(
          status -> {
            var sync = new CalendarSync();
            sync.setConsultantId(consultantId);
            sync.setTenantId(caller.getTenantId());
            sync.setFeedId(UUID.randomUUID());
            return syncs.save(sync);
          });
    } catch (DataIntegrityViolationException createdMeanwhile) {
      return syncs.findById(consultantId).orElseThrow();
    }
  }

  private CalendarSyncDTO toDto(CalendarSync sync) {
    var dto =
        new CalendarSyncDTO()
            .feedUrl(feedUrlOf(sync.getFeedId()))
            .feedLastFetchedAt(
                sync.getFeedLastFetchedAt() == null
                    ? null
                    : sync.getFeedLastFetchedAt().atOffset(ZoneOffset.UTC))
            .importConfigured(sync.getImportUrl() != null);
    if (sync.getImportUrl() != null) {
      dto.importHost(hostOf(sync.getImportUrl()))
          .importLastSyncAt(
              sync.getImportLastSyncAt() == null
                  ? null
                  : sync.getImportLastSyncAt().atOffset(ZoneOffset.UTC))
          .importError(
              sync.getImportError() == null
                  ? null
                  : CalendarSyncDTO.ImportErrorEnum.fromValue(sync.getImportError()));
    }
    return dto;
  }

  String feedUrlOf(UUID feedId) {
    return appBaseUrl + FEED_PATH + feedId + "/" + signer.sign(feedId) + ".ics";
  }

  private static String hostOf(String address) {
    try {
      return URI.create(address).getHost();
    } catch (IllegalArgumentException unreadable) {
      return null;
    }
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor connects a calendar.");
    }
  }
}
