package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Group chats across Träger (#1237): a counsellor of another Träger must not read or act on a group
 * they are not part of, while members, moderators and same-Beratungsstelle colleagues keep today's
 * access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@Transactional
class GroupChatCrossTenantAccessE2EIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long GROUP_AGENCY = 910_001L;
  private static final long OTHER_AGENCY_SAME_TENANT = 910_002L;
  private static final long FOREIGN_AGENCY = 910_003L;

  @Autowired private MockMvc mockMvc;
  @Autowired private EntityManager entityManager;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ChatAgencyRepository chatAgencyRepository;
  @Autowired private GroupChatParticipantRepository participantRepository;
  @Autowired private UserRepository userRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  private String[] consultantIds;
  private Consultant owner;
  private Consultant foreignCounsellor;
  private Chat group;

  @BeforeEach
  void givenAGroupOfOneTragerAndACounsellorOfAnother() {
    consultantIds =
        StreamSupport.stream(consultantRepository.findAll().spliterator(), false)
            .filter(consultant -> consultant.getDeleteDate() == null)
            .limit(3)
            .map(Consultant::getId)
            .toArray(String[]::new);
    owner =
        inTenantAndAgency(
            consultantRepository.findById(consultantIds[0]).orElseThrow(),
            OWN_TENANT,
            GROUP_AGENCY);
    foreignCounsellor =
        inTenantAndAgency(
            consultantRepository.findById(consultantIds[1]).orElseThrow(),
            FOREIGN_TENANT,
            FOREIGN_AGENCY);

    group =
        chatRepository.save(
            Chat.builder()
                .topic("Self-help group")
                .consultingTypeId(1)
                .initialStartDate(LocalDateTime.now().plusDays(1))
                .startDate(LocalDateTime.now().plusDays(1))
                .duration(60)
                .conversationType(ConversationType.SELF_HELP)
                .matrixRoomId("!group:matrix.test")
                .chatOwner(owner)
                .build());
    chatAgencyRepository.save(new ChatAgency(group, GROUP_AGENCY));
    participantRepository.save(
        GroupChatParticipant.builder()
            .chatId(group.getId())
            .seriesId(group.getId())
            .consultantId(owner.getId())
            .role(ParticipantRole.OWNER)
            .build());

    when(agencyService.getAgency(anyLong()))
        .thenAnswer(
            invocation ->
                new AgencyDTO().id(invocation.getArgument(0)).tenantId(OWN_TENANT).name("Agency"));
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherTragerCannotOpenTheGroupRoom() throws Exception {
    actingAs(foreignCounsellor);

    mockMvc
        .perform(consultantGet("/users/chat/room/" + group.getId()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherTragerGetsNothingFromTheSessionRoomLookup() throws Exception {
    actingAs(foreignCounsellor);

    mockMvc
        .perform(consultantGet("/users/sessions/room/" + group.getId()))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherTragerGetsNothingWhenAskingByMatrixRoom() throws Exception {
    actingAs(foreignCounsellor);

    mockMvc
        .perform(
            consultantGet("/users/sessions/room").queryParam("roomIds[]", group.getMatrixRoomId()))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherTragerCannotReadTheSeriesSchedule() throws Exception {
    actingAs(foreignCounsellor);

    mockMvc
        .perform(
            consultantGet("/users/chat-series/" + group.getId() + "/occurrences")
                .queryParam("from", "2026-01-01T00:00:00Z")
                .queryParam("to", "2027-01-01T00:00:00Z")
                .queryParam("limit", "5"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherTragerCannotReadTheChatInfo() throws Exception {
    actingAs(foreignCounsellor);

    mockMvc
        .perform(consultantGet("/users/chat/" + group.getId()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.START_CHAT})
  void counsellorOfAnotherTragerCannotStartTheGroup() throws Exception {
    actingAs(foreignCounsellor);

    mockMvc
        .perform(consultantPut("/users/chat/" + group.getId() + "/start"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherTragerCannotJoinAStartedGroup() throws Exception {
    givenTheGroupIsStarted();
    actingAs(foreignCounsellor);

    mockMvc
        .perform(consultantPut("/users/chat/" + group.getId() + "/join"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherTragerCannotListTheMembersOfAStartedGroup() throws Exception {
    givenTheGroupIsStarted();
    actingAs(foreignCounsellor);

    mockMvc
        .perform(consultantGet("/users/chat/" + group.getId() + "/members"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT, AuthorityValue.UPDATE_CHAT})
  void counsellorOfAnotherTragerCannotBanAnAdviceSeekerFromTheGroup() throws Exception {
    var adviceSeeker = anAdviceSeekerWithMatrixId("@seeker:matrix.test");
    actingAs(foreignCounsellor);

    mockMvc
        .perform(
            consultantPost(
                "/users/" + adviceSeeker.getMatrixUserId() + "/chat/" + group.getId() + "/ban"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void aCounsellorOfAnotherTragerLinkedToTheGroupsAgencyIdIsStillRefused() throws Exception {
    // Agency ids are not scoped per Träger in this service; the Träger check must hold on its own.
    var strayLink = inTenantAndAgency(thirdConsultant(), FOREIGN_TENANT, GROUP_AGENCY);
    actingAs(strayLink);

    mockMvc
        .perform(consultantGet("/users/chat/room/" + group.getId()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(consultantGet("/users/chat/" + group.getId()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void theOwnerOpensTheGroupRoom() throws Exception {
    actingAs(owner);

    mockMvc
        .perform(consultantGet("/users/chat/room/" + group.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("sessions[0].chat.id", is(group.getId().intValue())));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void aColleagueOfTheSameBeratungsstelleKeepsTodaysAccess() throws Exception {
    var colleague = inTenantAndAgency(thirdConsultant(), OWN_TENANT, GROUP_AGENCY);
    actingAs(colleague);

    mockMvc.perform(consultantGet("/users/chat/room/" + group.getId())).andExpect(status().isOk());
    mockMvc.perform(consultantGet("/users/chat/" + group.getId())).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void aCoModeratorFromAnotherBeratungsstelleSeesTheGroup() throws Exception {
    var coModerator = inTenantAndAgency(thirdConsultant(), OWN_TENANT, OTHER_AGENCY_SAME_TENANT);
    givenParticipant(coModerator, ParticipantRole.CO_MODERATOR);
    actingAs(coModerator);

    mockMvc.perform(consultantGet("/users/chat/room/" + group.getId())).andExpect(status().isOk());
    mockMvc.perform(consultantGet("/users/chat/" + group.getId())).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void aCounsellorOfAnotherTragerWhoWasAdmittedAsMemberSeesTheGroup() throws Exception {
    givenParticipant(foreignCounsellor, ParticipantRole.PARTICIPANT);
    actingAs(foreignCounsellor);

    mockMvc.perform(consultantGet("/users/chat/room/" + group.getId())).andExpect(status().isOk());
    mockMvc.perform(consultantGet("/users/chat/" + group.getId())).andExpect(status().isOk());
  }

  private Consultant inTenantAndAgency(Consultant consultant, long tenantId, long agencyId) {
    consultant.setTenantId(tenantId);
    var saved = consultantRepository.save(consultant);
    consultantAgencyRepository.save(
        ConsultantAgency.builder()
            .consultant(saved)
            .agencyId(agencyId)
            .tenantId(tenantId)
            .status(ConsultantAgencyStatus.CREATED)
            .build());
    return saved;
  }

  private void actingAs(Consultant consultant) {
    when(authenticatedUser.getUserId()).thenReturn(consultant.getId());
    when(authenticatedUser.getUsername()).thenReturn(consultant.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));
    when(authenticatedUser.getGrantedAuthorities())
        .thenReturn(Set.of(AuthorityValue.CONSULTANT_DEFAULT));
  }

  private Consultant thirdConsultant() {
    return consultantRepository.findById(consultantIds[2]).orElseThrow();
  }

  private void givenParticipant(Consultant consultant, ParticipantRole role) {
    participantRepository.save(
        GroupChatParticipant.builder()
            .chatId(group.getId())
            .seriesId(group.getId())
            .consultantId(consultant.getId())
            .role(role)
            .build());
    entityManager.flush();
    entityManager.clear();
  }

  private void givenTheGroupIsStarted() {
    var started = chatRepository.findById(group.getId()).orElseThrow();
    started.setActive(true);
    chatRepository.save(started);
    entityManager.flush();
    entityManager.clear();
  }

  private User anAdviceSeekerWithMatrixId(String matrixUserId) {
    var adviceSeeker = userRepository.findAll().iterator().next();
    adviceSeeker.setMatrixUserId(matrixUserId);
    adviceSeeker.setDeleteDate(null);
    var saved = userRepository.save(adviceSeeker);
    entityManager.flush();
    entityManager.clear();
    return saved;
  }

  private MockHttpServletRequestBuilder consultantPut(String path) {
    return put(path).cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private MockHttpServletRequestBuilder consultantPost(String path) {
    return post(path).cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private MockHttpServletRequestBuilder consultantGet(String path) {
    return get(path)
        .cookie(CSRF_COOKIE)
        .header(CSRF_HEADER, CSRF_VALUE)
        .accept(MediaType.APPLICATION_JSON);
  }
}
