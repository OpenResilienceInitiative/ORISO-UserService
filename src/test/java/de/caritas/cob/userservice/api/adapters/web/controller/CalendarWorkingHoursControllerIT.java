package de.caritas.cob.userservice.api.adapters.web.controller;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.calendar.CalendarPolicyResolver;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Working hours (ADR-032, addenda v1.1 and v1.2) through the real security chain, controller,
 * service and database; caller staged. Configured like {@link CalendarControllerIT} to share its
 * context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true", "feature.appointment.enabled=false"})
@Import(TenantFixtures.class)
@WithTenant(CalendarWorkingHoursControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.USER_DEFAULT})
class CalendarWorkingHoursControllerIT {

  static final long TENANT = 1L;
  private static final long AGENCY = 9841L;
  private static final String PATH = "/users/calendar/working-hours";
  private static final String EXCEPTIONS = PATH + "/exceptions/";
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private CalendarWorkingHoursRepository workingHours;
  @Autowired private CalendarWorkingHoursSettingRepository settings;
  @Autowired private CalendarWorkingDayExceptionRepository exceptions;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean AppointmentService appointmentService;
  @MockitoBean CalendarPolicyResolver policies;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant counsellor;
  private Consultant colleague;
  private User asker;
  private LocalDate today;

  @BeforeEach
  void seed() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    counsellor = fixtures.consultant(TENANT, AGENCY);
    colleague = fixtures.consultant(TENANT, AGENCY);
    asker = fixtures.adviceSeeker(TENANT);
    today = LocalDate.now(ZoneId.of("Europe/Berlin"));
  }

  @AfterEach
  void removeSeededRows() {
    Tenants.acrossAll(
        () -> {
          workingHours.deleteAll();
          settings.deleteAll();
          exceptions.deleteAll();
        });
    fixtures.removeAll();
  }

  @Test
  void workingHours_Should_BeEmpty_Until_TheCounsellorSetsThem() throws Exception {
    actAsCounsellor(counsellor);

    assertThat(body(get(PATH))).isEqualTo(hours(List.of()));
  }

  @Test
  void put_Should_ReplaceAllWindows_AndGetReturnsThemInOrder() throws Exception {
    actAsCounsellor(counsellor);

    var stored =
        body(
            set(
                window(5, "08:00", "14:00"),
                window(1, "13:00", "20:00"),
                window(1, "07:00", "12:00")));
    var expected =
        hours(
            List.of(
                window(1, "07:00", "12:00"),
                window(1, "13:00", "20:00"),
                window(5, "08:00", "14:00")));
    assertThat(stored).isEqualTo(expected);
    assertThat(body(get(PATH))).isEqualTo(expected);

    body(set(window(3, "09:30", "16:45")));
    assertThat(body(get(PATH))).isEqualTo(hours(List.of(window(3, "09:30", "16:45"))));

    body(set());
    assertThat(body(get(PATH))).isEqualTo(hours(List.of()));
  }

  @Test
  void workingHours_Should_BelongToTheirCounsellorOnly() throws Exception {
    actAsCounsellor(counsellor);
    body(set(window(1, "07:00", "20:00")));

    actAsCounsellor(colleague);
    assertThat(body(get(PATH))).isEqualTo(hours(List.of()));
    body(set(window(2, "10:00", "11:00")));

    actAsCounsellor(counsellor);
    assertThat(body(get(PATH))).isEqualTo(hours(List.of(window(1, "07:00", "20:00"))));
  }

  @Test
  void put_Should_RefuseInvalidWindows_AndKeepTheStoredOnes() throws Exception {
    actAsCounsellor(counsellor);
    body(setHours(Map.of("windows", List.of(window(1, "07:00", "20:00")), "holidayRegion", "BY")));
    var fifteen = new ArrayList<Map<String, Object>>();
    for (var i = 0; i < 15; i++) {
      fifteen.add(window(i % 7, "08:00", "09:00"));
    }

    for (var invalid :
        List.<Object>of(
            Map.of("windows", fifteen),
            Map.of("windows", List.of(window(1, "09:00", "09:00"))),
            Map.of("windows", List.of(window(1, "17:00", "08:00"))),
            Map.of("windows", List.of(window(7, "08:00", "17:00"))),
            Map.of("windows", List.of(window(-1, "08:00", "17:00"))),
            Map.of("windows", List.of(window(1, "8:00", "17:00"))),
            Map.of("windows", List.of(window(1, "08:00", "24:00"))),
            Map.of("windows", List.of(Map.of("weekday", 1, "start", "08:00"))),
            Map.of("windows", List.of(), "holidayRegion", "XX"),
            Map.of("windows", List.of(), "holidayRegion", "by"),
            Map.of("windows", List.of(), "holidayRegion", 1),
            Map.of())) {
      mockMvc.perform(setHours(invalid)).andExpect(status().isBadRequest());
    }
    assertThat(body(get(PATH)))
        .isEqualTo(hours(List.of(window(1, "07:00", "20:00")), "BY", List.of()));
  }

  @Test
  void put_Should_StoreTheHolidayRegion_AndClearItWhenAbsentOrNull() throws Exception {
    actAsCounsellor(counsellor);
    var monday = List.of(window(1, "08:00", "17:00"));

    assertThat(body(setHours(Map.of("windows", monday, "holidayRegion", "BY"))))
        .isEqualTo(hours(monday, "BY", List.of()));
    assertThat(body(get(PATH))).isEqualTo(hours(monday, "BY", List.of()));

    var tuesday = List.of(window(2, "09:00", "12:00"));
    body(setHours(Map.of("windows", tuesday, "holidayRegion", "BY")));
    assertThat(body(get(PATH))).isEqualTo(hours(tuesday, "BY", List.of()));

    body(setHours(Map.of("windows", tuesday)));
    assertThat(body(get(PATH))).isEqualTo(hours(tuesday));

    body(setHours(Map.of("windows", tuesday, "holidayRegion", "NW")));
    var explicitNull = new HashMap<String, Object>();
    explicitNull.put("windows", tuesday);
    explicitNull.put("holidayRegion", null);
    assertThat(body(setHours(explicitNull))).isEqualTo(hours(tuesday));
    assertThat(body(get(PATH))).isEqualTo(hours(tuesday));
  }

  @Test
  void put_Should_IgnoreTheExceptionsInTheBody_AndGiveTheStoredOnes() throws Exception {
    actAsCounsellor(counsellor);
    var kept = today.plusDays(1).toString();
    body(setException(kept));

    var result =
        body(
            setHours(
                Map.of(
                    "windows",
                    List.of(),
                    "exceptions",
                    List.of(
                        exception(today.plusDays(2).toString(), dayWindow("08:00", "09:00"))))));

    assertThat(result).isEqualTo(hours(List.of(), null, List.of(exception(kept))));
    assertThat(body(get(PATH))).isEqualTo(result);
  }

  @Test
  void exceptions_Should_StartAt62DaysAgo_InDateOrder_WithTheirWindowsByStart() throws Exception {
    actAsCounsellor(counsellor);
    body(
        setException(
            today.plusDays(5).toString(),
            dayWindow("13:00", "17:00"),
            dayWindow("08:00", "12:00")));
    body(setException(today.plusDays(1).toString()));
    body(setException(today.minusDays(62).toString(), dayWindow("10:00", "11:00")));
    // Written when it was still in the window: kept until the next PUT, but no longer shown.
    Tenants.acrossAll(() -> exceptions.save(dayOff(counsellor.getId(), today.minusDays(63))));

    assertThat(body(get(PATH)).get("exceptions"))
        .isEqualTo(
            List.of(
                exception(today.minusDays(62).toString(), dayWindow("10:00", "11:00")),
                exception(today.plusDays(1).toString()),
                exception(
                    today.plusDays(5).toString(),
                    dayWindow("08:00", "12:00"),
                    dayWindow("13:00", "17:00"))));
  }

  @Test
  void putException_Should_CreateReplaceAndMakeADayOff_AndDeleteFallsBack() throws Exception {
    actAsCounsellor(counsellor);
    var date = today.plusDays(3).toString();

    assertThat(body(setException(date, dayWindow("13:00", "18:00"), dayWindow("08:00", "12:00"))))
        .isEqualTo(exception(date, dayWindow("08:00", "12:00"), dayWindow("13:00", "18:00")));
    assertThat(body(setException(date, dayWindow("10:00", "14:00"))))
        .isEqualTo(exception(date, dayWindow("10:00", "14:00")));
    assertThat(body(get(PATH)).get("exceptions"))
        .isEqualTo(List.of(exception(date, dayWindow("10:00", "14:00"))));

    assertThat(body(setException(date))).isEqualTo(exception(date));
    assertThat(body(get(PATH)).get("exceptions")).isEqualTo(List.of(exception(date)));
    assertThat(Tenants.acrossAll(() -> exceptions.findAll())).hasSize(1);

    mockMvc.perform(removeException(date)).andExpect(status().isNoContent());
    assertThat(body(get(PATH))).isEqualTo(hours(List.of()));
    mockMvc.perform(removeException(date)).andExpect(status().isNoContent());
    assertThat(Tenants.acrossAll(() -> exceptions.findAll())).isEmpty();
  }

  @Test
  void putException_Should_AcceptTheDatesGetShows_From62DaysAgoTo400DaysAhead() throws Exception {
    actAsCounsellor(counsellor);
    var first = today.minusDays(62).toString();
    var last = today.plusDays(400).toString();

    assertThat(body(setException(first))).isEqualTo(exception(first));
    assertThat(body(setException(last))).isEqualTo(exception(last));
    mockMvc
        .perform(setException(today.minusDays(63).toString()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(setException(today.plusDays(401).toString()))
        .andExpect(status().isBadRequest());

    assertThat(body(get(PATH)).get("exceptions"))
        .isEqualTo(List.of(exception(first), exception(last)));
    assertThat(storedDates()).containsExactlyInAnyOrder(today.minusDays(62), today.plusDays(400));
  }

  @Test
  void removeException_Should_Answer204_ForAnyDate_AndRemoveAnOldOne() throws Exception {
    actAsCounsellor(counsellor);
    Tenants.acrossAll(() -> exceptions.save(dayOff(counsellor.getId(), today.minusDays(63))));

    for (var date : List.of(today.minusDays(63), today.minusDays(500), today.plusDays(401))) {
      mockMvc.perform(removeException(date.toString())).andExpect(status().isNoContent());
    }
    assertThat(storedDates()).isEmpty();
  }

  @Test
  void putException_Should_RefuseInvalidWindowsAndDates_AndKeepTheStoredOnes() throws Exception {
    actAsCounsellor(counsellor);
    var date = today.plusDays(2).toString();
    body(setException(date, dayWindow("08:00", "12:00")));
    var five = new ArrayList<Map<String, Object>>();
    for (var i = 0; i < 5; i++) {
      five.add(dayWindow("0" + i + ":00", "0" + i + ":30"));
    }

    for (var invalid :
        List.<Object>of(
            Map.of("windows", five),
            Map.of("windows", List.of(dayWindow("09:00", "09:00"))),
            Map.of("windows", List.of(dayWindow("17:00", "08:00"))),
            Map.of("windows", List.of(dayWindow("08:00", "12:00"), dayWindow("11:00", "13:00"))),
            Map.of("windows", List.of(dayWindow("13:00", "17:00"), dayWindow("08:00", "14:00"))),
            Map.of("windows", List.of(dayWindow("8:00", "12:00"))),
            Map.of("windows", List.of(dayWindow("08:00", "24:00"))),
            Map.of("windows", List.of(Map.of("start", "08:00"))),
            Map.of())) {
      mockMvc
          .perform(
              csrf(put(EXCEPTIONS + date))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(invalid)))
          .andExpect(status().isBadRequest());
    }
    for (var notADate : List.of("2026-13-01", "2026-02-30", "tomorrow", "16.11.2026")) {
      mockMvc.perform(setException(notADate)).andExpect(status().isBadRequest());
      mockMvc.perform(removeException(notADate)).andExpect(status().isBadRequest());
    }
    assertThat(body(get(PATH)).get("exceptions"))
        .isEqualTo(List.of(exception(date, dayWindow("08:00", "12:00"))));
  }

  @Test
  void putException_Should_Refuse401stDate_ButReplaceAKeptOne() throws Exception {
    actAsCounsellor(counsellor);
    var rows = new ArrayList<CalendarWorkingDayException>();
    for (var i = 0; i < 400; i++) {
      rows.add(dayOff(counsellor.getId(), today.minusDays(62).plusDays(i)));
    }
    Tenants.acrossAll(() -> exceptions.saveAll(rows));
    var next = today.minusDays(62).plusDays(400).toString();

    mockMvc.perform(setException(next)).andExpect(status().isConflict());
    assertThat(body(setException(today.toString(), dayWindow("08:00", "09:00"))))
        .isEqualTo(exception(today.toString(), dayWindow("08:00", "09:00")));
    assertThat((List<?>) body(get(PATH)).get("exceptions")).hasSize(400);

    mockMvc.perform(removeException(today.toString())).andExpect(status().isNoContent());
    body(setException(next));
    assertThat(Tenants.acrossAll(() -> exceptions.countDatesByConsultantId(counsellor.getId())))
        .isEqualTo(400);

    actAsCounsellor(colleague);
    body(setException(next));
  }

  @Test
  void putException_Should_LetOnlyOneOfSeveralParallelNewDates_PassTheLimit() throws Exception {
    actAsCounsellor(counsellor);
    var rows = new ArrayList<CalendarWorkingDayException>();
    for (var i = 0; i < 399; i++) {
      rows.add(dayOff(counsellor.getId(), today.minusDays(62).plusDays(i)));
    }
    Tenants.acrossAll(() -> exceptions.saveAll(rows));
    var parallel = 6;
    var security = TestSecurityContextHolder.getContext();
    var start = new CountDownLatch(1);
    var statuses = new ArrayList<Integer>();
    try (var pool = Executors.newFixedThreadPool(parallel)) {
      var answers = new ArrayList<Future<Integer>>();
      for (var i = 0; i < parallel; i++) {
        var request =
            setException(today.plusDays(340 + i).toString()).with(securityContext(security));
        answers.add(
            pool.submit(
                () -> {
                  start.await();
                  return mockMvc.perform(request).andReturn().getResponse().getStatus();
                }));
      }
      start.countDown();
      for (var answer : answers) {
        statuses.add(answer.get(30, SECONDS));
      }
    }

    var expected = new ArrayList<Integer>(List.of(200));
    while (expected.size() < parallel) {
      expected.add(409);
    }
    assertThat(statuses).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(Tenants.acrossAll(() -> exceptions.countDatesByConsultantId(counsellor.getId())))
        .isEqualTo(400);
  }

  @Test
  void putException_Should_DropExceptionsOlderThan62Days_OnTheNextPut() throws Exception {
    actAsCounsellor(counsellor);
    Tenants.acrossAll(
        () ->
            exceptions.saveAll(
                List.of(
                    dayOff(counsellor.getId(), today.minusDays(63)),
                    dayOff(counsellor.getId(), today.minusDays(62)))));

    body(setException(today.plusDays(1).toString()));

    assertThat(storedDates()).containsExactlyInAnyOrder(today.minusDays(62), today.plusDays(1));
  }

  @Test
  void putException_Should_CountOnlyTheDatesGetShows_AgainstTheLimit() throws Exception {
    actAsCounsellor(counsellor);
    var rows = new ArrayList<CalendarWorkingDayException>();
    for (var i = 0; i < 300; i++) {
      rows.add(dayOff(counsellor.getId(), today.minusDays(63 + i)));
    }
    for (var i = 0; i < 100; i++) {
      rows.add(dayOff(counsellor.getId(), today.plusDays(i)));
    }
    Tenants.acrossAll(() -> exceptions.saveAll(rows));

    body(setException(today.plusDays(100).toString()));

    assertThat((List<?>) body(get(PATH)).get("exceptions")).hasSize(101);
    assertThat(storedDates()).hasSize(101);
  }

  @Test
  void exceptions_Should_BelongToTheirCounsellorOnly() throws Exception {
    var date = today.plusDays(4).toString();
    actAsCounsellor(counsellor);
    body(setException(date, dayWindow("08:00", "12:00")));

    actAsCounsellor(colleague);
    assertThat(body(get(PATH))).isEqualTo(hours(List.of()));
    mockMvc.perform(removeException(date)).andExpect(status().isNoContent());
    body(setException(date));

    actAsCounsellor(counsellor);
    assertThat(body(get(PATH)).get("exceptions"))
        .isEqualTo(List.of(exception(date, dayWindow("08:00", "12:00"))));
    actAsCounsellor(colleague);
    assertThat(body(get(PATH)).get("exceptions")).isEqualTo(List.of(exception(date)));
  }

  @Test
  void asker_Should_NotReachWorkingHours() throws Exception {
    actAsAsker(asker);
    var date = today.plusDays(1).toString();

    mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
    mockMvc.perform(set(window(1, "08:00", "17:00"))).andExpect(status().isForbidden());
    mockMvc.perform(setException(date)).andExpect(status().isForbidden());
    mockMvc.perform(removeException(date)).andExpect(status().isForbidden());
    assertThat(workingHours.findAll()).isEmpty();
    assertThat(Tenants.acrossAll(() -> exceptions.findAll())).isEmpty();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void askerAuthority_Should_BeForbidden_OnBothPaths() throws Exception {
    actAsAsker(asker);
    var date = today.plusDays(1).toString();

    mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
    mockMvc.perform(get("/service" + PATH)).andExpect(status().isForbidden());
    mockMvc.perform(set(window(1, "08:00", "17:00"))).andExpect(status().isForbidden());
    mockMvc
        .perform(
            csrf(put("/service" + PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("windows", List.of()))))
        .andExpect(status().isForbidden());
    mockMvc.perform(setException(date)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            csrf(put("/service" + EXCEPTIONS + date))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("windows", List.of()))))
        .andExpect(status().isForbidden());
    mockMvc.perform(removeException(date)).andExpect(status().isForbidden());
    mockMvc.perform(csrf(delete("/service" + EXCEPTIONS + date))).andExpect(status().isForbidden());
  }

  @Test
  @WithAnonymousUser
  void unauthenticatedCaller_Should_BeUnauthorized() throws Exception {
    var date = LocalDate.now(ZoneId.of("Europe/Berlin")).toString();

    mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mockMvc.perform(set(window(1, "08:00", "17:00"))).andExpect(status().isUnauthorized());
    mockMvc.perform(setException(date)).andExpect(status().isUnauthorized());
    mockMvc.perform(removeException(date)).andExpect(status().isUnauthorized());
  }

  private static Map<String, Object> window(int weekday, String start, String end) {
    return Map.of("weekday", weekday, "start", start, "end", end);
  }

  private static Map<String, Object> dayWindow(String start, String end) {
    return Map.of("start", start, "end", end);
  }

  @SafeVarargs
  private static Map<String, Object> exception(String date, Map<String, Object>... windows) {
    return Map.of("date", date, "windows", List.of(windows));
  }

  private static Map<String, Object> hours(List<Map<String, Object>> windows) {
    return hours(windows, null, List.of());
  }

  private static Map<String, Object> hours(
      List<Map<String, Object>> windows, String region, List<Map<String, Object>> exceptions) {
    var body = new HashMap<String, Object>();
    body.put("windows", windows);
    body.put("exceptions", exceptions);
    if (region != null) {
      body.put("holidayRegion", region);
    }
    return body;
  }

  private List<LocalDate> storedDates() {
    var dates = new ArrayList<LocalDate>();
    Tenants.acrossAll(() -> exceptions.findAll()).forEach(row -> dates.add(row.getExceptionDate()));
    return dates;
  }

  private static CalendarWorkingDayException dayOff(String consultantId, LocalDate date) {
    var row = new CalendarWorkingDayException();
    row.setConsultantId(consultantId);
    row.setExceptionDate(date);
    return row;
  }

  @SafeVarargs
  private MockHttpServletRequestBuilder set(Map<String, Object>... windows) throws Exception {
    return setHours(Map.of("windows", List.of(windows)));
  }

  private MockHttpServletRequestBuilder setHours(Object body) throws Exception {
    return csrf(put(PATH)).contentType(MediaType.APPLICATION_JSON).content(json(body));
  }

  @SafeVarargs
  private MockHttpServletRequestBuilder setException(String date, Map<String, Object>... windows)
      throws Exception {
    return csrf(put(EXCEPTIONS + date))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("windows", List.of(windows))));
  }

  private MockHttpServletRequestBuilder removeException(String date) {
    return csrf(delete(EXCEPTIONS + date));
  }

  private static String json(Object value) throws Exception {
    return JSON.writeValueAsString(value);
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

  private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private void actAsCounsellor(Consultant consultant) {
    Tenants.actAs(caller, consultant.getId(), consultant.getTenantId(), UserRole.CONSULTANT);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.CONSULTANT_DEFAULT));
  }

  private void actAsAsker(User user) {
    Tenants.actAs(caller, user.getUserId(), TENANT, UserRole.USER);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.USER_DEFAULT));
  }
}
