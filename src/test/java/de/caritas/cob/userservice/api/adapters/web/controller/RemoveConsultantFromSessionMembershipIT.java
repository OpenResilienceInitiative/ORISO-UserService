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

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Everything here is one Träger, so the tenant filter does not help. Not {@code @Transactional},
 * like production ({@code spring.jpa.open-in-view=false}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(RemoveConsultantFromSessionMembershipIT.TENANT)
class RemoveConsultantFromSessionMembershipIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  static final long TENANT = 1L;
  private static final long SESSION_AGENCY = 9711L;
  private static final long OTHER_AGENCY = 9712L;
  private static final String MATRIX_ROOM = "!remove-membership:synthetic.oriso.test";

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private SessionRepository sessionRepository;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean GroupChatMembershipService groupChatMembershipService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Consultant advisor;
  private Consultant roomMember;
  private Consultant colleagueOfSessionAgency;
  private Consultant consultantOfOtherAgency;
  private Session session;

  @BeforeEach
  void seedSessionWithARoomMemberAndTwoOtherConsultants() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    advisor = fixtures.consultant(TENANT, SESSION_AGENCY);
    roomMember = fixtures.consultant(TENANT);
    colleagueOfSessionAgency = fixtures.consultant(TENANT, SESSION_AGENCY);
    consultantOfOtherAgency = fixtures.consultant(TENANT, OTHER_AGENCY);
    session = fixtures.session(fixtures.adviceSeeker(TENANT), SESSION_AGENCY, advisor);
    givenRoomMember(roomMember);
  }

  @AfterEach
  void removeSeededRows() {
    fixtures.removeAll();
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

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_Refuse_When_CallerIsAdviceSeekerOfAnotherSession()
      throws Exception {
    actAsAdviceSeeker(fixtures.adviceSeeker(TENANT).getUserId());

    var result = mockMvc.perform(removeFromSession(roomMember)).andReturn();

    verify(groupChatMembershipService, never()).removeMemberFromRoom(anyString(), anyString());
    assertStatus(result, 403);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_Remove_When_CallerIsTheSessionsAdviceSeeker() throws Exception {
    actAsAdviceSeeker(session.getUser().getUserId());

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
    Tenants.actAs(caller, consultant.getId(), TENANT, UserRole.CONSULTANT);
    caller.setUsername(consultant.getUsername());
    caller.setGrantedAuthorities(Set.of(AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION));
  }

  private void actAsAdviceSeeker(String userId) {
    Tenants.actAs(caller, userId, TENANT, UserRole.USER);
    caller.setGrantedAuthorities(Set.of(AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION));
  }

  private String advisorOfSession() {
    return Tenants.in(
        TENANT,
        () -> sessionRepository.findById(session.getId()).orElseThrow().getConsultant().getId());
  }

  private static void assertStatus(MvcResult result, int expected) {
    assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(expected);
  }
}
