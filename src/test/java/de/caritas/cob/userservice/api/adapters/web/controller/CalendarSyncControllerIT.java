package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.model.CalendarSync;
import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarSyncRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.calendar.CalendarImportFetcher;
import de.caritas.cob.userservice.api.service.calendar.CalendarSyncService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantCalendarAction;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscription feed and busy import through the real security chain, services and database. Only
 * the network is replaced: the calendar a counsellor names is never fetched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true", "calendar.import.enabled=false"})
@Import(TenantFixtures.class)
@WithTenant(CalendarSyncControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.USER_DEFAULT})
class CalendarSyncControllerIT {

  static final long TENANT = 1L;
  private static final long AGENCY = 9831L;
  private static final String APP_BASE = "http://localhost:8082";
  private static final String PUBLIC_ADDRESS = "https://93.184.216.34/private/secret-token.ics";
  private static final String OTHER_ADDRESS = "https://93.184.216.35/private/other-token.ics";
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DateTimeFormatter ICS_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @MockitoSpyBean private CalendarBlockerRepository blockers;
  @Autowired private CalendarSyncRepository syncs;
  @Autowired private CalendarSyncService syncService;
  @Autowired private CalendarWorkingHoursRepository workingHours;
  @Autowired private CalendarWorkingDayExceptionRepository dayExceptions;
  @Autowired private CalendarWorkingHoursSettingRepository holidayRegions;
  @Autowired private DeleteConsultantCalendarAction deleteCalendar;
  @Autowired private ConsultantRepository consultants;
  @Autowired private DeletionLifecycleService lifecycle;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private ChatRepository chats;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean CalendarImportFetcher fetcher;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant counsellor;
  private User asker;
  private Session session;
  private final List<Chat> seededChats = new ArrayList<>();

  @BeforeEach
  void seed() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    counsellor = fixtures.consultant(TENANT, AGENCY);
    asker = fixtures.adviceSeeker(TENANT);
    session = fixtures.session(asker, AGENCY, counsellor);
  }

  @AfterEach
  void removeSeededRows() {
    Tenants.acrossAll(
        () -> {
          blockers.deleteAll();
          syncs.deleteAll();
          workingHours.deleteAll();
          dayExceptions.deleteAll();
          holidayRegions.deleteAll();
          chats.deleteAll(seededChats);
        });
    fixtures.removeAll();
  }

  @Test
  void feed_Should_BeReadableWithoutLogin_And_SayNothingButTimes() throws Exception {
    actAsCounsellor();
    var appointment = appointmentAt(in(24), in(25));
    var chat = groupChatAt(in(48));
    blockerAt(in(72), in(73), Source.ORISO);
    blockerAt(in(96), in(97), Source.EXTERNAL);
    var sync = body(get("/users/calendar/sync"));
    assertThat(sync).doesNotContainKey("feedLastFetchedAt");
    assertThat(sync.get("importConfigured")).isEqualTo(false);
    var feedUrl = (String) sync.get("feedUrl");
    assertThat(feedUrl)
        .matches(APP_BASE + "/service/users/calendar/feed/[0-9a-f-]{36}/[A-Za-z0-9_-]{22}\\.ics");

    // A calendar app: no token, no tenant.
    TenantContext.clear();
    var response = feed(feedUrl);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualToIgnoringCase("text/calendar;charset=UTF-8");
    assertThat(response.getHeader("Cache-Control")).contains("private").contains("max-age=300");
    assertThat(response.getHeader("Set-Cookie")).isNull();
    var document = response.getContentAsString();
    assertThat(document).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n");
    assertThat(document.split("BEGIN:VEVENT", -1)).hasSize(3);
    assertThat(document)
        .contains("UID:" + appointment.getId() + "@oriso\r\n")
        .contains("UID:chat-" + chat.getId() + "-0@oriso\r\n")
        .contains("DTSTART:" + ICS_TIME.format(in(24)) + "\r\n")
        .contains("DTEND:" + ICS_TIME.format(in(25)) + "\r\n")
        .contains("SUMMARY:ORISO-Termin\r\n")
        .contains("URL:" + APP_BASE + "/booking/events\r\n");
    assertThat(document)
        .doesNotContain(asker.getUsername(), "Synthetic group topic", "Private note")
        .doesNotContain("DESCRIPTION", "LOCATION", "ATTENDEE", "ORGANIZER")
        .doesNotContain(counsellor.getId(), asker.getUserId())
        .doesNotContain("X-ORISO", "ONSITE");
    assertThat(document.replaceAll("@oriso|ORISO-Termin|//ORISO//", ""))
        .doesNotContainPattern("(?i)session");

    actAsCounsellor();
    assertThat(body(get("/users/calendar/sync")).get("feedLastFetchedAt")).isNotNull();
  }

  @Test
  void feed_Should_AnswerTheSameNotFound_ForAWrongSignatureAndAnUnknownFeed() throws Exception {
    actAsCounsellor();
    var feedUrl = (String) body(get("/users/calendar/sync")).get("feedUrl");
    var signature = feedUrl.substring(feedUrl.lastIndexOf('/') + 1, feedUrl.length() - 4);
    var flipped = (signature.charAt(0) == 'A' ? 'B' : 'A') + signature.substring(1);
    TenantContext.clear();

    var wrongSignature = feed(feedUrl.replace(signature, flipped));
    var unknownFeed =
        feed(feedUrl.replaceFirst("/feed/[0-9a-f-]{36}/", "/feed/" + UUID.randomUUID() + "/"));
    var noUuid = feed(feedUrl.replaceFirst("/feed/[0-9a-f-]{36}/", "/feed/1/"));

    assertThat(wrongSignature.getStatus()).isEqualTo(404);
    assertThat(unknownFeed.getStatus()).isEqualTo(404);
    assertThat(noUuid.getStatus()).isEqualTo(404);
    assertThat(unknownFeed.getContentAsString())
        .isEqualTo(wrongSignature.getContentAsString())
        .isEqualTo(noUuid.getContentAsString())
        .doesNotContain("VCALENDAR");
    assertThat(feed(feedUrl).getStatus()).isEqualTo(200);
  }

  @Test
  void reset_Should_InvalidateTheOldAddressAtOnce() throws Exception {
    actAsCounsellor();
    var before = (String) body(get("/users/calendar/sync")).get("feedUrl");
    assertThat(feed(before).getStatus()).isEqualTo(200);

    actAsCounsellor();
    var after = (String) body(csrf(post("/users/calendar/sync/feed/reset"))).get("feedUrl");

    assertThat(after).isNotEqualTo(before);
    TenantContext.clear();
    assertThat(feed(before).getStatus()).isEqualTo(404);
    assertThat(feed(after).getStatus()).isEqualTo(200);
    actAsCounsellor();
    assertThat(body(get("/users/calendar/sync")).get("feedUrl")).isEqualTo(after);
  }

  @Test
  void import_Should_ReplaceBusyTimes_And_RemoveThemWithTheAddress() throws Exception {
    actAsCounsellor();
    blockerAt(in(200), in(201), Source.ORISO);
    when(fetcher.fetch(any(), anyList()))
        .thenReturn(calendar(event(in(24), in(25)), event(in(48), in(49))))
        .thenReturn(calendar(event(in(72), in(73))));

    var first = body(setImport(PUBLIC_ADDRESS));

    assertThat(first.get("importConfigured")).isEqualTo(true);
    assertThat(first.get("importHost")).isEqualTo("93.184.216.34");
    assertThat(first.get("importLastSyncAt")).isNotNull();
    assertThat(first).doesNotContainKey("importError");
    assertThat(JSON.writeValueAsString(first)).doesNotContain("secret-token", "/private/");
    assertThat(busyStarts()).containsExactly(in(24), in(48));
    var entries = entries();
    assertThat(entries)
        .extracting(row -> row.get("type"))
        .containsExactly("BUSY", "BUSY", "BLOCKER");
    assertThat(entries.get(0)).doesNotContainKey("title");
    assertThat(JSON.writeValueAsString(entries)).doesNotContain("Private matter");

    body(setImport(PUBLIC_ADDRESS));
    assertThat(busyStarts()).containsExactly(in(72));

    var removed = body(csrf(delete("/users/calendar/sync/import")));
    assertThat(removed.get("importConfigured")).isEqualTo(false);
    assertThat(removed).doesNotContainKeys("importHost", "importLastSyncAt", "importError");
    assertThat(busyStarts()).isEmpty();
    assertThat(entries()).extracting(row -> row.get("type")).containsExactly("BLOCKER");
  }

  @Test
  void import_Should_ReportAnAddressThatMustNotBeRead_WithoutFetchingIt() throws Exception {
    actAsCounsellor();

    for (var refused :
        Map.of(
                "https://127.0.0.1/cal.ics", "FORBIDDEN_ADDRESS",
                "https://169.254.169.254/latest/meta-data", "FORBIDDEN_ADDRESS",
                "https://[::1]/cal.ics", "FORBIDDEN_ADDRESS",
                "https://10.0.0.5/cal.ics", "FORBIDDEN_ADDRESS")
            .entrySet()) {
      var status = body(setImport(refused.getKey()));
      assertThat(status.get("importError")).as(refused.getKey()).isEqualTo(refused.getValue());
      assertThat(status.get("importConfigured")).isEqualTo(true);
      assertThat(status).doesNotContainKey("importLastSyncAt");
    }
    mockMvc.perform(setImport("not a url")).andExpect(status().isBadRequest());
    mockMvc.perform(setImport("")).andExpect(status().isBadRequest());
    verifyNoInteractions(fetcher);
  }

  @Test
  void import_Should_RefuseAPlainOrCredentialedAddress_With400_AndStoreNothing() throws Exception {
    actAsCounsellor();

    for (var refused :
        List.of(
            "http://93.184.216.34/private/secret-token.ics",
            "https://alice:secret@93.184.216.34/private/secret-token.ics")) {
      var answer =
          mockMvc
              .perform(setImport(refused))
              .andExpect(status().isBadRequest())
              .andReturn()
              .getResponse();
      assertThat(answer.getContentAsString()).doesNotContain("secret", "alice");
    }

    assertThat(syncs.findById(counsellor.getId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM calendar_sync WHERE import_url IS NOT NULL", Integer.class))
        .isZero();
    verifyNoInteractions(fetcher);
  }

  @Test
  void deletingTheCounsellor_Should_RemoveTheirCalendarRows_AndNoOneElses() {
    var colleague = fixtures.consultant(TENANT, AGENCY);
    for (var owner : List.of(counsellor.getId(), colleague.getId())) {
      var sync = new CalendarSync();
      sync.setConsultantId(owner);
      sync.setTenantId(TENANT);
      sync.setFeedId(UUID.randomUUID());
      sync.setImportUrl(PUBLIC_ADDRESS);
      syncs.save(sync);
      blockerOf(owner, in(24), in(25), Source.ORISO);
      blockerOf(owner, in(48), in(49), Source.EXTERNAL);
      var hours = new CalendarWorkingHours();
      hours.setConsultantId(owner);
      hours.setWeekday(1);
      hours.setStartTime(LocalTime.of(9, 0));
      hours.setEndTime(LocalTime.of(17, 0));
      workingHours.save(hours);
      var dayOff = new CalendarWorkingDayException();
      dayOff.setConsultantId(owner);
      dayOff.setExceptionDate(LocalDate.now(ZoneOffset.UTC).plusDays(1));
      dayExceptions.save(dayOff);
      var region = new CalendarWorkingHoursSetting();
      region.setConsultantId(owner);
      region.setHolidayRegion("BY");
      holidayRegions.save(region);
    }
    var workflow = new ConsultantDeletionWorkflowDTO(counsellor, new ArrayList<>());

    deleteCalendar.execute(workflow);

    assertThat(workflow.getDeletionWorkflowErrors()).isEmpty();
    for (var table :
        List.of(
            "calendar_sync",
            "calendar_blocker",
            "calendar_working_hours",
            "calendar_working_day_exception",
            "calendar_working_hours_setting")) {
      assertThat(rowsOf(table, counsellor.getId())).as(table).isZero();
      assertThat(rowsOf(table, colleague.getId())).as(table).isPositive();
    }
  }

  @Test
  void aSoftDeletedCounsellor_Should_KeepTheirCalendar_AndBeReadAgainOnceTheDeletionIsCancelled() {
    connectImport(PUBLIC_ADDRESS);
    blockerAt(in(24), in(25), Source.EXTERNAL);
    inTransaction(
        () -> {
          var marked = consultants.findById(counsellor.getId()).orElseThrow();
          lifecycle.beginConsultantDeletion(marked, "synthetic-admin");
          consultants.save(marked);
        });

    syncService.importAll();

    verifyNoInteractions(fetcher);
    assertThat(syncs.findById(counsellor.getId()).orElseThrow().getImportUrl())
        .isEqualTo(PUBLIC_ADDRESS);
    assertThat(busyStarts()).containsExactly(in(24));

    inTransaction(
        () -> {
          var marked = consultants.findById(counsellor.getId()).orElseThrow();
          lifecycle.cancelConsultantDeletion(marked);
          consultants.save(marked);
        });
    when(fetcher.fetch(any(), anyList())).thenReturn(calendar(event(in(48), in(49))));

    syncService.importAll();

    verify(fetcher).fetch(any(), anyList());
    assertThat(busyStarts()).containsExactly(in(48));
  }

  @Test
  void importAll_Should_DropAConnectionWhoseCounsellorHasNoRow_WithoutReadingIt() {
    var orphan = UUID.randomUUID().toString();
    var sync = new CalendarSync();
    sync.setConsultantId(orphan);
    sync.setTenantId(TENANT);
    sync.setFeedId(UUID.randomUUID());
    sync.setImportUrl(OTHER_ADDRESS);
    syncs.save(sync);
    blockerOf(orphan, in(24), in(25), Source.EXTERNAL);

    syncService.importAll();

    verifyNoInteractions(fetcher);
    assertThat(syncs.findById(orphan)).isEmpty();
    assertThat(rowsOf("calendar_blocker", orphan)).isZero();
  }

  @Test
  void import_Should_ReportACalendarThatIsNone() throws Exception {
    actAsCounsellor();
    when(fetcher.fetch(any(), anyList()))
        .thenReturn("<html>Sign in</html>".getBytes(StandardCharsets.UTF_8));

    var status = body(setImport(PUBLIC_ADDRESS));

    assertThat(status.get("importError")).isEqualTo("NOT_ICS");
    assertThat(busyStarts()).isEmpty();
    verify(fetcher).fetch(any(), anyList());
  }

  @Test
  void aReplacementThatFailsAfterTheDelete_Should_KeepTheBusyTimesOfTheLastGoodImport()
      throws Exception {
    connectImport(PUBLIC_ADDRESS);
    blockerAt(in(24), in(25), Source.EXTERNAL);
    blockerAt(in(48), in(49), Source.EXTERNAL);
    var left = new AtomicReference<Integer>();
    when(fetcher.fetch(any(), anyList()))
        .thenReturn(calendar(event(in(72), in(73)), event(in(96), in(97))));
    refuseTheSecondNewRow(() -> left.set(externalRowsSeenByTheImport()));

    syncService.importAll();

    assertThat(left.get()).as("the import had deleted the old busy times").isZero();
    assertThat(busyStarts()).containsExactly(in(24), in(48));
    var sync = syncs.findById(counsellor.getId()).orElseThrow();
    assertThat(sync.getImportLastSyncAt()).isNull();
    // Recorded after the rollback: the counsellor sees that the import failed.
    assertThat(sync.getImportError()).isEqualTo("UNREACHABLE");
  }

  @Test
  void aReplacementThatFailsAfterTheDelete_Should_KeepTheTimeOfTheLastGoodImport()
      throws Exception {
    connectImport(PUBLIC_ADDRESS);
    var lastGoodImport = in(-1);
    var connected = syncs.findById(counsellor.getId()).orElseThrow();
    connected.setImportLastSyncAt(lastGoodImport);
    syncs.save(connected);
    when(fetcher.fetch(any(), anyList()))
        .thenReturn(calendar(event(in(72), in(73)), event(in(96), in(97))));
    refuseTheSecondNewRow(() -> {});

    syncService.importAll();

    var sync = syncs.findById(counsellor.getId()).orElseThrow();
    assertThat(sync.getImportLastSyncAt()).isEqualTo(lastGoodImport);
    assertThat(sync.getImportError()).isEqualTo("UNREACHABLE");
  }

  @Test
  void anAddressChangedWhileAnImportIsWritten_Should_NotBeOverwrittenByTheImport()
      throws Exception {
    connectImport(PUBLIC_ADDRESS);
    when(fetcher.fetch(any(), anyList())).thenReturn(calendar(event(in(24), in(25))));
    var change = new AtomicReference<CompletableFuture<Integer>>();
    doAnswer(
            call -> {
              // A second request stores another address while the import writes its result.
              var update =
                  CompletableFuture.supplyAsync(
                      () ->
                          jdbc.update(
                              "UPDATE calendar_sync SET import_url = ? WHERE consultant_id = ?",
                              OTHER_ADDRESS,
                              counsellor.getId()));
              change.set(update);
              try {
                update.get(300, TimeUnit.MILLISECONDS);
              } catch (TimeoutException waitsForTheImport) {
                // The row is locked: the change is applied once the import commits.
              }
              return callThrough(call);
            })
        .when(blockers)
        .deleteByConsultantIdAndSource(counsellor.getId(), Source.EXTERNAL);

    syncService.importAll();

    assertThat(change.get().get(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(busyStarts()).as("the import itself went through").containsExactly(in(24));
    assertThat(syncs.findById(counsellor.getId()).orElseThrow().getImportUrl())
        .isEqualTo(OTHER_ADDRESS);
  }

  @Test
  void asker_Should_NotReachTheConnections() throws Exception {
    Tenants.actAs(caller, asker.getUserId(), TENANT, UserRole.USER);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.USER_DEFAULT));

    mockMvc.perform(get("/users/calendar/sync")).andExpect(status().isForbidden());
    mockMvc
        .perform(csrf(post("/users/calendar/sync/feed/reset")))
        .andExpect(status().isForbidden());
    mockMvc.perform(setImport(PUBLIC_ADDRESS)).andExpect(status().isForbidden());
    mockMvc.perform(csrf(delete("/users/calendar/sync/import"))).andExpect(status().isForbidden());
    verifyNoInteractions(fetcher);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void askerAuthority_Should_BeForbidden() throws Exception {
    mockMvc.perform(get("/users/calendar/sync")).andExpect(status().isForbidden());
    mockMvc.perform(get("/service/users/calendar/sync")).andExpect(status().isForbidden());
    mockMvc.perform(setImport(PUBLIC_ADDRESS)).andExpect(status().isForbidden());
  }

  @Test
  @WithAnonymousUser
  void unauthenticatedCaller_Should_OnlyReachTheFeed() throws Exception {
    mockMvc.perform(get("/users/calendar/sync")).andExpect(status().isUnauthorized());
    mockMvc.perform(setImport(PUBLIC_ADDRESS)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/users/calendar/feed/" + UUID.randomUUID() + "/AAAAAAAAAAAAAAAAAAAAAA.ics"))
        .andExpect(status().isNotFound());
  }

  private MockHttpServletResponse feed(String feedUrl) throws Exception {
    // The gateway strips "/service"; the application itself is mapped without it.
    var path = feedUrl.substring((APP_BASE + "/service").length());
    return mockMvc.perform(get(path).with(anonymous())).andReturn().getResponse();
  }

  private MockHttpServletRequestBuilder setImport(String address) throws Exception {
    return csrf(put("/users/calendar/sync/import"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("icsUrl", address)));
  }

  private List<Instant> busyStarts() {
    var found = new ArrayList<Instant>();
    blockers
        .findOverlapping(counsellor.getId(), in(-24 * 8), in(24 * 91))
        .forEach(
            blocker -> {
              if (blocker.getSource() == Source.EXTERNAL) {
                found.add(blocker.getStartTime());
              }
            });
    return found;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> entries() throws Exception {
    return JSON.readValue(
        mockMvc
            .perform(
                get("/users/calendar/entries")
                    .param("from", in(0).toString())
                    .param("to", in(24 * 30).toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        List.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(MockHttpServletRequestBuilder request) throws Exception {
    return JSON.readValue(
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        Map.class);
  }

  private SessionAppointment appointmentAt(Instant start, Instant end) {
    var appointment = new SessionAppointment();
    appointment.setSession(session);
    appointment.setStartTime(start);
    appointment.setEndTime(end);
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    appointment.setKind(SessionAppointment.Kind.ONSITE);
    return Tenants.in(TENANT, () -> appointments.save(appointment));
  }

  private Chat groupChatAt(Instant start) {
    var startUtc = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
    var chat =
        Tenants.in(
            TENANT,
            () ->
                chats.save(
                    Chat.builder()
                        .topic("Synthetic group topic")
                        .chatOwner(counsellor)
                        .consultingTypeId(1)
                        .initialStartDate(startUtc)
                        .startDate(startUtc)
                        .duration(60)
                        .repeatCount(1)
                        .timezone("UTC")
                        .chatModality(ChatModality.TEXT)
                        .conversationType(ConversationType.SELF_HELP)
                        .active(false)
                        .build()));
    seededChats.add(chat);
    return chat;
  }

  /** The database refuses the second new row of an import, after the old ones were deleted. */
  private void refuseTheSecondNewRow(Runnable beforeTheWrite) {
    doAnswer(
            call -> {
              beforeTheWrite.run();
              var rows = new ArrayList<CalendarBlocker>();
              call.<Iterable<CalendarBlocker>>getArgument(0).forEach(rows::add);
              rows.get(1).setStartTime(null);
              return callThrough(call);
            })
        .when(blockers)
        .saveAll(anyIterable());
  }

  /**
   * Runs the repository behind the spy. A repository is a JDK proxy without a real method Mockito
   * could call, so the spy delegates to it as its default answer.
   */
  private Object callThrough(InvocationOnMock call) throws Throwable {
    return Mockito.mockingDetails(blockers)
        .getMockCreationSettings()
        .getDefaultAnswer()
        .answer(call);
  }

  /** Imported busy times of the counsellor as the running import's own transaction sees them. */
  private Integer externalRowsSeenByTheImport() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM calendar_blocker WHERE consultant_id = ? AND source = 'EXTERNAL'",
        Integer.class,
        counsellor.getId());
  }

  private void connectImport(String address) {
    var sync = new CalendarSync();
    sync.setConsultantId(counsellor.getId());
    sync.setTenantId(TENANT);
    sync.setFeedId(UUID.randomUUID());
    sync.setImportUrl(address);
    syncs.save(sync);
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  private int rowsOf(String table, String consultantId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE consultant_id = ?", Integer.class, consultantId);
  }

  private void blockerAt(Instant start, Instant end, Source source) {
    blockerOf(counsellor.getId(), start, end, source);
  }

  private void blockerOf(String owner, Instant start, Instant end, Source source) {
    var blocker = new CalendarBlocker();
    blocker.setConsultantId(owner);
    blocker.setTenantId(TENANT);
    blocker.setStartTime(start);
    blocker.setEndTime(end);
    blocker.setSource(source);
    blocker.setNote(source == Source.ORISO ? "Private note" : null);
    blocker.setCreateDate(Instant.now());
    blockers.save(blocker);
  }

  private static byte[] calendar(String... events) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Synthetic//Test//EN\r\n"
            + String.join("", events)
            + "END:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String event(Instant start, Instant end) {
    return "BEGIN:VEVENT\r\nUID:"
        + UUID.randomUUID()
        + "\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Private matter\r\nDTSTART:"
        + ICS_TIME.format(start)
        + "\r\nDTEND:"
        + ICS_TIME.format(end)
        + "\r\nEND:VEVENT\r\n";
  }

  private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private Instant in(int hours) {
    return Instant.now().truncatedTo(ChronoUnit.HOURS).plus(hours, ChronoUnit.HOURS);
  }

  private void actAsCounsellor() {
    Tenants.actAs(caller, counsellor.getId(), TENANT, UserRole.CONSULTANT);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.CONSULTANT_DEFAULT));
  }
}
