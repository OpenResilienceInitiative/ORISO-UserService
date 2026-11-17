package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
 * Self-booking (ORISO-Frontend#1050): the asker of a 1:1 conversation books a free slot of its
 * counsellor. Real security chain, controller, service and database; only the caller is staged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(AppointmentSelfBookingControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.USER_DEFAULT})
class AppointmentSelfBookingControllerIT {

  static final long TENANT = 1L;
  private static final long AGENCY = 9812L;
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration LEAD_TIME = Duration.ofHours(24);

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private SessionRepository sessions;
  @Autowired private CalendarWorkingHoursRepository workingHours;
  @Autowired private CalendarBlockerRepository blockers;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant counsellor;
  private Consultant otherCounsellor;
  private User asker;
  private User otherAsker;
  private Session session;

  @BeforeEach
  void seed() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    counsellor = fixtures.consultant(TENANT, AGENCY);
    otherCounsellor = fixtures.consultant(TENANT, AGENCY);
    asker = fixtures.adviceSeeker(TENANT);
    otherAsker = fixtures.adviceSeeker(TENANT);
    session = fixtures.session(asker, AGENCY, counsellor);
  }

  @AfterEach
  void removeSeededRows() {
    Tenants.acrossAll(
        () -> {
          workingHours.deleteAll(
              workingHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(
                  counsellor.getId()));
          blockers.deleteAll(blockers.findOverlapping(counsellor.getId(), Instant.EPOCH, far()));
        });
    fixtures.removeAll();
  }

  @Test
  void slots_Should_OfferHourLongTimesInsideTheWorkingHours_AfterTheLeadTime() throws Exception {
    worksEveryDay("08:00", "18:00");
    actAsAsker(asker);
    var now = Instant.now();

    var slots = slots(session.getId(), now, now.plus(Duration.ofDays(3)));

    assertThat(slots).isNotEmpty();
    for (var slot : slots) {
      var start = Instant.parse((String) slot.get("startTime"));
      var end = Instant.parse((String) slot.get("endTime"));
      assertThat(Duration.between(start, end)).isEqualTo(Duration.ofHours(1));
      assertThat(start).isAfterOrEqualTo(now.plus(LEAD_TIME));
    }
  }

  @Test
  void slots_Should_BeEmpty_When_TheCounsellorKeepsNoWorkingHours() throws Exception {
    actAsAsker(asker);
    var now = Instant.now();

    assertThat(slots(session.getId(), now, now.plus(Duration.ofDays(3)))).isEmpty();
  }

  @Test
  void slots_Should_LeaveOutTheCounsellorsBlockers() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();
    var first = firstSlotStart(session.getId(), now);
    block(first, first.plus(Duration.ofHours(1)));

    var starts =
        slots(session.getId(), now, now.plus(Duration.ofDays(3))).stream()
            .map(slot -> Instant.parse((String) slot.get("startTime")))
            .toList();

    assertThat(starts).doesNotContain(first);
  }

  @Test
  void slots_Should_RefuseAWindowOfMoreThanFourteenDays() throws Exception {
    actAsAsker(asker);
    var now = Instant.now();

    mockMvc
        .perform(slotsRequest(session.getId(), now, now.plus(Duration.ofDays(15)), "VIDEO"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(slotsRequest(session.getId(), now, now.minusSeconds(1), "VIDEO"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void book_Should_ScheduleTheSlot_And_TakeItOutOfTheOffer() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();
    var start = firstSlotStart(session.getId(), now);

    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId").value(session.getId()))
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.kind").value("VIDEO"))
        .andExpect(jsonPath("$.counterpart.username").isNotEmpty());

    assertThat(firstSlotStart(session.getId(), now)).isNotEqualTo(start);
    var listed = body(get("/users/sessions/" + session.getId() + "/appointments"));
    assertThat(listed).hasSize(1);
    assertThat(Instant.parse((String) listed.get(0).get("startTime"))).isEqualTo(start);
  }

  @Test
  void book_Should_Refuse_When_TheTimeIsTakenMeanwhile() throws Exception {
    worksEveryDay("00:00", "23:30");
    var otherSession = fixtures.session(otherAsker, AGENCY, counsellor);
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "PHONE"))
        .andExpect(status().isCreated());

    actAsAsker(otherAsker);
    mockMvc
        .perform(book(otherSession.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict());
  }

  @Test
  void book_Should_Refuse_A_TimeThatIsNoOfferedSlot() throws Exception {
    worksEveryDay("08:00", "18:00");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());

    // Inside the lead time.
    var soon = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.HOURS);
    mockMvc
        .perform(book(session.getId(), soon, soon.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict());
    // Not on the step of the grid.
    var crooked = start.plus(Duration.ofMinutes(7));
    mockMvc
        .perform(book(session.getId(), crooked, crooked.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict());
    // Longer than a slot.
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(3)), "VIDEO"))
        .andExpect(status().isConflict());
  }

  @Test
  void book_Should_TakeOnlyVideoOrPhone() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());

    for (var kind : List.of("ONSITE", "CHAT")) {
      mockMvc
          .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), kind))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), null))
        .andExpect(status().isBadRequest());
  }

  @Test
  void counsellor_Should_NeitherReadSlotsNorSelfBook_And_KeepCreating() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());

    actAsCounsellor(counsellor);
    var now = Instant.now();
    mockMvc
        .perform(slotsRequest(session.getId(), now, now.plus(Duration.ofDays(1)), "VIDEO"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            csrf(post("/users/sessions/" + session.getId() + "/appointments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(times(start, start.plus(Duration.ofHours(1)), "VIDEO")))
        .andExpect(status().isCreated());
  }

  @Test
  void strangers_Should_GetNotFound() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());
    var now = Instant.now();

    for (var stranger :
        List.<Runnable>of(() -> actAsAsker(otherAsker), () -> actAsCounsellor(otherCounsellor))) {
      stranger.run();
      mockMvc
          .perform(slotsRequest(session.getId(), now, now.plus(Duration.ofDays(1)), "VIDEO"))
          .andExpect(status().isNotFound());
      mockMvc
          .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  void finishedConversation_Should_RefuseSlotsAndBooking() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());
    session.setStatus(SessionStatus.DONE);
    session = Tenants.in(TENANT, () -> sessions.save(session));
    var now = Instant.now();

    mockMvc
        .perform(slotsRequest(session.getId(), now, now.plus(Duration.ofDays(1)), "VIDEO"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict());
  }

  @Test
  void anonymousAsker_Should_BookLikeARegisteredOne() throws Exception {
    worksEveryDay("00:00", "23:30");
    Tenants.actAs(caller, asker.getUserId(), TENANT, UserRole.ANONYMOUS);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.ANONYMOUS_DEFAULT));
    var start = firstSlotStart(session.getId(), Instant.now());

    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated());
  }

  @Test
  void book_Should_AllowOnlyOneUpcomingAppointmentPerConversation() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var slots = slots(session.getId(), Instant.now(), Instant.now().plus(Duration.ofDays(3)));
    var first = Instant.parse((String) slots.get(0).get("startTime"));
    var later = Instant.parse((String) slots.get(4).get("startTime"));
    var id = booked(first);

    mockMvc
        .perform(book(session.getId(), later, later.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "SELF_BOOKING_LIMIT_REACHED"));

    // Cancelled, it no longer counts.
    mockMvc
        .perform(
            csrf(post("/users/sessions/" + session.getId() + "/appointments/" + id + "/cancel")))
        .andExpect(status().isOk());
    mockMvc
        .perform(book(session.getId(), later, later.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated());
  }

  @Test
  void book_Should_CountTheCounsellorsUpcomingAppointmentAsWell() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());
    actAsCounsellor(counsellor);
    var soon = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
    mockMvc
        .perform(
            csrf(post("/users/sessions/" + session.getId() + "/appointments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(times(soon, soon.plus(Duration.ofHours(1)), "PHONE")))
        .andExpect(status().isCreated());

    actAsAsker(asker);
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "SELF_BOOKING_LIMIT_REACHED"));
  }

  @Test
  void book_Should_RefuseAStartOutsideTheBookingWindow() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var beyond = Instant.now().plus(Duration.ofDays(15)).truncatedTo(ChronoUnit.HOURS);
    var soon = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.HOURS);

    for (var start : List.of(beyond, soon)) {
      mockMvc
          .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
          .andExpect(status().isConflict())
          .andExpect(header().string("X-Reason", "SLOT_OUTSIDE_BOOKING_WINDOW"));
    }
  }

  @Test
  void slots_Should_OfferNothingBeyondTheBookingHorizon() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();

    assertThat(slots(session.getId(), now.plus(Duration.ofDays(20)), now.plus(Duration.ofDays(25))))
        .isEmpty();
    var lastStart =
        slots(session.getId(), now.plus(Duration.ofDays(10)), now.plus(Duration.ofDays(20)))
            .stream()
            .map(slot -> Instant.parse((String) slot.get("startTime")))
            .max(Instant::compareTo)
            .orElseThrow();
    assertThat(lastStart).isBefore(now.plus(Duration.ofDays(14)).plusSeconds(60));
  }

  private String booked(Instant start) throws Exception {
    var response =
        mockMvc
            .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return (String) JSON.readValue(response, Map.class).get("id");
  }

  private Instant firstSlotStart(long sessionId, Instant now) throws Exception {
    var slots = slots(sessionId, now, now.plus(Duration.ofDays(3)));
    assertThat(slots).isNotEmpty();
    return Instant.parse((String) slots.get(0).get("startTime"));
  }

  private List<Map<String, Object>> slots(long sessionId, Instant from, Instant to)
      throws Exception {
    return body(slotsRequest(sessionId, from, to, "VIDEO"));
  }

  private MockHttpServletRequestBuilder slotsRequest(
      long sessionId, Instant from, Instant to, String medium) {
    return get("/users/sessions/" + sessionId + "/appointment-slots")
        .param("from", from.toString())
        .param("to", to.toString())
        .param("medium", medium);
  }

  private MockHttpServletRequestBuilder book(
      long sessionId, Instant start, Instant end, String kind) throws Exception {
    return csrf(post("/users/sessions/" + sessionId + "/appointment-slots/booking"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(times(start, end, kind));
  }

  private void worksEveryDay(String start, String end) {
    Tenants.acrossAll(
        () -> {
          for (int weekday = 0; weekday <= 6; weekday++) {
            var row = new CalendarWorkingHours();
            row.setConsultantId(counsellor.getId());
            row.setWeekday(weekday);
            row.setStartTime(LocalTime.parse(start));
            row.setEndTime(LocalTime.parse(end));
            workingHours.save(row);
          }
        });
  }

  private void block(Instant start, Instant end) {
    Tenants.acrossAll(
        () -> {
          var blocker = new CalendarBlocker();
          blocker.setConsultantId(counsellor.getId());
          blocker.setTenantId(TENANT);
          blocker.setStartTime(start);
          blocker.setEndTime(end);
          blocker.setSource(CalendarBlocker.Source.ORISO);
          blocker.setCreateDate(Instant.now());
          blockers.save(blocker);
        });
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

  private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private String times(Instant start, Instant end, String kind) throws Exception {
    var body = new HashMap<String, Object>();
    body.put("startTime", start.toString());
    body.put("endTime", end.toString());
    if (kind != null) {
      body.put("kind", kind);
    }
    return JSON.writeValueAsString(body);
  }

  private static Instant far() {
    return Instant.now().plus(Duration.ofDays(365));
  }

  private void actAsCounsellor(Consultant consultant) {
    Tenants.actAs(caller, consultant.getId(), TENANT, UserRole.CONSULTANT);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.CONSULTANT_DEFAULT));
  }

  private void actAsAsker(User user) {
    Tenants.actAs(caller, user.getUserId(), TENANT, UserRole.USER);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.USER_DEFAULT));
  }
}
