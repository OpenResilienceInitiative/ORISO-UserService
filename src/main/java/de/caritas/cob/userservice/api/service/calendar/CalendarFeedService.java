package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.port.out.CalendarSyncRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The subscription feed of one counsellor (ADR-032, ORISO-UserService#1645): their scheduled
 * appointments and group occurrences as an iCalendar document for a calendar app. The address is
 * the only credential and the reader is a third-party app, so an entry says no more than that there
 * is an ORISO appointment and when: no asker, no topic, no session.
 *
 * <p>An unknown feed, a wrong signature and a counsellor who is gone all read as the same 404.
 */
@Service
public class CalendarFeedService {

  static final Duration PAST = Duration.ofDays(30);
  static final Duration FUTURE = Duration.ofDays(180);
  static final String SUMMARY = "ORISO-Termin";
  private static final String NOT_FOUND = "Calendar feed not found.";
  private static final String CRLF = "\r\n";
  private static final int MAX_LINE_OCTETS = 75;
  private static final DateTimeFormatter UTC_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final CalendarSyncRepository syncs;
  private final ConsultantRepository consultants;
  private final CalendarFeedSigner signer;
  private final CalendarEntryReader reader;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String eventUrl;

  public CalendarFeedService(
      CalendarSyncRepository syncs,
      ConsultantRepository consultants,
      CalendarFeedSigner signer,
      CalendarEntryReader reader,
      PlatformTransactionManager transactionManager,
      Clock clock,
      @Value("${app.base.url}") String appBaseUrl) {
    this.syncs = syncs;
    this.consultants = consultants;
    this.signer = signer;
    this.reader = reader;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.eventUrl = appBaseUrl.replaceAll("/+$", "") + "/booking/events";
  }

  /**
   * @return the iCalendar document of the feed
   * @throws NotFoundException unless the signature belongs to the id of a living feed
   */
  public String feed(String feedId, String signature) {
    var id = uuidOf(feedId);
    // The signature is checked first and in constant time: only then is the id looked up.
    if (id == null || !signer.matches(id, signature)) {
      throw notFound();
    }
    var sync = syncs.findByFeedId(id).orElseThrow(CalendarFeedService::notFound);
    var consultantId = sync.getConsultantId();
    var now = Instant.now(clock);
    // A feed request carries no tenant; the feed id has settled whose rows these are.
    List<CalendarEntry> entries =
        TenantContext.supplyAcrossTenants(
            () -> {
              if (consultants.findByIdAndDeleteDateIsNull(consultantId).isEmpty()) {
                return null;
              }
              var found = new ArrayList<CalendarEntry>();
              found.addAll(
                  reader.appointmentsOfConsultant(consultantId, now.minus(PAST), now.plus(FUTURE)));
              found.addAll(
                  reader.groupOccurrencesOfConsultant(
                      consultantId, now.minus(PAST), now.plus(FUTURE)));
              found.sort(
                  Comparator.comparing(CalendarEntry::start).thenComparing(CalendarEntry::id));
              return found;
            });
    if (entries == null) {
      throw notFound();
    }
    transaction.executeWithoutResult(status -> syncs.markFeedFetched(consultantId, now));
    return write(entries, now);
  }

  /** Writes RFC 5545 by hand, so that nothing but the properties named here can appear. */
  String write(List<CalendarEntry> entries, Instant now) {
    var out = new StringBuilder();
    line(out, "BEGIN:VCALENDAR");
    line(out, "VERSION:2.0");
    line(out, "PRODID:-//ORISO//Calendar Feed//DE");
    line(out, "CALSCALE:GREGORIAN");
    for (var entry : entries) {
      line(out, "BEGIN:VEVENT");
      line(out, "UID:" + entry.id() + "@oriso");
      line(out, "DTSTAMP:" + UTC_STAMP.format(now));
      line(out, "DTSTART:" + UTC_STAMP.format(entry.start()));
      line(out, "DTEND:" + UTC_STAMP.format(entry.end()));
      line(out, "SUMMARY:" + SUMMARY);
      line(out, "URL:" + eventUrl);
      line(out, "END:VEVENT");
    }
    line(out, "END:VCALENDAR");
    return out.toString();
  }

  /** One content line, folded at 75 octets with CRLF and a leading space as RFC 5545 asks. */
  private static void line(StringBuilder out, String content) {
    int octets = 0;
    for (int index = 0; index < content.length(); ) {
      int codePoint = content.codePointAt(index);
      int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
      if (octets + size > MAX_LINE_OCTETS) {
        out.append(CRLF).append(' ');
        octets = 1;
      }
      out.appendCodePoint(codePoint);
      octets += size;
      index += Character.charCount(codePoint);
    }
    out.append(CRLF);
  }

  private static UUID uuidOf(String feedId) {
    try {
      // UUID.fromString accepts shortened forms; only the canonical text is a feed id.
      var id = UUID.fromString(feedId);
      return id.toString().equals(feedId) ? id : null;
    } catch (RuntimeException notAnId) {
      return null;
    }
  }

  private static NotFoundException notFound() {
    return new NotFoundException(NOT_FOUND);
  }
}
