package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.RESTRICTED_AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.StatisticsDashboardSnapshot;
import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.GroupCountProjection;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.StatisticsDashboardSnapshotRepository;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingChangeRepository;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStandRefresh;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStandService;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardScope;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardStatistics;
import de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSnapshotInterval;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.statistics.AdminDashboardSnapshotScheduler;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Differencing protection over the real HTTP path, database and lease; only the aggregate queries
 * are stubbed, so a "new case" is one number going up. Admin roles get a fixed stand, whatever they
 * call; a counsellor's own numbers stay live.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class AdminDashboardStandIT {

  private static final String DASHBOARD = "/useradmin/statistics/dashboard";
  private static final String SETTINGS = "/useradmin/statistics/settings";
  // The identity the token post-processor uses; other counsellors must never be queried.
  private static final String TEST_IDENTITY = "user";
  private static final long TENANT = 5L;
  private static final long AGENCY = 11L;
  private static final long OTHER_AGENCY = 12L;
  // Wednesday 11:00 in Berlin: today's stand is from 06:00 Berlin time, this week's from Monday.
  private static final Instant WEDNESDAY = Instant.parse("2026-11-04T10:00:00Z");

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    MovableClock movableClock() {
      return new MovableClock();
    }
  }

  static class MovableClock extends Clock {
    private Instant now = WEDNESDAY;

    void moveTo(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private MovableClock clock;
  @Autowired private StatisticsDashboardSnapshotRepository stands;
  @Autowired private StatisticsSettingChangeRepository settings;
  @Autowired private ScheduledTaskClaimService claims;
  @Autowired private AdminDashboardStandRefresh refresh;
  @Autowired private AdminDashboardStandService standService;

  @MockitoBean private AdminStatisticsRepository statistics;
  @MockitoBean private AdminAgencyRepository adminAgencies;
  @MockitoBean private ConsultantStatisticsRepository consultantStatistics;
  @MockitoBean private ConsultantMessageStatService consultantMessages;

  private long cases;
  private long counsellors;

  @BeforeEach
  void setUp() {
    stands.deleteAll();
    settings.deleteAll();
    clock.moveTo(WEDNESDAY);
    cases = 23;
    counsellors = 8;
    when(statistics.countConsultantsByTenant()).thenAnswer(call -> group(TENANT, counsellors));
    when(statistics.countNewSessionsByTenant(any(), any()))
        .thenAnswer(call -> group(TENANT, cases));
    when(statistics.countConsultantsForTenant(anyLong())).thenAnswer(call -> counsellors);
    when(statistics.countConsultantsByAgency(anyLong()))
        .thenAnswer(
            call ->
                List.of(groupCount(AGENCY, counsellors), groupCount(OTHER_AGENCY, counsellors)));
    when(statistics.countNewSessionsByAgency(anyLong(), any(), any()))
        .thenAnswer(call -> group(AGENCY, cases));
    when(adminAgencies.findByAdminId(anyString()))
        .thenReturn(List.of(AdminAgency.builder().agencyId(AGENCY).build()));
  }

  /* ---------- the fixed stand ---------- */

  @Test
  void everyAdminRole_Should_SeeTheSameStand_WhenANewCaseAppearsInBetween() throws Exception {
    for (var role : List.of(platformAdmin(), tenantAdmin(), agencyAdmin())) {
      cases = 23;
      var first = dashboard(role);

      cases = 24;
      clock.moveTo(WEDNESDAY.plus(Duration.ofMinutes(5)));
      var second = dashboard(role);

      assertThat(enquiries(first)).as("first request").isEqualTo(23);
      assertThat(second).as("the whole answer stays the same").isEqualTo(first);
      clock.moveTo(WEDNESDAY);
    }
  }

  @Test
  void theStand_Should_SayWhenItWasCalculated_AndHowOftenItChanges() throws Exception {
    var body = dashboard(tenantAdmin());

    assertThat(JsonPath.<String>read(body, "$.snapshotInterval")).isEqualTo("DAILY");
    assertThat(generatedAt(body)).isEqualTo(WEDNESDAY);
  }

  @Test
  void theStand_Should_BeStoredInTheDatabase_AndCalculatedOnlyOnce() throws Exception {
    dashboard(tenantAdmin());
    dashboard(tenantAdmin());

    assertThat(stands.findAll()).hasSize(1);
    assertThat(stands.findAll().getFirst().getCutoffAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 4, 5, 0));
    verify(statistics, times(1)).countConsultantsForTenant(TENANT);
  }

  @Test
  void theNextRequest_Should_GetTheNewStand_OnlyAfterTheCutoff() throws Exception {
    dashboard(tenantAdmin());
    cases = 30;

    clock.moveTo(Instant.parse("2026-11-05T04:59:59Z"));
    assertThat(enquiries(dashboard(tenantAdmin()))).as("one second before").isEqualTo(23);

    clock.moveTo(Instant.parse("2026-11-05T05:00:00Z"));
    assertThat(enquiries(dashboard(tenantAdmin()))).as("at the cutoff").isEqualTo(30);
  }

  @Test
  void aWeeklyStand_Should_HoldUntilNextMonday() throws Exception {
    setInterval("tenantSnapshotInterval", "WEEKLY");
    dashboard(tenantAdmin());
    cases = 30;

    clock.moveTo(Instant.parse("2026-11-09T04:59:59Z"));
    assertThat(enquiries(dashboard(tenantAdmin()))).as("Monday before 06:00").isEqualTo(23);

    clock.moveTo(Instant.parse("2026-11-09T05:00:00Z"));
    assertThat(enquiries(dashboard(tenantAdmin()))).as("Monday 06:00").isEqualTo(30);
  }

  @Test
  void aNewStand_Should_ReplaceTheOldOne_SoNoHistoryPilesUp() throws Exception {
    dashboard(tenantAdmin());

    clock.moveTo(Instant.parse("2026-11-05T05:00:00Z"));
    dashboard(tenantAdmin());

    assertThat(stands.findAll()).hasSize(1);
    assertThat(stands.findAll().getFirst().getCutoffAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 5, 5, 0));
  }

  @Test
  void aFailedCalculation_Should_ServeTheLastStand_NeverLiveNumbers() throws Exception {
    dashboard(tenantAdmin());

    clock.moveTo(Instant.parse("2026-11-05T05:00:00Z"));
    cases = 30;
    when(statistics.countConsultantsForTenant(anyLong())).thenThrow(new IllegalStateException());

    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(23);
  }

  @Test
  void aFailedFirstCalculation_Should_FailTheRequest_NeverAnswerEmpty() throws Exception {
    when(statistics.countConsultantsForTenant(anyLong())).thenThrow(new IllegalStateException());

    mvc.perform(get(DASHBOARD).with(tenantAdmin())).andExpect(status().is5xxServerError());
  }

  /* ---------- no way around the stand ---------- */

  @Test
  void aDirectApiCall_Should_NotBypassTheStand() throws Exception {
    dashboard(tenantAdmin());
    cases = 24;

    for (var path : List.of(DASHBOARD, "/service" + DASHBOARD)) {
      var body =
          mvc.perform(
                  get(path)
                      .param("refresh", "true")
                      .param("live", "true")
                      .header("Cache-Control", "no-cache")
                      .with(tenantAdmin()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(enquiries(body)).as(path).isEqualTo(23);
    }
    verify(statistics, times(1)).countConsultantsForTenant(TENANT);
  }

  @Test
  void aCounsellor_Should_SeeANewCaseAtOnce_AndNeverTouchAStand() throws Exception {
    when(consultantStatistics.countAssignedSessionsCreatedInPeriod(any(), any(), any(), any()))
        .thenReturn(23L, 24L);
    var counsellor =
        jwt()
            .authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT))
            .jwt(token -> token.claim("tenantId", TENANT));

    assertThat(ownAssignedSessions(counsellor)).isEqualTo(23);
    assertThat(ownAssignedSessions(counsellor)).isEqualTo(24);
    assertThat(stands.findAll()).isEmpty();
    verify(consultantStatistics, times(2))
        .countAssignedSessionsCreatedInPeriod(eq(TEST_IDENTITY), any(), any(), any());
    verify(consultantStatistics, never())
        .countAssignedSessionsCreatedInPeriod(
            argThat(id -> !TEST_IDENTITY.equals(id)), any(), any(), any());
  }

  @Test
  void aCounsellor_Should_NotReachTheAdminDashboard() throws Exception {
    mvc.perform(
            get(DASHBOARD).with(jwt().authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT))))
        .andExpect(status().isForbidden());
  }

  /* ---------- the value "live" ---------- */

  @Test
  void live_Should_BehaveExactlyAsBefore() throws Exception {
    setInterval("tenantSnapshotInterval", "LIVE");

    var first = dashboard(tenantAdmin());
    cases = 24;
    clock.moveTo(WEDNESDAY.plus(Duration.ofMinutes(5)));
    var second = dashboard(tenantAdmin());

    assertThat(enquiries(first)).isEqualTo(23);
    assertThat(enquiries(second)).isEqualTo(24);
    assertThat(generatedAt(second)).isEqualTo(WEDNESDAY.plus(Duration.ofMinutes(5)));
    assertThat(JsonPath.<String>read(second, "$.snapshotInterval")).isEqualTo("LIVE");
    assertThat(stands.findAll()).isEmpty();
    verify(statistics, times(2)).countConsultantsForTenant(TENANT);
  }

  /* ---------- one field per role ---------- */

  @Test
  void eachRole_Should_FollowItsOwnField() throws Exception {
    setInterval("agencySnapshotInterval", "LIVE");
    setInterval("tenantSnapshotInterval", "DAILY");
    setInterval("platformSnapshotInterval", "WEEKLY");
    assertThat(interval(agencyAdmin())).isEqualTo("LIVE");
    assertThat(interval(tenantAdmin())).isEqualTo("DAILY");
    assertThat(interval(platformAdmin())).isEqualTo("WEEKLY");
  }

  @Test
  void anAgencyAdmin_Should_SeeOnlyTheirOwnAgency_FromTheSharedStand() throws Exception {
    var body = dashboard(agencyAdmin());
    assertThat(JsonPath.<List<Integer>>read(body, "$.targets[*].agencyId"))
        .containsExactly((int) AGENCY);

    when(adminAgencies.findByAdminId(anyString()))
        .thenReturn(List.of(AdminAgency.builder().agencyId(OTHER_AGENCY).build()));
    assertThat(JsonPath.<List<Integer>>read(dashboard(agencyAdmin()), "$.targets[*].agencyId"))
        .as("a removed assignment applies at once")
        .containsExactly((int) OTHER_AGENCY);
    assertThat(stands.findAll()).hasSize(1);
  }

  /* ---------- changing the setting ---------- */

  @Test
  void switchingToLive_Should_ApplyAtOnce() throws Exception {
    dashboard(tenantAdmin());
    cases = 24;

    saveSetting("tenantSnapshotInterval", "LIVE").andExpect(status().isOk());

    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(24);
  }

  @Test
  void switchingFromLive_Should_CreateAStandOnTheFirstRequest() throws Exception {
    setInterval("tenantSnapshotInterval", "LIVE");
    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(23);
    assertThat(stands.findAll()).isEmpty();

    saveSetting("tenantSnapshotInterval", "WEEKLY").andExpect(status().isOk());
    cases = 24;

    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(24);
    cases = 25;
    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(24);
    assertThat(stands.findAll()).hasSize(1);
  }

  @Test
  void aChange_Should_LeaveAProtocolLine() throws Exception {
    saveSetting("platformSnapshotInterval", "WEEKLY").andExpect(status().isOk());

    var history =
        mvc.perform(get(SETTINGS + "/history").with(platformAdmin()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(history, "$[*].key"))
        .containsExactly("platformSnapshotInterval");
    assertThat(JsonPath.<List<String>>read(history, "$[*].oldValue")).containsExactly("DAILY");
    assertThat(JsonPath.<List<String>>read(history, "$[*].newValue")).containsExactly("WEEKLY");
  }

  @Test
  void onlyThePlatformAdmin_Should_ChangeTheSetting() throws Exception {
    for (var role : List.of(tenantAdmin(), agencyAdmin())) {
      mvc.perform(settingsUpdate("platformSnapshotInterval", "LIVE").with(role))
          .andExpect(status().isForbidden());
    }
    assertThat(settings.findAll()).isEmpty();
  }

  @Test
  void anInvalidInterval_Should_BeRejected() throws Exception {
    saveSetting("platformSnapshotInterval", "HOURLY").andExpect(status().isBadRequest());
  }

  /* ---------- thresholds on the stored stand ---------- */

  @Test
  void aStoredStand_Should_HoldNoNumbersBelowTheThreshold() throws Exception {
    counsellors = 3;

    var body = dashboard(tenantAdmin());

    assertThat(JsonPath.<List<Boolean>>read(body, "$.targets[*].suppressed")).containsOnly(true);
    assertThat(stands.findAll().getFirst().getPayload())
        .contains("\"suppressed\":true")
        .doesNotContain("enquiriesCurrentMonth");
  }

  @Test
  void raisingAThreshold_Should_ApplyToTheStoredStandAtOnce() throws Exception {
    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(23);

    setSetting("tenantMinimumCounsellors", "10");
    setSetting("agencyMinimumCounsellors", "10");

    var body = dashboard(tenantAdmin());
    assertThat(JsonPath.<List<Boolean>>read(body, "$.targets[*].suppressed")).containsOnly(true);
    assertThat(JsonPath.<List<Object>>read(body, "$.targets[*].metrics")).containsOnlyNulls();
    assertThat(JsonPath.<List<Integer>>read(body, "$.targets[*].counselorCount")).containsOnly(8);
  }

  @Test
  void loweringAThreshold_Should_NotRevealNumbers_BeforeTheNextStand() throws Exception {
    counsellors = 3;
    dashboard(tenantAdmin());

    setSetting("tenantMinimumCounsellors", "2");
    setSetting("agencyMinimumCounsellors", "2");

    var body = dashboard(tenantAdmin());
    assertThat(JsonPath.<List<Boolean>>read(body, "$.targets[*].suppressed")).containsOnly(true);
  }

  /* ---------- several instances ---------- */

  @Test
  void theScheduler_Should_CalculateEveryStandOnce_ForEveryRoleAndTenant() throws Exception {
    scheduler().refreshStands();

    assertThat(stands.findAll())
        .extracting(stand -> stand.getScope() + ":" + stand.getTenantId())
        .containsExactlyInAnyOrder("PLATFORM:0", "TENANT:" + TENANT, "AGENCY:" + TENANT);

    clearInvocations(statistics);
    scheduler().refreshStands();
    verify(statistics, never()).countConsultantsForTenant(anyLong());
    assertThat(stands.findAll()).hasSize(3);
  }

  @Test
  void aStandFromTheScheduler_Should_BeServedWithItsNumbersFromThatTime() throws Exception {
    clock.moveTo(Instant.parse("2026-11-04T05:00:00Z"));
    scheduler().refreshStands();

    clock.moveTo(WEDNESDAY);
    cases = 24;

    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(23);
    assertThat(generatedAt(dashboard(tenantAdmin())))
        .isEqualTo(Instant.parse("2026-11-04T05:00:00Z"));
  }

  @Test
  void aSecondInstance_Should_CalculateNothing_WhileTheFirstHoldsTheLease() throws Exception {
    var held =
        claims.tryClaimLease(AdminDashboardSnapshotScheduler.TASK_NAME, Duration.ofMinutes(5));
    assertThat(held).as("the first instance holds the lease").isPresent();

    scheduler().refreshStands();

    verify(statistics, never()).countConsultantsForTenant(anyLong());
    assertThat(stands.findAll()).isEmpty();

    claims.release(held.get());
    scheduler().refreshStands();
    assertThat(stands.findAll()).hasSize(3);
  }

  @Test
  void theScheduler_Should_SkipLiveRoles_AndRemoveTheirOldStands() throws Exception {
    scheduler().refreshStands();

    setInterval("tenantSnapshotInterval", "LIVE");
    scheduler().refreshStands();

    assertThat(stands.findAll())
        .extracting(stand -> stand.getScope())
        .containsExactlyInAnyOrder("PLATFORM", "AGENCY");
  }

  @Test
  void aRequest_Should_NotCalculateASecondTime_WhileAnotherInstanceIsCalculatingIt()
      throws Exception {
    var held = claims.tryClaimLease("statistics-stand:TENANT:" + TENANT, Duration.ofMinutes(5));
    assertThat(held).isPresent();

    mvc.perform(get(DASHBOARD).with(tenantAdmin())).andExpect(status().isServiceUnavailable());
    verify(statistics, never()).countConsultantsForTenant(anyLong());

    claims.release(held.get());
    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(23);
  }

  @Test
  void aRequest_Should_GetTheLastStand_WhileAnotherInstanceIsCalculatingTheNewOne()
      throws Exception {
    dashboard(tenantAdmin());
    clock.moveTo(Instant.parse("2026-11-05T05:00:00Z"));
    cases = 30;
    var held = claims.tryClaimLease("statistics-stand:TENANT:" + TENANT, Duration.ofMinutes(5));
    assertThat(held).isPresent();

    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(23);

    claims.release(held.get());
    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(30);
  }

  @Test
  void aReplicaLosingTheRace_Should_ServeTheStandThatWonIt() throws Exception {
    var winner = dashboard(tenantAdmin());
    var stored = stands.findAll().getFirst();
    stands.deleteAll();
    cases = 99;

    var served =
        standService.currentStand(
            DashboardScope.TENANT,
            TENANT,
            StatisticsSnapshotInterval.DAILY,
            () -> {
              stands.saveAndFlush(
                  StatisticsDashboardSnapshot.builder()
                      .scope(stored.getScope())
                      .tenantId(stored.getTenantId())
                      .snapshotInterval(stored.getSnapshotInterval())
                      .cutoffAt(stored.getCutoffAt())
                      .computedAt(stored.getComputedAt())
                      .payload(stored.getPayload())
                      .build());
              return new DashboardStatistics(
                  "loser", DashboardScope.TENANT, false, 5, 4, null, List.of());
            });

    assertThat(served.generatedAt()).isEqualTo(JsonPath.read(winner, "$.generatedAt"));
    assertThat(stands.findAll()).hasSize(1);
  }

  @Test
  void anUnreadableStand_Should_BeReplaced() throws Exception {
    dashboard(tenantAdmin());
    var stand = stands.findAll().getFirst();
    stand.setPayload("{not json");
    stands.saveAndFlush(stand);
    cases = 24;

    assertThat(enquiries(dashboard(tenantAdmin()))).isEqualTo(24);
    assertThat(stands.findAll()).hasSize(1);
  }

  /* ---------- helpers ---------- */

  private AdminDashboardSnapshotScheduler scheduler() {
    return new AdminDashboardSnapshotScheduler(refresh, claims);
  }

  private String dashboard(JwtRequestPostProcessor role) throws Exception {
    return mvc.perform(get(DASHBOARD).with(role))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String interval(JwtRequestPostProcessor role) throws Exception {
    return JsonPath.read(dashboard(role), "$.snapshotInterval");
  }

  private static Instant generatedAt(String body) {
    return OffsetDateTime.parse(JsonPath.<String>read(body, "$.generatedAt")).toInstant();
  }

  private static int enquiries(String body) {
    return JsonPath.<List<Integer>>read(body, "$.targets[*].metrics.enquiriesCurrentMonth")
        .getFirst();
  }

  private int ownAssignedSessions(JwtRequestPostProcessor counsellor) throws Exception {
    var body =
        mvc.perform(
                get("/users/statistics/consultant")
                    .param("startDate", "2026-11-01")
                    .param("endDate", "2026-11-04")
                    .with(counsellor))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.numberOfAssignedSessions");
  }

  private void setInterval(String key, String value) {
    setSetting(key, value);
  }

  private void setSetting(String key, String value) {
    settings.save(
        StatisticsSettingChange.builder()
            .settingKey(key)
            .newValue(value)
            .effectiveFrom(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .build());
  }

  private ResultActions saveSetting(String key, String value) throws Exception {
    return mvc.perform(settingsUpdate(key, value).with(platformAdmin()));
  }

  private MockHttpServletRequestBuilder settingsUpdate(String key, String value) {
    return put(SETTINGS)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"values\":{\"%s\":\"%s\"},\"revision\":%d}".formatted(key, value, settings.count()))
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }

  private static JwtRequestPostProcessor platformAdmin() {
    return admin(USER_ADMIN, 0L, "agency-admin", "tenant-admin");
  }

  private static JwtRequestPostProcessor tenantAdmin() {
    return admin(TENANT_ADMIN, TENANT, "agency-admin", "tenant-admin");
  }

  private static JwtRequestPostProcessor agencyAdmin() {
    return admin(RESTRICTED_AGENCY_ADMIN, TENANT, "restricted-agency-admin");
  }

  private static JwtRequestPostProcessor admin(String authority, long tenantId, String... roles) {
    return jwt()
        .authorities(new SimpleGrantedAuthority(authority))
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of(roles)))
                    .claim("tenantId", tenantId));
  }

  private static List<GroupCountProjection> group(long id, long total) {
    return List.of(groupCount(id, total));
  }

  private static GroupCountProjection groupCount(long id, long total) {
    return new GroupCountProjection() {
      @Override
      public Long getGroupId() {
        return id;
      }

      @Override
      public Long getTotal() {
        return total;
      }
    };
  }
}
