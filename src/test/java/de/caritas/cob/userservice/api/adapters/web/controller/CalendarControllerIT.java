package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.calendar.CalendarPolicy;
import de.caritas.cob.userservice.api.service.calendar.CalendarPolicyResolver;
import de.caritas.cob.userservice.api.service.calendar.CalendarVisibilityScope;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real security chain, controller, services and database; caller and Träger setting staged. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(CalendarControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.USER_DEFAULT})
class CalendarControllerIT {

  static final long TENANT = 1L;
  private static final long OTHER_TENANT = 2L;
  private static final long AGENCY = 9821L;
  private static final long OTHER_AGENCY = 9822L;
  private static final long FOREIGN_AGENCY = 9823L;
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> COLLEAGUE_FIELDS =
      Set.of("id", "type", "start", "end", "ownerId", "kind", "title", "movable");

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private ConsultantRepository consultants;
  @Autowired private CalendarBlockerRepository blockers;
  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private ChatRepository chats;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean CalendarPolicyResolver policies;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant counsellor;
  private Consultant colleague;
  private Consultant colleagueOfOtherAgency;
  private Consultant counsellorOfOtherTenant;
  private User asker;
  private Session session;
  private final List<Chat> seededChats = new ArrayList<>();

  @BeforeEach
  void seed() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(agencyService.getAgencies(anyList()))
        .thenReturn(List.of(new AgencyDTO().id(AGENCY).name("Synthetic Centre")));
    policy(CalendarVisibilityScope.AGENCY, false);
    counsellor = fixtures.consultant(TENANT, AGENCY);
    colleague = fixtures.consultant(TENANT, AGENCY);
    colleagueOfOtherAgency = fixtures.consultant(TENANT, OTHER_AGENCY);
    counsellorOfOtherTenant = fixtures.consultant(OTHER_TENANT, FOREIGN_AGENCY);
    asker = fixtures.adviceSeeker(TENANT);
    session = fixtures.session(asker, AGENCY, counsellor);
  }

  @AfterEach
  void removeSeededRows() {
    Tenants.acrossAll(
        () -> {
          blockers.deleteAll();
          chats.deleteAll(seededChats);
        });
    fixtures.removeAll();
  }

  @Test
  void appointment_Should_CarryItsKind_And_KeepItOnAPlainMove() throws Exception {
    actAsCounsellor(counsellor);
    var id = appointment("PHONE", in(24), in(25));

    var moved =
        mockMvc
            .perform(
                csrf(put("/users/sessions/" + session.getId() + "/appointments/" + id))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(times(in(26), in(27)))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JSON.readValue(moved, Map.class).get("kind")).isEqualTo("PHONE");
    assertThat(list(get("/users/appointments/upcoming")).get(0).get("kind")).isEqualTo("PHONE");
    var legacy = appointment(null, in(30), in(31));
    assertThat(list(get("/users/appointments/upcoming")))
        .filteredOn(row -> row.get("id").equals(legacy))
        .hasSize(1)
        .allMatch(row -> row.get("kind") == null);
  }

  @Test
  void ownEntries_Should_ShowAppointmentsGroupsAndBlockers() throws Exception {
    actAsCounsellor(counsellor);
    var appointmentId = appointment("VIDEO", in(24), in(25));
    var chat = groupChatOf(counsellor, in(48));
    var blockerId = (String) createBlocker(in(50), in(51), "Lunch", null).get(0).get("id");
    busyTimeOf(counsellor, in(60), in(61));

    var entries = list(entries(in(0), in(24 * 30)));

    assertThat(entries)
        .extracting(row -> row.get("type"))
        .containsExactly("APPOINTMENT", "GROUP", "BLOCKER", "BUSY");
    assertThat(entries).allMatch(row -> row.get("ownerId").equals(counsellor.getId()));
    var appointment = entries.get(0);
    assertThat(appointment.get("id")).isEqualTo(appointmentId);
    assertThat(appointment.get("kind")).isEqualTo("VIDEO");
    assertThat(appointment.get("sessionId")).isEqualTo(session.getId().intValue());
    assertThat(appointment.get("title")).isEqualTo(asker.getUsername());
    assertThat(appointment.get("movable")).isEqualTo(true);
    assertThat(entries.get(1).get("id")).isEqualTo("chat-" + chat.getId() + "-0");
    assertThat(entries.get(1).get("title")).isEqualTo("Synthetic group topic");
    assertThat(entries.get(1).get("movable")).isEqualTo(false);
    assertThat(entries.get(2).get("id")).isEqualTo(blockerId);
    assertThat(entries.get(2).get("title")).isEqualTo("Lunch");
    assertThat(entries.get(2).get("movable")).isEqualTo(true);
    assertThat(entries.get(3)).doesNotContainKey("title");
    assertThat(entries.get(3).get("movable")).isEqualTo(false);

    // Outside the window nothing is listed.
    assertThat(list(entries(in(24 * 40), in(24 * 50)))).isEmpty();
  }

  @Test
  void entries_Should_RefuseAnInvalidWindow() throws Exception {
    actAsCounsellor(counsellor);

    mockMvc.perform(entries(in(2), in(1))).andExpect(status().isBadRequest());
    mockMvc.perform(entries(in(0), in(24 * 62 + 1))).andExpect(status().isBadRequest());
    mockMvc.perform(get("/users/calendar/entries")).andExpect(status().isBadRequest());
    mockMvc
        .perform(colleagueEntries(colleague, in(0), in(24 * 63)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void blockers_Should_BeCreatedMovedAndRemovedByTheirOwnerOnly() throws Exception {
    actAsCounsellor(counsellor);
    var until = LocalDate.now(ZoneOffset.UTC).plusDays(22).toString();
    var series =
        createBlocker(
            in(24),
            in(25),
            "Team",
            Map.of("weekdays", List.of(0, 1, 2, 3, 4, 5, 6), "until", until));
    assertThat(series.size()).isBetween(22, 23);
    assertThat(series.stream().map(row -> row.get("seriesId")).distinct()).hasSize(1);
    assertThat(series.get(0).get("seriesId")).isNotNull();
    var id = (String) series.get(0).get("id");

    mockMvc.perform(moveBlocker(id, in(-48), in(-47))).andExpect(status().isBadRequest());
    var moved =
        mockMvc
            .perform(moveBlocker(id, in(26), in(27)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JSON.readValue(moved, Map.class).get("id")).isEqualTo(id);
    assertThat(Instant.parse((String) JSON.readValue(moved, Map.class).get("start")))
        .isEqualTo(in(26));

    actAsCounsellor(colleague);
    mockMvc.perform(moveBlocker(id, in(28), in(29))).andExpect(status().isNotFound());
    mockMvc
        .perform(csrf(delete("/users/calendar/blockers/" + id)))
        .andExpect(status().isNotFound());

    actAsCounsellor(counsellor);
    var busy = busyTimeOf(counsellor, in(60), in(61));
    mockMvc
        .perform(csrf(delete("/users/calendar/blockers/" + busy.getId())))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(csrf(delete("/users/calendar/blockers/" + id)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(csrf(delete("/users/calendar/blockers/" + id)))
        .andExpect(status().isNotFound());
    assertThat(list(entries(in(0), in(24 * 30))))
        .hasSize(series.size())
        .noneMatch(row -> row.get("id").equals(id));
  }

  @Test
  void blockers_Should_RefuseInvalidRequests() throws Exception {
    actAsCounsellor(counsellor);

    mockMvc.perform(blockerRequest(in(2), in(1), null, null)).andExpect(status().isBadRequest());
    mockMvc
        .perform(blockerRequest(in(1), in(2), "x".repeat(256), null))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            blockerRequest(
                in(1),
                in(2),
                null,
                Map.of(
                    "weekdays",
                    List.of(0, 1, 2, 3, 4, 5, 6),
                    "until",
                    LocalDate.now(ZoneOffset.UTC).plusDays(200).toString())))
        .andExpect(status().isBadRequest());
    assertThat(list(entries(in(0), in(24 * 30)))).isEmpty();
  }

  @Test
  void colleagues_Should_ListThoseInScope() throws Exception {
    actAsCounsellor(counsellor);
    colleague.setInternalDisplayName("Synthetic Colleague");
    Tenants.in(TENANT, () -> consultants.save(colleague));

    var agencyScope = body(get("/users/calendar/colleagues"));
    assertThat(agencyScope.get("visibilityScope")).isEqualTo("AGENCY");
    assertThat(agencyScope.get("showAskerUsernames")).isEqualTo(false);
    assertThat(colleaguesOf(agencyScope))
        .containsExactly(
            Map.of(
                "id",
                colleague.getId(),
                "displayName",
                "Synthetic Colleague",
                "agencyName",
                "Synthetic Centre",
                "relation",
                "SAME_AGENCY"));

    policy(CalendarVisibilityScope.TENANT, true);
    var tenantScope = body(get("/users/calendar/colleagues"));
    assertThat(tenantScope.get("showAskerUsernames")).isEqualTo(true);
    assertThat(colleaguesOf(tenantScope))
        .extracting(row -> row.get("id"))
        .contains(colleague.getId(), colleagueOfOtherAgency.getId())
        .doesNotContain(counsellor.getId(), counsellorOfOtherTenant.getId());
    assertThat(colleaguesOf(body(get("/users/calendar/colleagues").param("search", "colleag"))))
        .extracting(row -> row.get("id"))
        .containsExactly(colleague.getId());

    policy(CalendarVisibilityScope.PLATFORM, false);
    assertThat(colleaguesOf(body(get("/users/calendar/colleagues"))))
        .filteredOn(row -> row.get("id").equals(counsellorOfOtherTenant.getId()))
        .extracting(row -> row.get("relation"))
        .containsExactly("OTHER_TENANT");
  }

  @Test
  void colleagueEntries_Should_BeRedacted() throws Exception {
    actAsCounsellor(colleague);
    appointmentOf(session, in(24), in(25), SessionAppointment.Kind.ONSITE);
    groupChatOf(counsellor, in(48));
    var seriesBlocker = blockerOf(counsellor, in(50), in(51), Source.ORISO);
    busyTimeOf(counsellor, in(60), in(61));

    var response =
        mockMvc
            .perform(colleagueEntries(counsellor, in(0), in(24 * 30)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var entries = parse(response);
    assertThat(entries)
        .extracting(row -> row.get("type"))
        .containsExactly("APPOINTMENT", "GROUP", "BLOCKER", "BLOCKER");
    assertThat(entries).allMatch(row -> row.get("ownerId").equals(counsellor.getId()));
    assertThat(entries).allMatch(row -> row.get("movable").equals(false));
    assertThat(entries.get(0).get("kind")).isEqualTo("ONSITE");
    for (var entry : entries) {
      assertThat(entry.keySet()).isSubsetOf(COLLEAGUE_FIELDS);
      assertThat(entry).doesNotContainKeys("sessionId", "seriesId", "title");
    }
    assertThat(response)
        .doesNotContain("sessionId", "seriesId", "title", "BUSY")
        .doesNotContain(asker.getUsername(), "Synthetic group topic", "Private note")
        .doesNotContain(seriesBlocker.getSeriesId().toString());

    policy(CalendarVisibilityScope.AGENCY, true);
    var named = list(colleagueEntries(counsellor, in(0), in(24 * 30)));
    assertThat(named.get(0).get("title")).isEqualTo(asker.getUsername());
    assertThat(named.subList(1, 4)).allMatch(row -> !row.containsKey("title"));
    assertThat(named)
        .allMatch(row -> !row.containsKey("sessionId") && !row.containsKey("seriesId"));
  }

  @Test
  void colleagueEntries_Should_AnswerNotFound_When_TheColleagueIsOutsideTheScope()
      throws Exception {
    actAsCounsellor(counsellor);
    blockerOf(colleagueOfOtherAgency, in(50), in(51), Source.ORISO);
    blockerOf(counsellorOfOtherTenant, in(50), in(51), Source.ORISO);
    var window = new Instant[] {in(0), in(24 * 30)};

    // AGENCY: only who shares a centre.
    mockMvc.perform(colleagueEntries(colleague, window[0], window[1])).andExpect(status().isOk());
    assertNotFound(colleagueOfOtherAgency.getId(), window);
    assertNotFound(counsellorOfOtherTenant.getId(), window);
    assertNotFound("no-such-consultant", window);
    assertNotFound(counsellor.getId(), window);
    assertNotFound(asker.getUserId(), window);

    // TENANT: the whole Träger, nobody beyond.
    policy(CalendarVisibilityScope.TENANT, false);
    assertThat(list(colleagueEntries(colleagueOfOtherAgency, window[0], window[1]))).hasSize(1);
    assertNotFound(counsellorOfOtherTenant.getId(), window);

    // PLATFORM: other Träger too, and their rows are read across the tenant boundary.
    policy(CalendarVisibilityScope.PLATFORM, false);
    var foreignAsker = fixtures.adviceSeeker(OTHER_TENANT);
    var foreignSession = fixtures.session(foreignAsker, FOREIGN_AGENCY, counsellorOfOtherTenant);
    Tenants.in(
        OTHER_TENANT,
        () -> appointmentOf(foreignSession, in(24), in(25), SessionAppointment.Kind.CHAT));
    var foreign = list(colleagueEntries(counsellorOfOtherTenant, window[0], window[1]));
    assertThat(foreign)
        .extracting(row -> row.get("type"))
        .containsExactly("APPOINTMENT", "BLOCKER");
    assertThat(foreign).allMatch(row -> !row.containsKey("sessionId") && !row.containsKey("title"));
  }

  @Test
  void asker_Should_ReadOwnEntries_ButNeverCounsellorRoutes() throws Exception {
    counsellor.setDisplayName("Synthetic Display");
    Tenants.in(TENANT, () -> consultants.save(counsellor));
    actAsCounsellor(counsellor);
    var appointmentId = appointment("CHAT", in(24), in(25));
    var blockerId = (String) createBlocker(in(50), in(51), "Lunch", null).get(0).get("id");

    actAsAsker(asker);
    var entries = list(entries(in(0), in(24 * 30)));
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("id")).isEqualTo(appointmentId);
    assertThat(entries.get(0).get("title")).isEqualTo("Synthetic Display");
    assertThat(entries.get(0).get("ownerId")).isEqualTo(asker.getUserId());
    assertThat(entries.get(0).get("movable")).isEqualTo(false);
    assertThat(entries.get(0).get("kind")).isEqualTo("CHAT");

    mockMvc.perform(get("/users/calendar/colleagues")).andExpect(status().isForbidden());
    mockMvc
        .perform(colleagueEntries(counsellor, in(0), in(24 * 30)))
        .andExpect(status().isForbidden());
    mockMvc.perform(blockerRequest(in(1), in(2), null, null)).andExpect(status().isForbidden());
    mockMvc.perform(moveBlocker(blockerId, in(1), in(2))).andExpect(status().isForbidden());
    mockMvc
        .perform(csrf(delete("/users/calendar/blockers/" + blockerId)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void askerAuthority_Should_OnlyOpenTheOwnEntries() throws Exception {
    actAsAsker(asker);

    mockMvc.perform(entries(in(0), in(24))).andExpect(status().isOk());
    mockMvc.perform(get("/users/calendar/colleagues")).andExpect(status().isForbidden());
    mockMvc.perform(get("/service/users/calendar/colleagues")).andExpect(status().isForbidden());
    mockMvc.perform(colleagueEntries(counsellor, in(0), in(24))).andExpect(status().isForbidden());
    mockMvc.perform(blockerRequest(in(1), in(2), null, null)).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_ADMIN)
  void callerWithoutACalendarAuthority_Should_BeForbidden() throws Exception {
    mockMvc.perform(entries(in(0), in(24))).andExpect(status().isForbidden());
    mockMvc.perform(get("/users/calendar/colleagues")).andExpect(status().isForbidden());
  }

  @Test
  @WithAnonymousUser
  void unauthenticatedCaller_Should_BeUnauthorized() throws Exception {
    mockMvc.perform(entries(in(0), in(24))).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/users/calendar/colleagues")).andExpect(status().isUnauthorized());
    mockMvc.perform(blockerRequest(in(1), in(2), null, null)).andExpect(status().isUnauthorized());
  }

  private void assertNotFound(String consultantId, Instant[] window) throws Exception {
    mockMvc
        .perform(
            get("/users/calendar/colleagues/" + consultantId + "/entries")
                .param("from", window[0].toString())
                .param("to", window[1].toString()))
        .andExpect(status().isNotFound());
  }

  private void policy(CalendarVisibilityScope scope, boolean showAskerUsernames) {
    when(policies.resolve(any())).thenReturn(new CalendarPolicy(scope, showAskerUsernames));
  }

  private String appointment(String kind, Instant start, Instant end) throws Exception {
    var body = new java.util.HashMap<String, Object>(times(start, end));
    if (kind != null) {
      body.put("kind", kind);
    }
    var response =
        mockMvc
            .perform(
                csrf(post("/users/sessions/" + session.getId() + "/appointments"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var created = JSON.readValue(response, Map.class);
    assertThat(created.get("kind")).isEqualTo(kind);
    return (String) created.get("id");
  }

  private SessionAppointment appointmentOf(
      Session ofSession, Instant start, Instant end, SessionAppointment.Kind kind) {
    var appointment = new SessionAppointment();
    appointment.setSession(ofSession);
    appointment.setStartTime(start);
    appointment.setEndTime(end);
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    appointment.setKind(kind);
    return Tenants.acrossAll(() -> appointments.save(appointment));
  }

  private Chat groupChatOf(Consultant owner, Instant start) {
    var startUtc = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
    var chat =
        Tenants.in(
            owner.getTenantId(),
            () ->
                chats.save(
                    Chat.builder()
                        .topic("Synthetic group topic")
                        .chatOwner(owner)
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

  private CalendarBlocker busyTimeOf(Consultant owner, Instant start, Instant end) {
    return blockerOf(owner, start, end, Source.EXTERNAL);
  }

  private CalendarBlocker blockerOf(Consultant owner, Instant start, Instant end, Source source) {
    var blocker = new CalendarBlocker();
    blocker.setConsultantId(owner.getId());
    blocker.setTenantId(owner.getTenantId());
    blocker.setStartTime(start);
    blocker.setEndTime(end);
    blocker.setSource(source);
    blocker.setCreateDate(Instant.now());
    if (source == Source.ORISO) {
      blocker.setNote("Private note");
      blocker.setSeriesId(java.util.UUID.randomUUID());
    }
    return blockers.save(blocker);
  }

  private List<Map<String, Object>> createBlocker(
      Instant start, Instant end, String note, Map<String, Object> repeat) throws Exception {
    var response =
        mockMvc
            .perform(blockerRequest(start, end, note, repeat))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return parse(response);
  }

  private MockHttpServletRequestBuilder blockerRequest(
      Instant start, Instant end, String note, Map<String, Object> repeat) throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("start", start.toString());
    body.put("end", end.toString());
    if (note != null) {
      body.put("note", note);
    }
    if (repeat != null) {
      body.put("repeat", repeat);
    }
    return csrf(post("/users/calendar/blockers"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(body));
  }

  private MockHttpServletRequestBuilder moveBlocker(String id, Instant start, Instant end)
      throws Exception {
    return csrf(put("/users/calendar/blockers/" + id))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("start", start.toString(), "end", end.toString())));
  }

  private MockHttpServletRequestBuilder entries(Instant from, Instant to) {
    return get("/users/calendar/entries").param("from", from.toString()).param("to", to.toString());
  }

  private MockHttpServletRequestBuilder colleagueEntries(
      Consultant ofColleague, Instant from, Instant to) {
    return get("/users/calendar/colleagues/" + ofColleague.getId() + "/entries")
        .param("from", from.toString())
        .param("to", to.toString());
  }

  private List<Map<String, Object>> list(MockHttpServletRequestBuilder request) throws Exception {
    return parse(
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
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

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> colleaguesOf(Map<String, Object> listing) {
    return (List<Map<String, Object>>) listing.get("colleagues");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> parse(String json) throws Exception {
    return JSON.readValue(json, List.class);
  }

  private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private static Map<String, Object> times(Instant start, Instant end) {
    return Map.of("startTime", start.toString(), "endTime", end.toString());
  }

  private Instant in(int hours) {
    return Instant.now().truncatedTo(ChronoUnit.HOURS).plus(hours, ChronoUnit.HOURS);
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
