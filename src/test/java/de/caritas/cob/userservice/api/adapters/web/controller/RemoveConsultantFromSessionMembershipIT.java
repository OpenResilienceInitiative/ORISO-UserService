package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.google.common.collect.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code DELETE /users/sessions/{sessionId}/consultant/{consultantId}} takes a consultant out of
 * the session's chat room. Only somebody who belongs to the session may do that: the consultant
 * leaving the room themselves (what the frontend does after handing a session over), or a
 * consultant of the session's agency. A consultant of the same Träger but another agency must be
 * refused.
 *
 * <p>Everything here is one Träger, so the tenant filter does not help. Not {@code @Transactional},
 * like production ({@code spring.jpa.open-in-view=false}); seeded rows are removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
class RemoveConsultantFromSessionMembershipIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  private static final long TENANT = 1L;
  private static final long SESSION_AGENCY = 9711L;
  private static final long OTHER_AGENCY = 9712L;
  private static final String MATRIX_ROOM = "!remove-membership:synthetic.oriso.test";

  @Autowired private MockMvc mockMvc;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean GroupChatMembershipService groupChatMembershipService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private final List<Session> seededSessions = new ArrayList<>();
  private final List<User> seededAskers = new ArrayList<>();
  private final List<ConsultantAgency> seededAgencyMemberships = new ArrayList<>();
  private final List<Consultant> seededConsultants = new ArrayList<>();

  private Consultant advisor;
  private Consultant roomMember;
  private Consultant colleagueOfSessionAgency;
  private Consultant consultantOfOtherAgency;
  private Session session;

  @BeforeEach
  void seedSessionWithARoomMemberAndTwoOtherConsultants() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(tenantResolverService.resolve(any())).thenReturn(TENANT);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      advisor = persistConsultantIn(SESSION_AGENCY);
      roomMember = persistConsultantIn(null);
      colleagueOfSessionAgency = persistConsultantIn(SESSION_AGENCY);
      consultantOfOtherAgency = persistConsultantIn(OTHER_AGENCY);
      session = persistSession(advisor);
    } finally {
      TenantContext.clear();
    }
    givenRoomMember(roomMember);
  }

  @AfterEach
  void removeSeededRows() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      seededSessions.forEach(s -> sessionRepository.deleteById(s.getId()));
      seededAskers.forEach(asker -> userRepository.deleteById(asker.getUserId()));
      seededAgencyMemberships.forEach(m -> consultantAgencyRepository.deleteById(m.getId()));
      seededConsultants.forEach(c -> consultantRepository.deleteById(c.getId()));
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_Refuse_When_CallerIsConsultantOfAnotherAgency() throws Exception {
    actAs(consultantOfOtherAgency);

    var result = mockMvc.perform(removeFromSession(roomMember)).andReturn();

    verify(groupChatMembershipService, never()).removeMemberFromRoom(anyString(), anyString());
    assertStatus(result, 403);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_Refuse_When_CallerIsColleagueWithoutAccessToTheSession()
      throws Exception {
    actAs(colleagueOfSessionAgency);

    var result = mockMvc.perform(removeFromSession(roomMember)).andReturn();

    verify(groupChatMembershipService, never()).removeMemberFromRoom(anyString(), anyString());
    assertStatus(result, 403);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_Remove_When_CallerAdvisesTheSession() throws Exception {
    actAs(advisor);

    var result = mockMvc.perform(removeFromSession(roomMember)).andReturn();

    assertStatus(result, 204);
    verify(groupChatMembershipService)
        .removeMemberFromRoom(MATRIX_ROOM, roomMember.getMatrixUserId());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void assignSession_Should_Refuse_When_CallerHasNoAccessToTheSession() throws Exception {
    actAs(consultantOfOtherAgency);

    var result =
        mockMvc
            .perform(
                put("/users/sessions/"
                        + session.getId()
                        + "/consultant/"
                        + colleagueOfSessionAgency.getId())
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE))
            .andReturn();

    assertThat(advisorOfSession()).isEqualTo(advisor.getId());
    assertStatus(result, 403);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_Remove_When_CallerLeavesTheRoomThemselves() throws Exception {
    actAs(roomMember);

    var result = mockMvc.perform(removeFromSession(roomMember)).andReturn();

    assertStatus(result, 204);
    verify(groupChatMembershipService)
        .removeMemberFromRoom(MATRIX_ROOM, roomMember.getMatrixUserId());
  }

  // --- helpers ----------------------------------------------------------------------------------

  private MockHttpServletRequestBuilder removeFromSession(Consultant consultant) {
    return delete("/users/sessions/" + session.getId() + "/consultant/" + consultant.getId())
        .cookie(CSRF_COOKIE)
        .header(CSRF_HEADER, CSRF_VALUE);
  }

  private void givenRoomMember(Consultant consultant) {
    when(groupChatMembershipService.resolveMatrixRoomId(any(Session.class)))
        .thenReturn(MATRIX_ROOM);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    consultant.getMatrixUserId(),
                    consultant.getId(),
                    consultant.getUsername(),
                    "Synthetic",
                    true)));
  }

  private void actAs(Consultant consultant) {
    caller.setUserId(consultant.getId());
    caller.setUsername(consultant.getUsername());
    caller.setTenantId(TENANT);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    caller.setGrantedAuthorities(Set.of(AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION));
  }

  private String advisorOfSession() {
    TenantContext.setCurrentTenant(TENANT);
    try {
      return sessionRepository.findById(session.getId()).orElseThrow().getConsultant().getId();
    } finally {
      TenantContext.clear();
    }
  }

  private static void assertStatus(MvcResult result, int expected) {
    assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(expected);
  }

  private Consultant persistConsultantIn(Long agencyId) {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(TENANT);
    consultant.setUsername("membership-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("M" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setAppointments(null);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId("@membership-" + id.substring(0, 8) + ":synthetic.oriso.test");
    var saved = consultantRepository.save(consultant);
    seededConsultants.add(saved);
    if (agencyId != null) {
      var membership =
          consultantAgencyRepository.save(
              ConsultantAgency.builder()
                  .consultant(saved)
                  .agencyId(agencyId)
                  .tenantId(TENANT)
                  .createDate(LocalDateTime.now())
                  .updateDate(LocalDateTime.now())
                  .build());
      seededAgencyMemberships.add(membership);
    }
    return saved;
  }

  private Session persistSession(Consultant advisor) {
    var id = UUID.randomUUID().toString();
    var user =
        new User(
            id,
            null,
            "membership-asker-" + id.substring(0, 8),
            id.substring(0, 8) + "@synthetic.oriso.test",
            false);
    user.setTenantId(TENANT);
    user.setLanguageCode(LanguageCode.de);
    user.setEncourage2fa(true);
    var savedUser = userRepository.save(user);
    seededAskers.add(savedUser);

    var newSession = new Session();
    newSession.setUser(savedUser);
    newSession.setConsultant(advisor);
    newSession.setTenantId(TENANT);
    newSession.setAgencyId(SESSION_AGENCY);
    newSession.setConsultingTypeId(1);
    newSession.setStatus(SessionStatus.IN_PROGRESS);
    newSession.setRegistrationType(RegistrationType.REGISTERED);
    newSession.setPostcode("12345");
    newSession.setLanguageCode(LanguageCode.de);
    newSession.setTeamSession(false);
    newSession.setSessionTopics(Lists.newArrayList());
    newSession.setIsConsultantDirectlySet(false);
    var saved = sessionRepository.save(newSession);
    seededSessions.add(saved);
    return saved;
  }
}
