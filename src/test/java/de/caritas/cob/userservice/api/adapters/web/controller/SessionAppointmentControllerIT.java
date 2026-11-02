package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** Real security chain, controller, service and database; only the caller is staged. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true", "feature.appointment.enabled=false"})
@Import(TenantFixtures.class)
@WithTenant(SessionAppointmentControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.USER_DEFAULT})
class SessionAppointmentControllerIT {

  static final long TENANT = 1L;
  private static final long OTHER_TENANT = 2L;
  private static final long AGENCY = 9811L;
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private CaseHandoverRequestRepository handovers;
  @Autowired private SessionRepository sessions;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean AppointmentService appointmentService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant counsellor;
  private Consultant otherCounsellor;
  private Consultant counsellorOfOtherTenant;
  private User asker;
  private User otherAsker;
  private Session session;
  private final List<CaseHandoverRequest> seededHandovers = new ArrayList<>();

  @BeforeEach
  void seed() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    counsellor = fixtures.consultant(TENANT, AGENCY);
    otherCounsellor = fixtures.consultant(TENANT, AGENCY);
    counsellorOfOtherTenant = fixtures.consultant(OTHER_TENANT);
    asker = fixtures.adviceSeeker(TENANT);
    otherAsker = fixtures.adviceSeeker(TENANT);
    session = fixtures.session(asker, AGENCY, counsellor);
  }

  @AfterEach
  void removeSeededRowsAndWithThemTheAppointmentsOfTheirSessions() {
    Tenants.in(TENANT, () -> handovers.deleteAll(seededHandovers));
    fixtures.removeAll();
  }

  @Test
  void create_Should_ReturnTheAppointment_When_CallerIsTheAssignedCounsellor() throws Exception {
    actAsCounsellor(counsellor);

    mockMvc
        .perform(create(session.getId(), in(1), in(2)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.sessionId").value(session.getId()))
        .andExpect(jsonPath("$.startTime").isNotEmpty())
        .andExpect(jsonPath("$.endTime").isNotEmpty())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.counterpart.username").isNotEmpty());

    verifyNoInteractions(appointmentService);
  }

  @Test
  void create_Should_Refuse_When_EndIsNotAfterStart() throws Exception {
    actAsCounsellor(counsellor);

    mockMvc.perform(create(session.getId(), in(2), in(2))).andExpect(status().isBadRequest());
    mockMvc.perform(create(session.getId(), in(2), in(1))).andExpect(status().isBadRequest());
  }

  @Test
  void create_Should_Refuse_When_StartIsNotInTheFuture() throws Exception {
    actAsCounsellor(counsellor);

    mockMvc.perform(create(session.getId(), in(-1), in(1))).andExpect(status().isBadRequest());
  }

  @Test
  void asker_Should_ReadAndCancelButNeverCreateOrMove() throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();

    actAsAsker(asker);
    mockMvc.perform(create(session.getId(), in(1), in(2))).andExpect(status().isForbidden());
    mockMvc
        .perform(reschedule(session.getId(), id, in(3), in(4)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(list(session.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id))
        .andExpect(jsonPath("$[0].startTime").isNotEmpty());
    mockMvc
        .perform(cancel(session.getId(), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void strangers_Should_GetTheSameNotFoundAsForAnUnknownSession() throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();
    var unknownSession = session.getId() + 100_000L;

    actAsCounsellor(otherCounsellor);
    assertEveryRouteAnswersNotFound(session.getId(), id);
    assertEveryRouteAnswersNotFound(unknownSession, id);

    actAsAsker(otherAsker);
    assertEveryRouteAnswersNotFoundForAsker(session.getId(), id);

    actAs(counsellorOfOtherTenant.getId(), OTHER_TENANT, UserRole.CONSULTANT);
    assertEveryRouteAnswersNotFound(session.getId(), id);
  }

  @Test
  void assignedCounsellor_Should_GetNotFound_When_ActingFromAnotherTenant() throws Exception {
    actAs(counsellor.getId(), OTHER_TENANT, UserRole.CONSULTANT);

    assertEveryRouteAnswersNotFound(session.getId(), UUID.randomUUID().toString());
  }

  @Test
  void appointmentOfAnotherSession_Should_NotBeReachableThroughMySession() throws Exception {
    actAsCounsellor(counsellor);
    var mine = fixtures.session(fixtures.adviceSeeker(TENANT), AGENCY, counsellor);
    var id = createdAppointment();

    mockMvc.perform(cancel(mine.getId(), id)).andExpect(status().isNotFound());
    mockMvc.perform(reschedule(mine.getId(), id, in(3), in(4))).andExpect(status().isNotFound());
  }

  @Test
  void reschedule_Should_MoveTheAppointment_And_ValidateTheNewTime() throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();
    var start = in(5);

    mockMvc
        .perform(reschedule(session.getId(), id, start, in(6)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
    var read = body(list(session.getId())).get(0);
    assertThat(Instant.parse((String) read.get("startTime"))).isEqualTo(start);

    mockMvc
        .perform(reschedule(session.getId(), id, in(-1), in(6)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cancelledAppointment_Should_RefuseToMove_And_CancelAgainShouldChangeNothing()
      throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();

    mockMvc.perform(cancel(session.getId(), id)).andExpect(status().isOk());
    mockMvc
        .perform(cancel(session.getId(), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    mockMvc.perform(reschedule(session.getId(), id, in(3), in(4))).andExpect(status().isConflict());
  }

  @Test
  void upcoming_Should_ListOnlyMyScheduledAppointments_EarliestFirst() throws Exception {
    actAsCounsellor(counsellor);
    var later = createdAppointment(in(5), in(6));
    var sooner = createdAppointment(in(1), in(2));
    var cancelled = createdAppointment(in(3), in(4));
    mockMvc.perform(cancel(session.getId(), cancelled)).andExpect(status().isOk());

    var mine = body(get("/users/appointments/upcoming"));
    assertThat(mine).extracting(row -> row.get("id")).containsExactly(sooner, later);
    assertThat(mine.get(0).get("sessionId")).isEqualTo(session.getId().intValue());
    assertThat(mine.get(0).get("status")).isEqualTo("SCHEDULED");

    actAsAsker(asker);
    var askerRows = body(get("/users/appointments/upcoming"));
    assertThat(askerRows).extracting(row -> row.get("id")).containsExactly(sooner, later);
    assertThat(((Map<?, ?>) askerRows.get(0).get("counterpart")).get("displayName"))
        .isEqualTo(counsellor.getDisplayName());

    actAsCounsellor(otherCounsellor);
    assertThat(body(get("/users/appointments/upcoming"))).isEmpty();
    actAsAsker(otherAsker);
    assertThat(body(get("/users/appointments/upcoming"))).isEmpty();
  }

  @Test
  void upcoming_Should_BeBoundedToFifty() throws Exception {
    actAsCounsellor(counsellor);
    for (int hour = 1; hour <= 51; hour++) {
      createdAppointment(in(hour), in(hour + 1));
    }

    assertThat(body(get("/users/appointments/upcoming"))).hasSize(50);
  }

  @Test
  void coAccessCounsellor_Should_HaveTheSameRightsAsTheSessionsCounsellor() throws Exception {
    grant(otherCounsellor, CaseHandoverRequest.Status.GRANTED, AccessType.CO_ACCESS, tomorrow());
    actAsCounsellor(otherCounsellor);

    var id = createdAppointment();
    mockMvc.perform(reschedule(session.getId(), id, in(3), in(4))).andExpect(status().isOk());
    mockMvc
        .perform(list(session.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id));
    assertThat(body(get("/users/appointments/upcoming")))
        .extracting(row -> row.get("id"))
        .containsExactly(id);
    mockMvc
        .perform(cancel(session.getId(), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void coAccess_Should_AlsoCount_When_TheClientMayStillOptOut() throws Exception {
    grant(
        otherCounsellor,
        CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT,
        AccessType.CO_ACCESS,
        tomorrow());
    actAsCounsellor(otherCounsellor);

    mockMvc.perform(list(session.getId())).andExpect(status().isOk());
  }

  @Test
  void handoverWithoutLiveCoAccess_Should_LeaveTheColleagueAStranger() throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();
    grant(otherCounsellor, CaseHandoverRequest.Status.GRANTED, AccessType.CO_ACCESS, yesterday());
    grant(
        otherCounsellor,
        CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT,
        AccessType.CO_ACCESS,
        tomorrow());
    grant(otherCounsellor, CaseHandoverRequest.Status.EXPIRED, AccessType.CO_ACCESS, tomorrow());
    // A takeover whose holder is not the session's counsellor any more.
    grant(otherCounsellor, CaseHandoverRequest.Status.GRANTED, AccessType.TAKEOVER, null);

    actAsCounsellor(otherCounsellor);
    assertEveryRouteAnswersNotFound(session.getId(), id);
    assertThat(body(get("/users/appointments/upcoming"))).isEmpty();
  }

  @Test
  void coAccessOnAnotherSession_Should_NotOpenThisOne() throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();
    var thisSession = session;
    session = fixtures.session(otherAsker, AGENCY, counsellor);
    grant(otherCounsellor, CaseHandoverRequest.Status.GRANTED, AccessType.CO_ACCESS, tomorrow());

    actAsCounsellor(otherCounsellor);
    assertEveryRouteAnswersNotFound(thisSession.getId(), id);
    assertThat(body(get("/users/appointments/upcoming"))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = SessionStatus.class,
      names = {"DONE", "IN_ARCHIVE"})
  void finishedSession_Should_RefuseCreateAndMove_But_AllowReadAndCancel(SessionStatus finished)
      throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();
    finish(finished);

    mockMvc.perform(create(session.getId(), in(1), in(2))).andExpect(status().isConflict());
    mockMvc.perform(reschedule(session.getId(), id, in(3), in(4))).andExpect(status().isConflict());
    mockMvc.perform(list(session.getId())).andExpect(status().isOk());
    mockMvc
        .perform(cancel(session.getId(), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void finishedSession_Should_NotChangeTheAnswerForThoseWithoutTheRight() throws Exception {
    actAsCounsellor(counsellor);
    var id = createdAppointment();
    finish(SessionStatus.DONE);

    actAsAsker(asker);
    mockMvc.perform(create(session.getId(), in(1), in(2))).andExpect(status().isForbidden());
    actAsCounsellor(otherCounsellor);
    assertEveryRouteAnswersNotFound(session.getId(), id);
  }

  private void assertEveryRouteAnswersNotFound(long sessionId, String id) throws Exception {
    assertEveryRouteAnswersNotFoundForAsker(sessionId, id);
    mockMvc.perform(create(sessionId, in(1), in(2))).andExpect(status().isNotFound());
    mockMvc.perform(reschedule(sessionId, id, in(3), in(4))).andExpect(status().isNotFound());
  }

  private void assertEveryRouteAnswersNotFoundForAsker(long sessionId, String id) throws Exception {
    mockMvc.perform(list(sessionId)).andExpect(status().isNotFound());
    mockMvc.perform(cancel(sessionId, id)).andExpect(status().isNotFound());
  }

  private String createdAppointment() throws Exception {
    return createdAppointment(in(1), in(2));
  }

  private String createdAppointment(Instant start, Instant end) throws Exception {
    var response =
        mockMvc
            .perform(create(session.getId(), start, end))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return (String) JSON.readValue(response, Map.class).get("id");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> body(MockHttpServletRequestBuilder request) throws Exception {
    var response =
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readValue(response, List.class);
  }

  private MockHttpServletRequestBuilder list(long sessionId) {
    return get("/users/sessions/" + sessionId + "/appointments");
  }

  private MockHttpServletRequestBuilder create(long sessionId, Instant start, Instant end)
      throws Exception {
    return csrf(post("/users/sessions/" + sessionId + "/appointments"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(times(start, end));
  }

  private MockHttpServletRequestBuilder reschedule(
      long sessionId, String id, Instant start, Instant end) throws Exception {
    return csrf(put("/users/sessions/" + sessionId + "/appointments/" + id))
        .contentType(MediaType.APPLICATION_JSON)
        .content(times(start, end));
  }

  private MockHttpServletRequestBuilder cancel(long sessionId, String id) {
    return csrf(post("/users/sessions/" + sessionId + "/appointments/" + id + "/cancel"));
  }

  private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private String times(Instant start, Instant end) throws Exception {
    return JSON.writeValueAsString(
        Map.of("startTime", start.toString(), "endTime", end.toString()));
  }

  private void finish(SessionStatus finished) {
    session.setStatus(finished);
    session = Tenants.in(TENANT, () -> sessions.save(session));
  }

  private LocalDateTime tomorrow() {
    return LocalDateTime.now().plusDays(1);
  }

  private LocalDateTime yesterday() {
    return LocalDateTime.now().minusDays(1);
  }

  private void grant(
      Consultant requester,
      CaseHandoverRequest.Status status,
      AccessType accessType,
      LocalDateTime expiresAt) {
    var request =
        CaseHandoverRequest.builder()
            .session(session)
            .requesterConsultant(requester)
            .reasonCode("ADVICE_NEEDED")
            .reasonLabel("Advice needed")
            .explanation("synthetic")
            .status(status)
            .clientConsentRequired(false)
            .clientConsent(CaseHandoverConsentMode.NONE)
            .policyAuthority("synthetic")
            .auditOutcome("synthetic")
            .createdAt(LocalDateTime.now())
            .accessType(accessType)
            .expiresAt(expiresAt)
            .tenantId(TENANT)
            .build();
    seededHandovers.add(Tenants.in(TENANT, () -> handovers.save(request)));
  }

  private Instant in(int hours) {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(hours, ChronoUnit.HOURS);
  }

  private void actAsCounsellor(Consultant consultant) {
    actAs(consultant.getId(), TENANT, UserRole.CONSULTANT);
  }

  private void actAsAsker(User user) {
    actAs(user.getUserId(), TENANT, UserRole.USER);
  }

  private void actAs(String userId, long tenant, UserRole role) {
    Tenants.actAs(caller, userId, tenant, role);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.CONSULTANT_DEFAULT));
  }
}
