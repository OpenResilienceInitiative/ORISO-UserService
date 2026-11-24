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
import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarConsultationHours;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationOnceRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.sessionappointment.AppointmentRequestExpiry;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Self-booking (ORISO-Frontend#1050, #2287): the asker of a 1:1 conversation books a free slot of
 * its counsellor's Sprechstunden, at once or as a request the counsellor confirms or declines. Real
 * security chain, controller, service and database; only the caller is staged.
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

  /** The minimum lead time before any slot (appointment.self-booking.lead-time). */
  private static final Duration LEAD_TIME = Duration.ofHours(2);

  /** The default notice period: inside it every slot is a request. */
  private static final Duration NOTICE = Duration.ofDays(1);

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private SessionRepository sessions;
  @Autowired private CalendarWorkingHoursRepository workingHours;
  @Autowired private CalendarBlockerRepository blockers;
  @Autowired private CalendarConsultationHoursRepository consultationHours;
  @Autowired private CalendarConsultationOnceRepository consultationOnce;
  @Autowired private CalendarBookingSettingRepository bookingSettings;
  @Autowired private SessionAppointmentRepository appointmentRepository;
  @Autowired private AppointmentRequestExpiry requestExpiry;
  @Autowired private CaseHandoverRequestRepository handovers;

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
  private final List<CaseHandoverRequest> seededHandovers = new ArrayList<>();

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
    Tenants.in(TENANT, () -> handovers.deleteAll(seededHandovers));
    Tenants.acrossAll(
        () -> {
          workingHours.deleteAll(
              workingHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(
                  counsellor.getId()));
          blockers.deleteAll(blockers.findOverlapping(counsellor.getId(), Instant.EPOCH, far()));
          consultationHours.deleteAll(
              consultationHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(
                  counsellor.getId()));
          for (var id : List.of(counsellor.getId(), otherCounsellor.getId())) {
            consultationOnce.deleteAll(
                consultationOnce.findByConsultantIdAndEndAtAfterOrderByStartAtAsc(
                    id, Instant.EPOCH));
            consultationHours.deleteAll(
                consultationHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(id));
            bookingSettings.findById(id).ifPresent(bookingSettings::delete);
          }
        });
    fixtures.removeAll();
  }

  @Test
  void slots_Should_OfferHourLongTimesInsideTheSprechstunden_AfterTheLeadTime() throws Exception {
    opensEveryDay("08:00", "18:00");
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
  void slots_Should_BeEmpty_When_TheCounsellorKeepsOnlyWorkingHours() throws Exception {
    worksEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();

    assertThat(slots(session.getId(), now, now.plus(Duration.ofDays(3)))).isEmpty();
  }

  @Test
  void slots_Should_BeRequestsInsideTheNoticePeriod_And_InstantAfterIt() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();

    var slots = slots(session.getId(), now, now.plus(Duration.ofDays(3)));

    var edge = now.plus(NOTICE);
    for (var slot : slots) {
      var start = Instant.parse((String) slot.get("startTime"));
      // The server's "now" is a moment later than this one: leave the slots at the edge out.
      if (Duration.between(start, edge).abs().compareTo(Duration.ofMinutes(1)) < 0) {
        continue;
      }
      assertThat(slot.get("bookingMode"))
          .as(start.toString())
          .isEqualTo(start.isBefore(edge) ? "REQUEST" : "INSTANT");
    }
    assertThat(slots).extracting(slot -> slot.get("bookingMode")).contains("REQUEST", "INSTANT");
  }

  @Test
  void slots_Should_FollowTheCounsellorsSettings() throws Exception {
    opensEveryDay("00:00", "23:30");
    settings(false, 1, 30);
    actAsAsker(asker);
    var now = Instant.now();

    var slots = slots(session.getId(), now, now.plus(Duration.ofDays(3)));

    assertThat(slots).isNotEmpty();
    assertThat(slots).extracting(slot -> slot.get("bookingMode")).containsOnly("REQUEST");
    for (var slot : slots) {
      var start = Instant.parse((String) slot.get("startTime"));
      var end = Instant.parse((String) slot.get("endTime"));
      assertThat(Duration.between(start, end)).isEqualTo(Duration.ofMinutes(30));
    }
  }

  @Test
  void slots_Should_LeaveOutTheCounsellorsBlockers() throws Exception {
    opensEveryDay("00:00", "23:30");
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
  void book_Should_ScheduleAnInstantSlot_And_TakeItOutOfTheOffer() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();
    var start = firstSlotStart(session.getId(), now, "INSTANT");

    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId").value(session.getId()))
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.kind").value("VIDEO"))
        .andExpect(jsonPath("$.counterpart.username").isNotEmpty());

    assertThat(slotStarts(session.getId(), now)).doesNotContain(start);
    var listed = body(get("/users/sessions/" + session.getId() + "/appointments"));
    assertThat(listed).hasSize(1);
    assertThat(Instant.parse((String) listed.get(0).get("startTime"))).isEqualTo(start);
  }

  @Test
  void book_Should_RequestARequestSlot_AndBlockItForOthers_AndCountItAsTheOneAppointment()
      throws Exception {
    opensEveryDay("00:00", "23:30");
    var otherSession = fixtures.session(otherAsker, AGENCY, counsellor);
    actAsAsker(asker);
    var now = Instant.now();
    var start = firstSlotStart(session.getId(), now, "REQUEST");
    var later = firstSlotStart(session.getId(), now, "INSTANT");

    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "PHONE"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.kind").value("PHONE"));

    mockMvc
        .perform(book(session.getId(), later, later.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "SELF_BOOKING_LIMIT_REACHED"));
    actAsAsker(otherAsker);
    assertThat(slotStarts(otherSession.getId(), now)).doesNotContain(start);
    mockMvc
        .perform(book(otherSession.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isConflict());
  }

  @Test
  void confirm_Should_ScheduleTheRequest_ForTheCounsellorOnly() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);

    mockMvc.perform(decide(id, "confirm")).andExpect(status().isForbidden());
    actAsCounsellor(otherCounsellor);
    mockMvc.perform(decide(id, "confirm")).andExpect(status().isNotFound());

    actAsCounsellor(counsellor);
    mockMvc
        .perform(decide(id, "confirm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    mockMvc
        .perform(decide(id, "confirm"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "APPOINTMENT_NOT_REQUESTED"));
    mockMvc
        .perform(decide(id, "decline"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "APPOINTMENT_NOT_REQUESTED"));
  }

  @Test
  void coAccessCounsellor_Should_NotDecideARequest() throws Exception {
    opensEveryDay("00:00", "23:30");
    grantCoAccess(otherCounsellor);
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);

    // A request is the conversation's own counsellor's to answer (#2287), co-access or not.
    actAsCounsellor(otherCounsellor);
    mockMvc.perform(decide(id, "confirm")).andExpect(status().isForbidden());
    mockMvc.perform(decide(id, "decline")).andExpect(status().isForbidden());

    actAsCounsellor(counsellor);
    mockMvc
        .perform(decide(id, "confirm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
  }

  @Test
  void coAccessCounsellorsAppointments_Should_NotLimitTheOffer() throws Exception {
    opensEveryDay("00:00", "23:30");
    grantCoAccess(otherCounsellor);
    actAsAsker(asker);
    var now = Instant.now();
    var start = firstSlotStart(session.getId(), now, "INSTANT");
    var colleaguesConversation = fixtures.session(otherAsker, AGENCY, otherCounsellor);
    actAsCounsellor(otherCounsellor);
    mockMvc
        .perform(
            csrf(post("/users/sessions/" + colleaguesConversation.getId() + "/appointments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(times(start, start.plus(Duration.ofHours(1)), "VIDEO")))
        .andExpect(status().isCreated());

    // Frank, 17 Nov 2026: only the conversation's own counsellor's calendar limits the offer;
    // a co-access counsellor's appointments do not, though they see both in their calendar.
    actAsAsker(asker);
    assertThat(slotStarts(session.getId(), now)).contains(start);
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
  }

  @Test
  void confirm_Should_Refuse_When_SomethingElseTakesTheTimeNow() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);
    block(start.plus(Duration.ofMinutes(30)), start.plus(Duration.ofMinutes(45)));

    actAsCounsellor(counsellor);
    mockMvc
        .perform(decide(id, "confirm"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "REQUESTED_TIME_TAKEN"));
    mockMvc
        .perform(decide(id, "decline"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DECLINED"));
  }

  @Test
  void decline_Should_FreeTheTime_And_TheConversationsOneAppointment() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();
    var start = firstSlotStart(session.getId(), now, "REQUEST");
    var id = booked(start);

    mockMvc.perform(decide(id, "decline")).andExpect(status().isForbidden());
    actAsCounsellor(counsellor);
    mockMvc
        .perform(decide(id, "decline"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DECLINED"));
    mockMvc
        .perform(
            csrf(MockMvcRequestBuilders.put(
                    "/users/sessions/" + session.getId() + "/appointments/" + id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(times(start, start.plus(Duration.ofHours(1)), null)))
        .andExpect(status().isConflict());

    actAsAsker(asker);
    assertThat(slotStarts(session.getId(), now)).contains(start);
    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated());
  }

  @Test
  void upcoming_Should_ListARequest_ForItsCounsellorAndItsAsker_Only() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);

    assertThat(upcoming()).containsEntry(id, "REQUESTED");
    actAsCounsellor(counsellor);
    assertThat(upcoming()).containsEntry(id, "REQUESTED");
    actAsCounsellor(otherCounsellor);
    assertThat(upcoming()).doesNotContainKey(id);
    actAsAsker(otherAsker);
    assertThat(upcoming()).doesNotContainKey(id);
  }

  @Test
  void upcoming_Should_ShowTheConfirmedRequestAsScheduled_And_LeaveOutADeclinedOne()
      throws Exception {
    opensEveryDay("00:00", "23:30");
    var otherSession = fixtures.session(otherAsker, AGENCY, counsellor);
    actAsAsker(asker);
    var now = Instant.now();
    var confirmed = booked(firstSlotStart(session.getId(), now, "REQUEST"));
    actAsAsker(otherAsker);
    var declinedStart = firstSlotStart(otherSession.getId(), now, "REQUEST");
    var declined =
        (String)
            JSON.readValue(
                    mockMvc
                        .perform(
                            book(
                                otherSession.getId(),
                                declinedStart,
                                declinedStart.plus(Duration.ofHours(1)),
                                "VIDEO"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                    Map.class)
                .get("id");

    actAsCounsellor(counsellor);
    mockMvc.perform(decide(confirmed, "confirm")).andExpect(status().isOk());
    mockMvc
        .perform(
            csrf(
                post(
                    "/users/sessions/"
                        + otherSession.getId()
                        + "/appointments/"
                        + declined
                        + "/decline")))
        .andExpect(status().isOk());

    assertThat(upcoming()).containsEntry(confirmed, "SCHEDULED").doesNotContainKey(declined);
    actAsAsker(otherAsker);
    assertThat(upcoming()).doesNotContainKey(declined);
  }

  @Test
  void calendar_Should_ShowARequestProvisionally_ToItsCounsellorOnly() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();
    var start = firstSlotStart(session.getId(), now, "REQUEST");
    var id = booked(start);

    actAsCounsellor(counsellor);
    var entry = calendarEntry(id, now);
    assertThat(entry).isNotNull();
    assertThat(entry.get("type")).isEqualTo("APPOINTMENT");
    assertThat(entry.get("requested")).isEqualTo(true);
    assertThat(entry.get("movable")).isEqualTo(false);
    assertThat(entry.get("sessionId")).isEqualTo(session.getId().intValue());

    actAsCounsellor(otherCounsellor);
    assertThat(calendarEntry(id, now)).isNull();

    actAsCounsellor(counsellor);
    mockMvc.perform(decide(id, "confirm")).andExpect(status().isOk());
    var confirmed = calendarEntry(id, now);
    assertThat(confirmed.get("requested")).isNotEqualTo(true);
    assertThat(confirmed.get("movable")).isEqualTo(true);
  }

  @Test
  void calendar_Should_LeaveOutADeclinedRequest() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var now = Instant.now();
    var id = booked(firstSlotStart(session.getId(), now, "REQUEST"));

    actAsCounsellor(counsellor);
    mockMvc.perform(decide(id, "decline")).andExpect(status().isOk());
    assertThat(calendarEntry(id, now)).isNull();
  }

  @Test
  void expiry_Should_DeclineAnUnansweredRequest_AtItsStart_AndNotAMomentBefore() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);

    assertThat(requestExpiry.declineUnanswered(start.minusMillis(1))).isZero();
    assertThat(upcoming()).containsEntry(id, "REQUESTED");

    assertThat(requestExpiry.declineUnanswered(start)).isEqualTo(1);
    assertThat(upcoming()).doesNotContainKey(id);
    var row = sessionAppointment(id);
    assertThat(row.get("status")).isEqualTo("DECLINED");
    assertThat(row.get("expired")).isEqualTo(true);
    assertThat(row.get("noticePending")).isEqualTo(true);
    assertThat(requestExpiry.declineUnanswered(start.plusSeconds(60))).isZero();
  }

  @Test
  void expiry_Should_LeaveConfirmedAndDeclinedAppointmentsAlone() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);
    actAsCounsellor(counsellor);
    mockMvc.perform(decide(id, "confirm")).andExpect(status().isOk());

    assertThat(requestExpiry.declineUnanswered(start.plusSeconds(1))).isZero();
    var row = sessionAppointment(id);
    assertThat(row.get("status")).isEqualTo("SCHEDULED");
    assertThat(row.get("expired")).isNotEqualTo(true);
  }

  @Test
  void startedRequest_Should_NoLongerBlockTheConversationsOneAppointment_EvenBeforeTheSweep()
      throws Exception {
    opensEveryDay("00:00", "23:30");
    var now = Instant.now();
    var started = storedRequest(now.minus(Duration.ofMinutes(10)));

    actAsAsker(asker);
    assertThat(upcoming()).doesNotContainKey(started);
    var later = firstSlotStart(session.getId(), now, "INSTANT");
    mockMvc
        .perform(book(session.getId(), later, later.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated());
    actAsCounsellor(counsellor);
    assertThat(upcoming()).doesNotContainKey(started);
    assertThat(calendarEntry(started, now.minus(Duration.ofHours(1)))).isNull();
  }

  @Test
  void expiryNotice_Should_BeClaimedOnce_ByAParticipant() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);
    var notice =
        csrf(post("/users/sessions/" + session.getId() + "/appointments/" + id + "/expiry-notice"));

    mockMvc.perform(notice).andExpect(status().isConflict());
    requestExpiry.declineUnanswered(start);

    actAsAsker(otherAsker);
    mockMvc.perform(notice).andExpect(status().isNotFound());
    actAsCounsellor(otherCounsellor);
    mockMvc.perform(notice).andExpect(status().isNotFound());

    actAsAsker(asker);
    mockMvc.perform(notice).andExpect(status().isNoContent());
    assertThat(sessionAppointment(id).get("noticePending")).isNotEqualTo(true);
    actAsCounsellor(counsellor);
    mockMvc.perform(notice).andExpect(status().isConflict());
  }

  @Test
  void reschedule_Should_Refuse_ARequestWaitingForConfirmation() throws Exception {
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now(), "REQUEST");
    var id = booked(start);

    actAsCounsellor(counsellor);
    var later = start.plus(Duration.ofDays(2));
    mockMvc
        .perform(
            csrf(MockMvcRequestBuilders.put(
                    "/users/sessions/" + session.getId() + "/appointments/" + id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(times(later, later.plus(Duration.ofHours(1)), null)))
        .andExpect(status().isConflict());
  }

  @Test
  void consultationHours_Should_BeTheCounsellorsOwn() throws Exception {
    actAsCounsellor(counsellor);
    mockMvc
        .perform(
            csrf(MockMvcRequestBuilders.put("/users/calendar/consultation-hours"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"windows\":[{\"weekday\":2,\"start\":\"09:00\",\"end\":\"11:00\"}],"
                        + "\"autoAccept\":false,\"noticeDays\":2,\"slotMinutes\":30}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windows[0].weekday").value(2))
        .andExpect(jsonPath("$.autoAccept").value(false));
    mockMvc
        .perform(get("/users/calendar/consultation-hours"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windows[0].start").value("09:00"))
        .andExpect(jsonPath("$.noticeDays").value(2))
        .andExpect(jsonPath("$.slotMinutes").value(30));
    actAsCounsellor(otherCounsellor);
    mockMvc
        .perform(get("/users/calendar/consultation-hours"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windows").isEmpty())
        .andExpect(jsonPath("$.slotMinutes").value(60));

    actAsAsker(asker);
    mockMvc.perform(get("/users/calendar/consultation-hours")).andExpect(status().isForbidden());
  }

  @Test
  void consultationOnce_Should_BeTheCounsellorsOwn_And_AskBeforeLyingOverABlocker()
      throws Exception {
    actAsCounsellor(counsellor);
    var start = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(3));
    var end = start.plus(Duration.ofHours(2));
    block(start, start.plus(Duration.ofMinutes(30)));

    mockMvc
        .perform(createOnce(start, end, 30, false))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "CONSULTATION_ONCE_OVER_BLOCKER"));
    mockMvc.perform(createOnce(start, end, 45, true)).andExpect(status().isBadRequest());
    var created =
        JSON.readValue(
            mockMvc
                .perform(createOnce(start, end, 30, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.noticeMinutes").value(30))
                .andExpect(jsonPath("$.overBlocker").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);
    mockMvc
        .perform(
            createOnce(start.plus(Duration.ofHours(1)), end.plus(Duration.ofHours(1)), 0, true))
        .andExpect(status().isBadRequest());
    var id = (String) created.get("id");
    assertThat(body(get("/users/calendar/consultation-hours/once")))
        .extracting(row -> row.get("id"))
        .containsExactly(id);

    actAsCounsellor(otherCounsellor);
    assertThat(body(get("/users/calendar/consultation-hours/once"))).isEmpty();
    mockMvc
        .perform(
            csrf(MockMvcRequestBuilders.delete("/users/calendar/consultation-hours/once/" + id)))
        .andExpect(status().isNotFound());

    actAsAsker(asker);
    mockMvc
        .perform(get("/users/calendar/consultation-hours/once"))
        .andExpect(status().isForbidden());

    actAsCounsellor(counsellor);
    mockMvc
        .perform(
            csrf(MockMvcRequestBuilders.delete("/users/calendar/consultation-hours/once/" + id)))
        .andExpect(status().isNoContent());
    assertThat(body(get("/users/calendar/consultation-hours/once"))).isEmpty();
  }

  @Test
  void book_Should_TakeAOneOffSlot_InsideTheMinimumLeadTime_AfterItsOwnNotice() throws Exception {
    // No weekly Sprechstunden; a one-off starting in about an hour, bookable until 30 min before.
    var start = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(61));
    var end = start.plus(Duration.ofHours(1));
    actAsCounsellor(counsellor);
    mockMvc.perform(createOnce(start, end, 30, false)).andExpect(status().isCreated());

    actAsAsker(asker);
    var now = Instant.now();
    assertThat(slotStarts(session.getId(), now)).containsExactly(start);
    mockMvc
        .perform(book(session.getId(), start, end, "VIDEO"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
  }

  @Test
  void book_Should_RefuseAOneOffSlot_InsideItsOwnNotice() throws Exception {
    var start = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(61));
    var end = start.plus(Duration.ofHours(1));
    actAsCounsellor(counsellor);
    mockMvc.perform(createOnce(start, end, 120, false)).andExpect(status().isCreated());

    actAsAsker(asker);
    assertThat(slotStarts(session.getId(), Instant.now())).isEmpty();
    mockMvc
        .perform(book(session.getId(), start, end, "VIDEO"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "SLOT_OUTSIDE_BOOKING_WINDOW"));
  }

  @Test
  void book_Should_Refuse_When_TheTimeIsTakenMeanwhile() throws Exception {
    opensEveryDay("00:00", "23:30");
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
    opensEveryDay("08:00", "18:00");
    actAsAsker(asker);
    var start = firstSlotStart(session.getId(), Instant.now());

    // Inside the lead time.
    var soon = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.HOURS);
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
    opensEveryDay("00:00", "23:30");
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
    opensEveryDay("00:00", "23:30");
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
    opensEveryDay("00:00", "23:30");
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
    opensEveryDay("00:00", "23:30");
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
  // Replaces the class-level principal: only the anonymous authority reaches the booking route.
  @WithMockUser(authorities = AuthorityValue.ANONYMOUS_DEFAULT)
  void anonymousAsker_Should_BookLikeARegisteredOne() throws Exception {
    opensEveryDay("00:00", "23:30");
    Tenants.actAs(caller, asker.getUserId(), TENANT, UserRole.ANONYMOUS);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.ANONYMOUS_DEFAULT));
    var start = firstSlotStart(session.getId(), Instant.now());

    mockMvc
        .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
        .andExpect(status().isCreated());
  }

  @Test
  void book_Should_AllowOnlyOneUpcomingAppointmentPerConversation() throws Exception {
    opensEveryDay("00:00", "23:30");
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
    opensEveryDay("00:00", "23:30");
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
    opensEveryDay("00:00", "23:30");
    actAsAsker(asker);
    var beyond = Instant.now().plus(Duration.ofDays(15)).truncatedTo(ChronoUnit.HOURS);
    var soon = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.HOURS);

    for (var start : List.of(beyond, soon)) {
      mockMvc
          .perform(book(session.getId(), start, start.plus(Duration.ofHours(1)), "VIDEO"))
          .andExpect(status().isConflict())
          .andExpect(header().string("X-Reason", "SLOT_OUTSIDE_BOOKING_WINDOW"));
    }
  }

  @Test
  void slots_Should_OfferNothingBeyondTheBookingHorizon() throws Exception {
    opensEveryDay("00:00", "23:30");
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

  /** One appointment of the conversation as the caller's session list shows it. */
  private Map<String, Object> sessionAppointment(String id) throws Exception {
    return body(get("/users/sessions/" + session.getId() + "/appointments")).stream()
        .filter(row -> id.equals(row.get("id")))
        .findFirst()
        .orElseThrow();
  }

  /** A request stored directly, e.g. one whose start has passed; returns its id. */
  private String storedRequest(Instant start) {
    return Tenants.acrossAll(
        () -> {
          var appointment = new SessionAppointment();
          appointment.setSession(sessions.findById(session.getId()).orElseThrow());
          appointment.setStatus(SessionAppointment.Status.REQUESTED);
          appointment.setKind(SessionAppointment.Kind.VIDEO);
          appointment.setStartTime(start);
          appointment.setEndTime(start.plus(Duration.ofHours(1)));
          return appointmentRepository.save(appointment).getId().toString();
        });
  }

  /** The caller's upcoming appointments as id → status. */
  private Map<String, String> upcoming() throws Exception {
    var found = new HashMap<String, String>();
    body(get("/users/appointments/upcoming"))
        .forEach(row -> found.put((String) row.get("id"), (String) row.get("status")));
    return found;
  }

  /** The caller's own calendar entry of that id over the next three days, or null. */
  private Map<String, Object> calendarEntry(String id, Instant now) throws Exception {
    return body(
            get("/users/calendar/entries")
                .param("from", now.toString())
                .param("to", now.plus(Duration.ofDays(3)).toString()))
        .stream()
        .filter(entry -> id.equals(entry.get("id")))
        .findFirst()
        .orElse(null);
  }

  private Instant firstSlotStart(long sessionId, Instant now) throws Exception {
    var slots = slots(sessionId, now, now.plus(Duration.ofDays(3)));
    assertThat(slots).isNotEmpty();
    return Instant.parse((String) slots.get(0).get("startTime"));
  }

  /** The first offered slot of the mode, an hour away from the notice period's end. */
  private Instant firstSlotStart(long sessionId, Instant now, String mode) throws Exception {
    var edge = now.plus(NOTICE);
    return slots(sessionId, now, now.plus(Duration.ofDays(3))).stream()
        .filter(slot -> mode.equals(slot.get("bookingMode")))
        .map(slot -> Instant.parse((String) slot.get("startTime")))
        .filter(start -> Duration.between(start, edge).abs().compareTo(Duration.ofHours(1)) > 0)
        .findFirst()
        .orElseThrow();
  }

  private List<Instant> slotStarts(long sessionId, Instant now) throws Exception {
    return slots(sessionId, now, now.plus(Duration.ofDays(3))).stream()
        .map(slot -> Instant.parse((String) slot.get("startTime")))
        .toList();
  }

  private MockHttpServletRequestBuilder decide(String appointmentId, String decision) {
    return csrf(
        post(
            "/users/sessions/"
                + session.getId()
                + "/appointments/"
                + appointmentId
                + "/"
                + decision));
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

  private MockHttpServletRequestBuilder createOnce(
      Instant start, Instant end, int noticeMinutes, boolean overBlocker) throws Exception {
    var body = new HashMap<String, Object>();
    body.put("start", start.toString());
    body.put("end", end.toString());
    body.put("noticeMinutes", noticeMinutes);
    body.put("overBlocker", overBlocker);
    return csrf(post("/users/calendar/consultation-hours/once"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(body));
  }

  private void opensEveryDay(String start, String end) {
    Tenants.acrossAll(
        () -> {
          for (int weekday = 0; weekday <= 6; weekday++) {
            var row = new CalendarConsultationHours();
            row.setConsultantId(counsellor.getId());
            row.setWeekday(weekday);
            row.setStartTime(LocalTime.parse(start));
            row.setEndTime(LocalTime.parse(end));
            consultationHours.save(row);
          }
        });
  }

  private void settings(boolean autoAccept, int noticeDays, int slotMinutes) {
    Tenants.acrossAll(
        () -> {
          var setting = new CalendarBookingSetting();
          setting.setConsultantId(counsellor.getId());
          setting.setAutoAccept(autoAccept);
          setting.setNoticeDays(noticeDays);
          setting.setSlotMinutes(slotMinutes);
          bookingSettings.save(setting);
        });
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

  /** A live co-access grant on the conversation for a colleague (case handover, ADR-032). */
  private void grantCoAccess(Consultant colleague) {
    var request =
        CaseHandoverRequest.builder()
            .session(session)
            .requesterConsultant(colleague)
            .reasonCode("ADVICE_NEEDED")
            .reasonLabel("Advice needed")
            .explanation("synthetic")
            .status(CaseHandoverRequest.Status.GRANTED)
            .clientConsentRequired(false)
            .clientConsent(CaseHandoverConsentMode.NONE)
            .policyAuthority("synthetic")
            .auditOutcome("synthetic")
            .createdAt(LocalDateTime.now())
            .accessType(AccessType.CO_ACCESS)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .tenantId(TENANT)
            .build();
    seededHandovers.add(Tenants.in(TENANT, () -> handovers.save(request)));
  }
}
