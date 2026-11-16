package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.calendar.CalendarPolicyResolver;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
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

/**
 * Working hours (ADR-032, addendum v1.1) through the real security chain, controller, service and
 * database; caller staged. Configured like {@link CalendarControllerIT} to share its context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(CalendarWorkingHoursControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.USER_DEFAULT})
class CalendarWorkingHoursControllerIT {

  static final long TENANT = 1L;
  private static final long AGENCY = 9841L;
  private static final String PATH = "/users/calendar/working-hours";
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private CalendarWorkingHoursRepository workingHours;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean CalendarPolicyResolver policies;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant counsellor;
  private Consultant colleague;
  private User asker;

  @BeforeEach
  void seed() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    counsellor = fixtures.consultant(TENANT, AGENCY);
    colleague = fixtures.consultant(TENANT, AGENCY);
    asker = fixtures.adviceSeeker(TENANT);
  }

  @AfterEach
  void removeSeededRows() {
    Tenants.acrossAll(() -> workingHours.deleteAll());
    fixtures.removeAll();
  }

  @Test
  void workingHours_Should_BeEmpty_Until_TheCounsellorSetsThem() throws Exception {
    actAsCounsellor(counsellor);

    assertThat(body(get(PATH))).isEqualTo(Map.of("windows", List.of()));
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
        Map.of(
            "windows",
            List.of(
                window(1, "07:00", "12:00"),
                window(1, "13:00", "20:00"),
                window(5, "08:00", "14:00")));
    assertThat(stored).isEqualTo(expected);
    assertThat(body(get(PATH))).isEqualTo(expected);

    body(set(window(3, "09:30", "16:45")));
    assertThat(body(get(PATH))).isEqualTo(Map.of("windows", List.of(window(3, "09:30", "16:45"))));

    body(set());
    assertThat(body(get(PATH))).isEqualTo(Map.of("windows", List.of()));
  }

  @Test
  void workingHours_Should_BelongToTheirCounsellorOnly() throws Exception {
    actAsCounsellor(counsellor);
    body(set(window(1, "07:00", "20:00")));

    actAsCounsellor(colleague);
    assertThat(body(get(PATH))).isEqualTo(Map.of("windows", List.of()));
    body(set(window(2, "10:00", "11:00")));

    actAsCounsellor(counsellor);
    assertThat(body(get(PATH))).isEqualTo(Map.of("windows", List.of(window(1, "07:00", "20:00"))));
  }

  @Test
  void put_Should_RefuseInvalidWindows_AndKeepTheStoredOnes() throws Exception {
    actAsCounsellor(counsellor);
    body(set(window(1, "07:00", "20:00")));
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
            Map.of())) {
      mockMvc
          .perform(csrf(put(PATH)).contentType(MediaType.APPLICATION_JSON).content(json(invalid)))
          .andExpect(status().isBadRequest());
    }
    assertThat(body(get(PATH))).isEqualTo(Map.of("windows", List.of(window(1, "07:00", "20:00"))));
  }

  @Test
  void asker_Should_NotReachWorkingHours() throws Exception {
    actAsAsker(asker);

    mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
    mockMvc.perform(set(window(1, "08:00", "17:00"))).andExpect(status().isForbidden());
    assertThat(workingHours.findAll()).isEmpty();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void askerAuthority_Should_BeForbidden_OnBothPaths() throws Exception {
    actAsAsker(asker);

    mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
    mockMvc.perform(get("/service" + PATH)).andExpect(status().isForbidden());
    mockMvc.perform(set(window(1, "08:00", "17:00"))).andExpect(status().isForbidden());
    mockMvc
        .perform(
            csrf(put("/service" + PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("windows", List.of()))))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithAnonymousUser
  void unauthenticatedCaller_Should_BeUnauthorized() throws Exception {
    mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mockMvc.perform(set(window(1, "08:00", "17:00"))).andExpect(status().isUnauthorized());
  }

  private static Map<String, Object> window(int weekday, String start, String end) {
    return Map.of("weekday", weekday, "start", start, "end", end);
  }

  @SafeVarargs
  private MockHttpServletRequestBuilder set(Map<String, Object>... windows) throws Exception {
    return csrf(put(PATH))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("windows", List.of(windows))));
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
