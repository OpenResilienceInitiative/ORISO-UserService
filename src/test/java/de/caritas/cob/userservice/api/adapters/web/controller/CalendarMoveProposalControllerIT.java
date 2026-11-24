package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
import de.caritas.cob.userservice.api.model.CalendarMoveProposal;
import de.caritas.cob.userservice.api.model.CalendarMoveProposal.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarMoveProposalRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.calendar.CalendarPolicy;
import de.caritas.cob.userservice.api.service.calendar.CalendarPolicyResolver;
import de.caritas.cob.userservice.api.service.calendar.CalendarVisibilityScope;
import de.caritas.cob.userservice.api.service.notification.CalendarMoveProposalEmailNotification;
import de.caritas.cob.userservice.api.service.notification.CalendarMoveProposalMailSender;
import de.caritas.cob.userservice.api.service.sessionappointment.SessionAppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantCalendarAction;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Move proposals (ADR-032, move proposals v1) through the real security chain, controller, services
 * and database. Counsellor A proposes, counsellor B owns the conversation and decides.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true", "feature.appointment.enabled=false"})
@Import(TenantFixtures.class)
@WithTenant(CalendarMoveProposalControllerIT.TENANT)
@WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.USER_DEFAULT})
class CalendarMoveProposalControllerIT {

  static final long TENANT = 1L;
  private static final long AGENCY = 9831L;
  private static final long OTHER_AGENCY = 9832L;
  private static final long OTHER_TENANT = 2L;
  private static final long FOREIGN_AGENCY = 9833L;
  private static final String PROPOSALS = "/users/calendar/proposals";
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private SessionRepository sessions;
  @Autowired private CalendarMoveProposalRepository proposals;
  @Autowired private CalendarBlockerRepository blockers;
  @Autowired private DeleteConsultantCalendarAction deleteCalendar;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean CalendarPolicyResolver policies;
  @MockitoBean CalendarMoveProposalMailSender mailSender;
  @MockitoSpyBean SessionAppointmentService sessionAppointments;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant proposer;
  private Consultant owner;
  private Consultant thirdColleague;
  private Consultant outOfScope;
  private User asker;
  private Session session;
  private final List<UUID> seededBlockers = new ArrayList<>();
  private Instant base;

  @BeforeEach
  void seed() {
    base = Instant.now().truncatedTo(ChronoUnit.HOURS);
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(agencyService.getAgencies(anyList()))
        .thenReturn(List.of(new AgencyDTO().id(AGENCY).name("Synthetic Centre")));
    when(policies.resolve(any()))
        .thenReturn(new CalendarPolicy(CalendarVisibilityScope.AGENCY, false));
    proposer = fixtures.consultant(TENANT, AGENCY);
    owner = fixtures.consultant(TENANT, AGENCY);
    thirdColleague = fixtures.consultant(TENANT, AGENCY);
    outOfScope = fixtures.consultant(TENANT, OTHER_AGENCY);
    asker = fixtures.adviceSeeker(TENANT);
    session = fixtures.session(asker, AGENCY, owner);
  }

  @AfterEach
  void removeSeededRows() {
    Tenants.acrossAll(
        () -> {
          proposals.deleteAll();
          blockers.deleteAllById(seededBlockers);
        });
    fixtures.removeAll();
  }

  @Test
  void create_Should_Answer201_And_MailTheOwner_AfterTheCommit() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);

    var created = propose(appointment, in(72), in(73));

    assertThat(created)
        .containsEntry("entryId", appointment.toString())
        .containsEntry("proposerId", proposer.getId())
        .containsEntry("approverId", owner.getId())
        .containsKeys("id", "start", "end", "entryStart", "entryEnd", "createdAt", "proposerName");
    assertThat(Instant.parse((String) created.get("entryStart"))).isEqualTo(in(48));
    assertThat(Instant.parse((String) created.get("start"))).isEqualTo(in(72));
    assertThat(created.get("approverName")).isEqualTo(owner.getFullName());
    var mail = ArgumentCaptor.forClass(CalendarMoveProposalEmailNotification.Mail.class);
    verify(mailSender, timeout(5_000)).send(mail.capture());
    assertThat(mail.getValue().approverId()).isEqualTo(owner.getId());
    assertThat(mail.getValue().proposerName()).isEqualTo(proposer.getFullName());
    assertThat(mail.getValue().currentStart()).isEqualTo(in(48));
    assertThat(mail.getValue().proposedStart()).isEqualTo(in(72));
    assertThat(mail.getValue().proposalId().toString()).isEqualTo(created.get("id"));
  }

  @Test
  void create_Should_StillAnswer201_When_TheMailCannotBeSent() throws Exception {
    doThrow(new IllegalStateException("smtp down")).when(mailSender).send(any());
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);

    var created = propose(appointment, in(72), in(73));

    verify(mailSender, timeout(5_000)).send(any());
    assertThat(sentIds()).containsExactly(created.get("id"));
  }

  @Test
  void create_Should_BeRefused_ForEveryInvalidRequest() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);

    expect(proposal(appointment.toString(), in(72), in(72)), status().isBadRequest());
    expect(proposal(appointment.toString(), in(73), in(72)), status().isBadRequest());
    expect(proposal(appointment.toString(), in(-2), in(1)), status().isBadRequest());
    expect(proposal("chat-12-0", in(72), in(73)), status().isBadRequest());
    var blocker = blockerOf(owner, in(80), in(81));
    expect(proposal(blocker.toString(), in(72), in(73)), status().isBadRequest());
    var begun = appointmentOf(in(-1), in(1));
    expect(proposal(begun.toString(), in(72), in(73)), status().isBadRequest());
    expect(proposal(UUID.randomUUID().toString(), in(72), in(73)), status().isNotFound());

    actAsCounsellor(owner);
    expect(proposal(appointment.toString(), in(72), in(73)), status().isBadRequest());

    actAsCounsellor(outOfScope);
    expect(proposal(appointment.toString(), in(72), in(73)), status().isForbidden());

    assertThat(count(Status.OPEN)).isZero();
    verify(mailSender, after(300).never()).send(any());
  }

  /** ORISO-UserService#1663: the reason is stored, shown to the owner, and kept out of the mail. */
  @Test
  void create_Should_KeepTheReason_ForTheOwner_When_ItFitsIn500Characters() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);

    expect(
        proposal(appointment.toString(), in(72), in(73), "x".repeat(501)), status().isBadRequest());
    var created =
        parseObject(
            mockMvc
                .perform(proposal(appointment.toString(), in(72), in(73), " Room taken "))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(created).containsEntry("reason", "Room taken");
    actAsCounsellor(owner);
    assertThat(listing().get("received").get(0)).containsEntry("reason", "Room taken");
  }

  @Test
  void create_Should_ReplaceTheProposersOpenProposal_ForTheSameAppointment() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);
    var first = propose(appointment, in(72), in(73));

    var second = propose(appointment, in(96), in(97));

    assertThat(sentIds()).containsExactly(second.get("id"));
    assertThat(statusOf(first)).isEqualTo(Status.WITHDRAWN);
    actAsCounsellor(owner);
    assertThat(receivedIds()).containsExactly(second.get("id"));
  }

  @Test
  void list_Should_ShowOnlyOpenLiveProposals_ToProposerAndOwner() throws Exception {
    var live = appointmentOf(in(48), in(49));
    var moved = appointmentOf(in(50), in(51));
    var declined = appointmentOf(in(52), in(53));
    actAsCounsellor(proposer);
    var liveProposal = propose(live, in(72), in(73));
    propose(moved, in(74), in(75));
    var declinedProposal = propose(declined, in(76), in(77));
    var lapsed = storedProposal(live, thirdColleague, in(-1));

    actAsCounsellor(owner);
    moveAppointment(moved, in(60), in(61));
    decide(declinedProposal, "decline", status().isNoContent());

    assertThat(receivedIds()).containsExactly(liveProposal.get("id"));
    var received = listing().get("received");
    assertThat(received.get(0)).containsEntry("proposerName", proposer.getFullName());
    assertThat(sentIds()).isEmpty();
    actAsCounsellor(proposer);
    assertThat(sentIds()).containsExactly(liveProposal.get("id"));
    assertThat(proposals.findById(lapsed).orElseThrow().getStatus())
        .as("a lapsed proposal is only left out, not changed")
        .isEqualTo(Status.OPEN);
  }

  @Test
  void withdraw_Should_BeTheProposersAlone_And_Idempotent() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);
    var created = propose(appointment, in(72), in(73));

    actAsCounsellor(owner);
    mockMvc
        .perform(csrf(delete(PROPOSALS + "/" + created.get("id"))))
        .andExpect(status().isForbidden());
    actAsCounsellor(proposer);
    mockMvc
        .perform(csrf(delete(PROPOSALS + "/" + created.get("id"))))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(csrf(delete(PROPOSALS + "/" + created.get("id"))))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(csrf(delete(PROPOSALS + "/" + UUID.randomUUID())))
        .andExpect(status().isNotFound());

    assertThat(statusOf(created)).isEqualTo(Status.WITHDRAWN);
    assertThat(sentIds()).isEmpty();
    actAsCounsellor(owner);
    assertThat(receivedIds()).isEmpty();
  }

  @Test
  void decline_Should_BeTheOwnersAlone_And_LeaveTheAppointment() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);
    var created = propose(appointment, in(72), in(73));

    decide(created, "decline", status().isForbidden());
    actAsCounsellor(owner);
    decide(created, "decline", status().isNoContent());

    assertThat(statusOf(created)).isEqualTo(Status.DECLINED);
    assertThat(startOf(appointment)).isEqualTo(in(48));
    decide(created, "accept", status().isConflict());
  }

  @Test
  void accept_Should_MoveThroughTheAppointmentPath_And_SupersedeTheOthers() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);
    var accepted = propose(appointment, in(72), in(73));
    actAsCounsellor(thirdColleague);
    var other = propose(appointment, in(96), in(97));

    actAsCounsellor(proposer);
    decide(accepted, "accept", status().isForbidden());
    actAsCounsellor(owner);
    var entry = body(csrf(post(PROPOSALS + "/" + accepted.get("id") + "/accept")));

    verify(sessionAppointments).reschedule(any(), any(), any());
    assertThat(entry)
        .containsEntry("id", appointment.toString())
        .containsEntry("type", "APPOINTMENT")
        .containsEntry("ownerId", owner.getId())
        .containsEntry("sessionId", session.getId().intValue())
        .containsEntry("title", asker.getUsername());
    assertThat(Instant.parse((String) entry.get("start"))).isEqualTo(in(72));
    assertThat(startOf(appointment)).isEqualTo(in(72));
    assertThat(statusOf(accepted)).isEqualTo(Status.ACCEPTED);
    assertThat(statusOf(other)).isEqualTo(Status.SUPERSEDED);
    assertThat(receivedIds()).isEmpty();
    // The advice seeker sees the moved appointment like any other move.
    actAsAsker(asker);
    var upcoming = list(get("/users/appointments/upcoming"));
    assertThat(upcoming).hasSize(1);
    assertThat(Instant.parse((String) upcoming.get(0).get("startTime"))).isEqualTo(in(72));
  }

  @Test
  void accept_Should_Conflict_When_TheAppointmentWasMovedMeanwhile() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);
    var created = propose(appointment, in(72), in(73));
    actAsCounsellor(owner);
    moveAppointment(appointment, in(60), in(61));

    decide(created, "accept", status().isConflict());

    assertThat(startOf(appointment)).isEqualTo(in(60));
    assertThat(statusOf(created)).isEqualTo(Status.OPEN);
  }

  @Test
  void accept_Should_RunTheChecksOfTheMovePath() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);
    var created = propose(appointment, in(72), in(73));
    Tenants.in(
        TENANT,
        () -> {
          var finished = sessions.findById(session.getId()).orElseThrow();
          finished.setStatus(SessionStatus.DONE);
          sessions.save(finished);
        });

    actAsCounsellor(owner);
    decide(created, "accept", status().isConflict());

    assertThat(startOf(appointment)).isEqualTo(in(48));
    assertThat(statusOf(created)).isEqualTo(Status.OPEN);
  }

  @Test
  void deleteAction_Should_RemoveTheProposalsOfADeletedCounsellor_InBothRoles() throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    actAsCounsellor(proposer);
    var sentByProposer = propose(appointment, in(72), in(73));
    actAsCounsellor(thirdColleague);
    var sentByThird = propose(appointment, in(96), in(97));

    deleteCalendar.execute(new ConsultantDeletionWorkflowDTO(proposer, new ArrayList<>()));

    assertThat(exists(sentByProposer)).as("made by the deleted counsellor").isFalse();
    assertThat(exists(sentByThird)).isTrue();

    deleteCalendar.execute(new ConsultantDeletionWorkflowDTO(owner, new ArrayList<>()));

    assertThat(exists(sentByThird)).as("asked of the deleted counsellor").isFalse();
  }

  @Test
  void platformScope_Should_LetAColleagueOfAnotherTraegerPropose_AndTheOwnerDecide()
      throws Exception {
    var appointment = appointmentOf(in(48), in(49));
    var foreign = fixtures.consultant(OTHER_TENANT, FOREIGN_AGENCY);
    actAsCounsellor(foreign);
    expect(proposal(appointment.toString(), in(72), in(73)), status().isForbidden());

    when(policies.resolve(any()))
        .thenReturn(new CalendarPolicy(CalendarVisibilityScope.PLATFORM, false));
    var created = propose(appointment, in(72), in(73));

    assertThat(listing().get("sent").get(0)).containsEntry("approverName", owner.getFullName());
    actAsCounsellor(owner);
    assertThat(listing().get("received").get(0))
        .containsEntry("id", created.get("id"))
        .containsEntry("proposerName", foreign.getFullName());
    decide(created, "accept", status().isOk());
    assertThat(startOf(appointment)).isEqualTo(in(72));
  }

  @Test
  void asker_Should_BeForbidden() throws Exception {
    actAsAsker(asker);

    mockMvc.perform(get(PROPOSALS)).andExpect(status().isForbidden());
  }

  @Test
  @WithAnonymousUser
  void unauthenticatedCaller_Should_BeUnauthorized() throws Exception {
    mockMvc.perform(get(PROPOSALS)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(proposal(UUID.randomUUID().toString(), in(72), in(73)))
        .andExpect(status().isUnauthorized());
  }

  private UUID appointmentOf(Instant start, Instant end) {
    var appointment = new SessionAppointment();
    appointment.setSession(session);
    appointment.setStartTime(start);
    appointment.setEndTime(end);
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    appointment.setKind(SessionAppointment.Kind.VIDEO);
    return Tenants.acrossAll(() -> appointments.save(appointment)).getId();
  }

  private UUID blockerOf(Consultant of, Instant start, Instant end) {
    var blocker = new CalendarBlocker();
    blocker.setConsultantId(of.getId());
    blocker.setTenantId(of.getTenantId());
    blocker.setStartTime(start);
    blocker.setEndTime(end);
    blocker.setSource(CalendarBlocker.Source.ORISO);
    blocker.setCreateDate(Instant.now());
    var id = blockers.save(blocker).getId();
    seededBlockers.add(id);
    return id;
  }

  /** A proposal written directly: the API refuses one whose time has passed. */
  private UUID storedProposal(UUID appointmentId, Consultant from, Instant proposedStart) {
    return Tenants.acrossAll(
        () -> {
          var appointment = appointments.findById(appointmentId).orElseThrow();
          var proposal = new CalendarMoveProposal();
          proposal.setAppointment(appointment);
          proposal.setProposerId(from.getId());
          proposal.setApproverId(owner.getId());
          proposal.setProposedStart(proposedStart);
          proposal.setProposedEnd(proposedStart.plus(1, ChronoUnit.HOURS));
          proposal.setEntryStart(appointment.getStartTime());
          proposal.setEntryEnd(appointment.getEndTime());
          proposal.setStatus(Status.OPEN);
          proposal.setCreatedAt(Instant.now());
          return proposals.save(proposal).getId();
        });
  }

  private void moveAppointment(UUID appointment, Instant start, Instant end) throws Exception {
    mockMvc
        .perform(
            csrf(put("/users/sessions/" + session.getId() + "/appointments/" + appointment))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of("startTime", start.toString(), "endTime", end.toString()))))
        .andExpect(status().isOk());
  }

  private Map<String, Object> propose(UUID appointment, Instant start, Instant end)
      throws Exception {
    return parseObject(
        mockMvc
            .perform(proposal(appointment.toString(), start, end))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private MockHttpServletRequestBuilder proposal(
      String entryId, Instant start, Instant end, String reason) throws Exception {
    return csrf(post(PROPOSALS))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            JSON.writeValueAsString(
                Map.of(
                    "entryId",
                    entryId,
                    "start",
                    start.toString(),
                    "end",
                    end.toString(),
                    "reason",
                    reason)));
  }

  private MockHttpServletRequestBuilder proposal(String entryId, Instant start, Instant end)
      throws Exception {
    return csrf(post(PROPOSALS))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            JSON.writeValueAsString(
                Map.of("entryId", entryId, "start", start.toString(), "end", end.toString())));
  }

  private void decide(Map<String, Object> proposal, String decision, ResultMatcher expected)
      throws Exception {
    mockMvc
        .perform(csrf(post(PROPOSALS + "/" + proposal.get("id") + "/" + decision)))
        .andExpect(expected);
  }

  private void expect(MockHttpServletRequestBuilder request, ResultMatcher expected)
      throws Exception {
    mockMvc.perform(request).andExpect(expected);
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<Map<String, Object>>> listing() throws Exception {
    return JSON.readValue(
        mockMvc
            .perform(get(PROPOSALS))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        Map.class);
  }

  private List<Object> sentIds() throws Exception {
    return listing().get("sent").stream().map(row -> row.get("id")).toList();
  }

  private List<Object> receivedIds() throws Exception {
    return listing().get("received").stream().map(row -> row.get("id")).toList();
  }

  private Status statusOf(Map<String, Object> proposal) {
    return Tenants.acrossAll(
            () -> proposals.findById(UUID.fromString((String) proposal.get("id"))).orElseThrow())
        .getStatus();
  }

  private boolean exists(Map<String, Object> proposal) {
    return Tenants.acrossAll(
        () -> proposals.existsById(UUID.fromString((String) proposal.get("id"))));
  }

  private long count(Status status) {
    return Tenants.acrossAll(
        () ->
            java.util.stream.StreamSupport.stream(proposals.findAll().spliterator(), false)
                .filter(proposal -> proposal.getStatus() == status)
                .count());
  }

  private Instant startOf(UUID appointment) {
    return Tenants.acrossAll(() -> appointments.findById(appointment).orElseThrow()).getStartTime();
  }

  private Map<String, Object> body(MockHttpServletRequestBuilder request) throws Exception {
    return parseObject(
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(MockHttpServletRequestBuilder request) throws Exception {
    return JSON.readValue(
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        List.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseObject(String json) throws Exception {
    return JSON.readValue(json, Map.class);
  }

  private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  /** Hours from the start of the current hour, fixed per test so it holds across an hour. */
  private Instant in(int hours) {
    return base.plus(hours, ChronoUnit.HOURS);
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
