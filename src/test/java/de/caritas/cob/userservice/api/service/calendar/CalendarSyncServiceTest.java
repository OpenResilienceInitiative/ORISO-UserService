package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarImportDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarSyncDTO.ImportErrorEnum;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.model.CalendarSync;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarSyncRepository;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@ExtendWith(MockitoExtension.class)
class CalendarSyncServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
  private static final String ME = "me";
  private static final String ADDRESS = "https://93.184.216.34/private/secret-token.ics";
  private static final byte[] CALENDAR =
      ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Synthetic//Test//EN\r\n"
              + "BEGIN:VEVENT\r\nUID:a\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Private matter\r\n"
              + "DTSTART:20261116T090000Z\r\nDTEND:20261116T100000Z\r\nEND:VEVENT\r\n"
              + "BEGIN:VEVENT\r\nUID:b\r\nDTSTAMP:20261101T000000Z\r\n"
              + "DTSTART:20261117T090000Z\r\nDTEND:20261117T100000Z\r\nEND:VEVENT\r\n"
              + "END:VCALENDAR\r\n")
          .getBytes(StandardCharsets.UTF_8);

  @Mock CalendarSyncRepository syncs;
  @Mock CalendarBlockerRepository blockers;
  @Mock CalendarImportFetcher fetcher;
  @Mock PlatformTransactionManager transactionManager;
  @Captor ArgumentCaptor<List<CalendarBlocker>> imported;
  @Captor ArgumentCaptor<TransactionDefinition> definitions;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private final Map<String, CalendarSync> stored = new HashMap<>();
  private CalendarSyncService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setTenantId(3L);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    service =
        new CalendarSyncService(
            caller,
            syncs,
            blockers,
            new CalendarFeedSigner("synthetic-secret"),
            new CalendarImportUrlValidator(host -> new InetAddress[] {InetAddress.getByName(host)}),
            fetcher,
            new CalendarBusyParser(),
            transactionManager,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "https://app.synthetic.oriso.test",
            "Europe/Berlin");
    lenient()
        .when(syncs.findById(any()))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
    lenient()
        .when(syncs.findForUpdate(any()))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
    lenient()
        .when(syncs.save(any()))
        .thenAnswer(
            call -> {
              CalendarSync sync = call.getArgument(0);
              stored.put(sync.getConsultantId(), sync);
              return sync;
            });
    lenient()
        .when(syncs.findByImportUrlIsNotNull())
        .thenAnswer(
            call -> stored.values().stream().filter(sync -> sync.getImportUrl() != null).toList());
  }

  @Test
  void status_Should_CreateTheFeedOnce_And_NameItsSignedAddress() {
    var first = service.status();
    var second = service.status();

    var feedId = stored.get(ME).getFeedId();
    assertThat(first.getFeedUrl())
        .isEqualTo(second.getFeedUrl())
        .startsWith("https://app.synthetic.oriso.test/service/users/calendar/feed/" + feedId + "/")
        .endsWith(".ics")
        .hasSize(
            ("https://app.synthetic.oriso.test/service/users/calendar/feed/" + feedId + "/")
                    .length()
                + 22
                + 4);
    assertThat(first.getImportConfigured()).isFalse();
    assertThat(first.getImportHost()).isNull();
    assertThat(stored.get(ME).getTenantId()).isEqualTo(3L);
  }

  @Test
  void resetFeed_Should_ChangeTheAddress() {
    var before = service.status().getFeedUrl();
    stored.get(ME).setFeedLastFetchedAt(NOW);

    var after = service.resetFeed();

    assertThat(after.getFeedUrl()).isNotEqualTo(before);
    assertThat(after.getFeedLastFetchedAt()).isNull();
    assertThat(service.status().getFeedUrl()).isEqualTo(after.getFeedUrl());
  }

  @Test
  void configureImport_Should_ReplaceTheBusyTimes_And_NeverReturnTheAddress() {
    when(fetcher.fetch(any(), anyList())).thenReturn(CALENDAR);

    var status = service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS));

    assertThat(status.getImportConfigured()).isTrue();
    assertThat(status.getImportHost()).isEqualTo("93.184.216.34");
    assertThat(status.getImportLastSyncAt().toInstant()).isEqualTo(NOW);
    assertThat(status.getImportError()).isNull();
    assertThat(status.toString()).doesNotContain("secret-token", "/private/");
    // Once for the address that is replaced, once right before the fresh rows.
    verify(blockers, times(2)).deleteByConsultantIdAndSource(ME, Source.EXTERNAL);
    verify(blockers).saveAll(imported.capture());
    assertThat(imported.getValue()).hasSize(2);
    for (var busy : imported.getValue()) {
      assertThat(busy.getSource()).isEqualTo(Source.EXTERNAL);
      assertThat(busy.getConsultantId()).isEqualTo(ME);
      assertThat(busy.getTenantId()).isEqualTo(3L);
      assertThat(busy.getNote()).isNull();
      assertThat(busy.getSeriesId()).isNull();
    }
    assertThat(imported.getValue().get(0).getStartTime())
        .isEqualTo(Instant.parse("2026-11-16T09:00:00Z"));
  }

  @Test
  void configureImport_Should_ReportWhyACalendarCannotBeRead() {
    var cases = new HashMap<String, ImportErrorEnum>();
    cases.put("https://127.0.0.1/cal.ics", ImportErrorEnum.FORBIDDEN_ADDRESS);
    cases.put("https://169.254.169.254/latest", ImportErrorEnum.FORBIDDEN_ADDRESS);
    cases.put("http://93.184.216.34/cal.ics", ImportErrorEnum.INVALID_URL);
    cases.forEach(
        (address, expected) -> {
          var status = service.configureImport(new CalendarImportDTO().icsUrl(address));
          assertThat(status.getImportError()).as(address).isEqualTo(expected);
          assertThat(status.getImportConfigured()).isTrue();
          assertThat(status.getImportLastSyncAt()).isNull();
        });
    verifyNoInteractions(fetcher);

    when(fetcher.fetch(any(), anyList()))
        .thenThrow(new CalendarImportException(CalendarImportError.TOO_LARGE))
        .thenThrow(new CalendarImportException(CalendarImportError.UNREACHABLE))
        .thenThrow(new IllegalStateException("boom " + ADDRESS))
        .thenReturn("<html>Sign in</html>".getBytes(StandardCharsets.UTF_8));
    for (var expected :
        List.of(
            ImportErrorEnum.TOO_LARGE,
            ImportErrorEnum.UNREACHABLE,
            ImportErrorEnum.UNREACHABLE,
            ImportErrorEnum.NOT_ICS)) {
      assertThat(service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS)).getImportError())
          .isEqualTo(expected);
    }
    verify(blockers, never()).saveAll(any());
  }

  @Test
  void configureImport_Should_RefuseWhatIsNoUrl_And_ChangeNothing() {
    for (var text : new String[] {"", "not a url", "file:///etc/passwd", null}) {
      assertThatThrownBy(() -> service.configureImport(new CalendarImportDTO().icsUrl(text)))
          .isInstanceOf(BadRequestException.class);
    }
    assertThat(stored).isEmpty();
    verifyNoInteractions(fetcher, blockers);
  }

  @Test
  void aFailedRefresh_Should_KeepTheBusyTimesOfTheLastGoodImport() {
    when(fetcher.fetch(any(), anyList()))
        .thenReturn(CALENDAR)
        .thenThrow(new CalendarImportException(CalendarImportError.UNREACHABLE))
        .thenReturn(CALENDAR);
    service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS));

    service.importAll();

    assertThat(stored.get(ME).getImportError()).isEqualTo("UNREACHABLE");
    assertThat(stored.get(ME).getImportLastSyncAt()).isEqualTo(NOW);
    verify(blockers, times(2)).deleteByConsultantIdAndSource(ME, Source.EXTERNAL);
    verify(blockers, times(1)).saveAll(any());

    service.importAll();

    assertThat(stored.get(ME).getImportError()).isNull();
    verify(blockers, times(3)).deleteByConsultantIdAndSource(ME, Source.EXTERNAL);
    verify(blockers, times(2)).saveAll(any());
  }

  @Test
  void anImportWhoseBusyTimesCannotBeStored_Should_SayItFailed_InATransactionOfItsOwn() {
    when(fetcher.fetch(any(), anyList())).thenReturn(CALENDAR);
    service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS));
    when(blockers.saveAll(anyList())).thenThrow(new IllegalStateException("refused"));

    service.importAll();

    // The last good import stays the latest, and the counsellor sees that this one failed.
    assertThat(stored.get(ME).getImportError()).isEqualTo("UNREACHABLE");
    assertThat(stored.get(ME).getImportLastSyncAt()).isEqualTo(NOW);
    verify(transactionManager, times(1)).rollback(any());
    verify(transactionManager, atLeastOnce()).getTransaction(definitions.capture());
    assertThat(definitions.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void savingTheSameAddressAgain_Should_KeepTheBusyTimes_When_TheRefreshFails() {
    when(fetcher.fetch(any(), anyList()))
        .thenReturn(CALENDAR)
        .thenThrow(new CalendarImportException(CalendarImportError.UNREACHABLE));
    service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS));

    var status = service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS));

    assertThat(status.getImportError()).isEqualTo(ImportErrorEnum.UNREACHABLE);
    assertThat(status.getImportLastSyncAt().toInstant()).isEqualTo(NOW);
    // Only the first import replaced busy times; the failed refresh removed none.
    verify(blockers, times(2)).deleteByConsultantIdAndSource(ME, Source.EXTERNAL);
    verify(blockers, times(1)).saveAll(any());
  }

  @Test
  void importAll_Should_GoOn_When_OneCalendarFails() {
    var other = new CalendarSync();
    other.setConsultantId("other");
    other.setImportUrl("https://93.184.216.35/other.ics");
    stored.put("other", other);
    var mine = new CalendarSync();
    mine.setConsultantId(ME);
    mine.setImportUrl(ADDRESS);
    stored.put(ME, mine);
    when(fetcher.fetch(any(), anyList()))
        .thenAnswer(
            call -> {
              if (call.getArgument(0).toString().contains("other")) {
                throw new CalendarImportException(CalendarImportError.TOO_LARGE);
              }
              return CALENDAR;
            });

    service.importAll();

    assertThat(other.getImportError()).isEqualTo("TOO_LARGE");
    assertThat(mine.getImportError()).isNull();
    assertThat(mine.getImportLastSyncAt()).isEqualTo(NOW);
  }

  @Test
  void anImportThatFinishesAfterTheAddressChanged_Should_BeDropped() {
    service.status();
    stored.get(ME).setImportUrl(ADDRESS);
    when(fetcher.fetch(any(), anyList()))
        .thenAnswer(
            call -> {
              stored.get(ME).setImportUrl("https://93.184.216.34/another.ics");
              return CALENDAR;
            });

    service.importAll();

    verify(blockers, never()).saveAll(any());
    verify(blockers, never()).deleteByConsultantIdAndSource(any(), any());
    assertThat(stored.get(ME).getImportLastSyncAt()).isNull();
  }

  @Test
  void removeImport_Should_ForgetTheAddress_And_RemoveTheBusyTimes() {
    when(fetcher.fetch(any(), anyList())).thenReturn(CALENDAR);
    service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS));

    var status = service.removeImport();

    assertThat(status.getImportConfigured()).isFalse();
    assertThat(status.getImportHost()).isNull();
    assertThat(status.getImportLastSyncAt()).isNull();
    assertThat(stored.get(ME).getImportUrl()).isNull();
    verify(blockers, times(3)).deleteByConsultantIdAndSource(ME, Source.EXTERNAL);
    service.importAll();
    verify(fetcher, times(1)).fetch(any(), anyList());
  }

  @Test
  void asker_Should_NotConnectACalendar() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(service::status).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(service::resetFeed).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(service::removeImport).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.configureImport(new CalendarImportDTO().icsUrl(ADDRESS)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(new ArrayList<>(stored.values())).isEmpty();
  }
}
