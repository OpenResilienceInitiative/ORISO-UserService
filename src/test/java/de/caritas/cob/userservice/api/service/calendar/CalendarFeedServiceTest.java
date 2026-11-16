package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.CalendarSync;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.SessionAppointment.Kind;
import de.caritas.cob.userservice.api.port.out.CalendarSyncRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class CalendarFeedServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
  private static final UUID FEED = UUID.fromString("5b0f3c1e-2a4d-4e7f-9a10-0c8d6f2b7e11");
  private static final String CONSULTANT = "consultant-id";
  private static final String APPOINTMENT = "0d1b0f3a-9d0c-4f6e-8a44-3b2f6f0c9a01";

  @Mock CalendarSyncRepository syncs;
  @Mock ConsultantRepository consultants;
  @Mock CalendarEntryReader reader;
  @Mock PlatformTransactionManager transactionManager;

  private final CalendarFeedSigner signer = new CalendarFeedSigner("synthetic-secret");
  private CalendarFeedService service;

  @BeforeEach
  void setUp() {
    service = service("https://app.synthetic.oriso.test/");
    var sync = new CalendarSync();
    sync.setConsultantId(CONSULTANT);
    sync.setFeedId(FEED);
    lenient().when(syncs.findByFeedId(FEED)).thenReturn(Optional.of(sync));
    lenient()
        .when(consultants.findByIdAndDeleteDateIsNull(CONSULTANT))
        .thenReturn(Optional.of(new Consultant()));
    lenient()
        .when(reader.appointmentsOfConsultant(eq(CONSULTANT), any(), any()))
        .thenReturn(
            List.of(
                new CalendarEntry(
                    APPOINTMENT,
                    CalendarEntryType.APPOINTMENT,
                    Instant.parse("2026-11-20T09:00:00Z"),
                    Instant.parse("2026-11-20T10:00:00Z"),
                    Kind.ONSITE,
                    "asker-username",
                    4711L,
                    null)));
    lenient()
        .when(reader.groupOccurrencesOfConsultant(eq(CONSULTANT), any(), any()))
        .thenReturn(
            List.of(
                new CalendarEntry(
                    "chat-7-2",
                    CalendarEntryType.GROUP,
                    Instant.parse("2026-11-18T17:00:00Z"),
                    Instant.parse("2026-11-18T18:30:00Z"),
                    null,
                    "Secret group topic",
                    null,
                    null)));
  }

  @Test
  void feed_Should_SayOnlyThatThereIsAnAppointmentAndWhen() throws Exception {
    var document = service.feed(FEED.toString(), signer.sign(FEED));

    assertThat(document)
        .doesNotContain("asker-username", "Secret group topic", "4711", "ONSITE")
        .doesNotContain("DESCRIPTION", "LOCATION", "ATTENDEE", "ORGANIZER", "CATEGORIES")
        .doesNotContain(CONSULTANT);
    var events =
        new CalendarBuilder()
            .build(new StringReader(document))
            .<VEvent>getComponents(Component.VEVENT);
    assertThat(events).hasSize(2);
    for (var event : events) {
      assertThat(event.getProperties().stream().map(Property::getName))
          .containsExactlyInAnyOrder("UID", "DTSTAMP", "DTSTART", "DTEND", "SUMMARY", "URL");
      assertThat(value(event, "SUMMARY")).isEqualTo("ORISO-Termin");
      assertThat(value(event, "URL")).isEqualTo("https://app.synthetic.oriso.test/booking/events");
      assertThat(value(event, "DTSTAMP")).isEqualTo("20261114T100000Z");
    }
    assertThat(value(events.get(0), "UID")).isEqualTo("chat-7-2@oriso");
    assertThat(value(events.get(0), "DTSTART")).isEqualTo("20261118T170000Z");
    assertThat(value(events.get(0), "DTEND")).isEqualTo("20261118T183000Z");
    assertThat(value(events.get(1), "UID")).isEqualTo(APPOINTMENT + "@oriso");
    assertThat(value(events.get(1), "DTSTART")).isEqualTo("20261120T090000Z");
    verify(syncs).markFeedFetched(CONSULTANT, NOW);
    verify(reader, never()).blockersOf(any(), any(), any());
  }

  @Test
  void feed_Should_CoverThirtyDaysBackAndHalfAYearAhead() {
    service.feed(FEED.toString(), signer.sign(FEED));

    verify(reader)
        .appointmentsOfConsultant(
            CONSULTANT,
            Instant.parse("2026-10-15T10:00:00Z"),
            Instant.parse("2027-05-13T10:00:00Z"));
    verify(reader)
        .groupOccurrencesOfConsultant(
            CONSULTANT,
            Instant.parse("2026-10-15T10:00:00Z"),
            Instant.parse("2027-05-13T10:00:00Z"));
  }

  @Test
  void feed_Should_UseCrLf_And_FoldLongLines() {
    var longBase = "https://app.synthetic.oriso.test/" + "a".repeat(120);
    var document = service(longBase).feed(FEED.toString(), signer.sign(FEED));

    assertThat(document)
        .startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")
        .endsWith("END:VCALENDAR\r\n");
    assertThat(document.replace("\r\n", "")).doesNotContain("\n", "\r");
    for (var line : document.split("\r\n")) {
      assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
    }
    assertThat(document.replace("\r\n ", "")).contains("URL:" + longBase + "/booking/events\r\n");
  }

  @Test
  void feed_Should_BeEmptyButValid_When_NothingIsScheduled() throws Exception {
    when(reader.appointmentsOfConsultant(eq(CONSULTANT), any(), any())).thenReturn(List.of());
    when(reader.groupOccurrencesOfConsultant(eq(CONSULTANT), any(), any())).thenReturn(List.of());

    var document = service.feed(FEED.toString(), signer.sign(FEED));

    assertThat(new CalendarBuilder().build(new StringReader(document)).getComponents()).isEmpty();
  }

  @Test
  void feed_Should_AnswerNotFound_ForAWrongSignature_WithoutLookingAnythingUp() {
    var good = signer.sign(FEED);

    for (var signature : new String[] {"", "x", good.substring(1) + "A", good + "A", null}) {
      assertThatThrownBy(() -> service.feed(FEED.toString(), signature))
          .isInstanceOf(NotFoundException.class);
    }
    assertThatThrownBy(() -> service.feed("not-a-uuid", good))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.feed(null, good)).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(syncs, reader, consultants);
  }

  @Test
  void feed_Should_AnswerTheSameNotFound_ForAnUnknownFeed_And_ADeletedCounsellor() {
    var unknown = UUID.randomUUID();
    when(syncs.findByFeedId(unknown)).thenReturn(Optional.empty());
    var wrongSignature = catchNotFound(() -> service.feed(FEED.toString(), signer.sign(unknown)));
    var unknownFeed = catchNotFound(() -> service.feed(unknown.toString(), signer.sign(unknown)));
    when(consultants.findByIdAndDeleteDateIsNull(CONSULTANT)).thenReturn(Optional.empty());
    var deleted = catchNotFound(() -> service.feed(FEED.toString(), signer.sign(FEED)));

    assertThat(unknownFeed.getMessage())
        .isEqualTo(wrongSignature.getMessage())
        .isEqualTo(deleted.getMessage());
    verify(syncs, never()).markFeedFetched(any(), any());
  }

  private CalendarFeedService service(String appBaseUrl) {
    return new CalendarFeedService(
        syncs,
        consultants,
        signer,
        reader,
        transactionManager,
        Clock.fixed(NOW, ZoneOffset.UTC),
        appBaseUrl);
  }

  private static NotFoundException catchNotFound(Runnable call) {
    try {
      call.run();
    } catch (NotFoundException expected) {
      return expected;
    }
    throw new AssertionError("Expected a NotFoundException");
  }

  private static String value(VEvent event, String property) {
    return event.getProperties(property).get(0).getValue();
  }
}
