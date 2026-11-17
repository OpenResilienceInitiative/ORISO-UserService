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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.facade.InternalSubsetChatFacade;
import de.caritas.cob.userservice.api.facade.InternalSubsetChatFacade.OpenedRoom;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.CalendarAutoAcceptRepository;
import de.caritas.cob.userservice.api.port.out.CalendarColleagueAppointmentRepository;
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
import java.time.temporal.ChronoUnit;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Appointments between two counsellors (ORISO-Frontend#2305) through the real security chain,
 * controller, services and database; the internal chat room is staged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(CalendarColleagueAppointmentControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT})
class CalendarColleagueAppointmentControllerIT {

  static final long TENANT = 1L;
  private static final long AGENCY = 9831L;
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private CalendarColleagueAppointmentRepository meetings;
  @Autowired private CalendarAutoAcceptRepository autoAccept;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean CalendarPolicyResolver policies;
  @MockitoBean InternalSubsetChatFacade rooms;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant counsellor;
  private Consultant colleague;
  private Consultant third;

  @BeforeEach
  void seed() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(agencyService.getAgencies(anyList()))
        .thenReturn(List.of(new AgencyDTO().id(AGENCY).name("Synthetic Centre")));
    when(policies.resolve(any()))
        .thenReturn(new CalendarPolicy(CalendarVisibilityScope.AGENCY, true));
    when(rooms.open(any(), any())).thenReturn(new OpenedRoom("!pair:synthetic", 4711L, true));
    counsellor = fixtures.consultant(TENANT, AGENCY);
    colleague = fixtures.consultant(TENANT, AGENCY);
    third = fixtures.consultant(TENANT, AGENCY);
  }

  @AfterEach
  void removeSeededRows() {
    Tenants.acrossAll(
        () -> {
          meetings.deleteAll();
          autoAccept.deleteAll();
        });
    fixtures.removeAll();
  }

  @Test
  void appointment_Should_WaitForTheColleague_ThenShowInBothCalendars() throws Exception {
    actAs(counsellor);
    var created = create(colleague, "VIDEO");
    var id = (String) created.get("id");
    assertThat(created.get("colleagueStatus")).isEqualTo("PENDING");
    assertThat(created.get("matrixRoomId")).isEqualTo("!pair:synthetic");

    actAs(third);
    assertThat(list(colleagueEntries(counsellor))).as("a waiting one is not shown").isEmpty();

    actAs(colleague);
    var invited = list(entries()).get(0);
    assertThat(invited.get("id")).isEqualTo(id);
    assertThat(invited.get("invited")).isEqualTo(true);
    assertThat(invited.get("colleagueId")).isEqualTo(counsellor.getId());
    mockMvc
        .perform(csrf(post("/users/calendar/colleague-appointments/" + id + "/accept")))
        .andExpect(status().isOk());

    actAs(counsellor);
    var mine = list(entries()).get(0);
    assertThat(mine.get("colleagueStatus")).isEqualTo("CONFIRMED");
    assertThat(mine.get("invited")).isEqualTo(false);
    assertThat(mine.get("colleagueId")).isEqualTo(colleague.getId());

    actAs(third);
    var seen = list(colleagueEntries(counsellor));
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).get("kind")).isEqualTo("VIDEO");
    assertThat(seen.get(0)).doesNotContainKeys("title", "colleagueId", "matrixRoomId");

    actAs(colleague);
    mockMvc
        .perform(csrf(delete("/users/calendar/colleague-appointments/" + id)))
        .andExpect(status().isNoContent());
    actAs(counsellor);
    assertThat(list(entries())).isEmpty();
  }

  @Test
  void appointment_Should_BeFixedAtOnce_When_TheColleagueAcceptsTheCallerAutomatically()
      throws Exception {
    actAs(colleague);
    mockMvc
        .perform(
            csrf(put("/users/calendar/auto-accept"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(Map.of("colleagueIds", List.of(counsellor.getId())))))
        .andExpect(status().isOk());

    actAs(counsellor);
    assertThat(create(colleague, "ONSITE").get("colleagueStatus")).isEqualTo("CONFIRMED");
  }

  @Test
  void accept_Should_AnswerNotFound_ForTheOrganizer() throws Exception {
    actAs(counsellor);
    var id = (String) create(colleague, "CHAT").get("id");

    mockMvc
        .perform(csrf(post("/users/calendar/colleague-appointments/" + id + "/accept")))
        .andExpect(status().isNotFound());
  }

  private Map<String, Object> create(Consultant with, String kind) throws Exception {
    var body =
        Map.of(
            "colleagueId", with.getId(),
            "start", in(24).toString(),
            "end", in(25).toString(),
            "kind", kind);
    return JSON.readValue(
        mockMvc
            .perform(
                csrf(post("/users/calendar/colleague-appointments"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        new TypeReference<>() {});
  }

  private MockHttpServletRequestBuilder entries() {
    return get("/users/calendar/entries")
        .param("from", in(0).toString())
        .param("to", in(24 * 7).toString());
  }

  private MockHttpServletRequestBuilder colleagueEntries(Consultant of) {
    return get("/users/calendar/colleagues/" + of.getId() + "/entries")
        .param("from", in(0).toString())
        .param("to", in(24 * 7).toString());
  }

  private List<Map<String, Object>> list(MockHttpServletRequestBuilder request) throws Exception {
    return JSON.readValue(
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        new TypeReference<>() {});
  }

  private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private Instant in(int hours) {
    return Instant.now().truncatedTo(ChronoUnit.HOURS).plus(hours, ChronoUnit.HOURS);
  }

  private void actAs(Consultant consultant) {
    Tenants.actAs(caller, consultant.getId(), consultant.getTenantId(), UserRole.CONSULTANT);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.CONSULTANT_DEFAULT));
  }
}
