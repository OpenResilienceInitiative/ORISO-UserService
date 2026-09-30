package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Set;
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
 * Advice seekers and self-help groups across Träger (#1237). The invite link is meant to travel
 * across Träger, so a client of any Träger may join with it — but only with the link, not by
 * guessing a group number, and a client sees only the groups they belong to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@Transactional
class GroupChatInviteLinkE2EIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  private static final long GROUP_TENANT = 1L;
  private static final long GROUP_AGENCY = 920_001L;

  @Autowired private MockMvc mockMvc;
  @Autowired private EntityManager entityManager;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ChatAgencyRepository chatAgencyRepository;
  @Autowired private UserChatRepository userChatRepository;
  @Autowired private GroupChatParticipantRepository participantRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  private Consultant owner;
  private User client;
  private Chat selfHelpGroup;

  @BeforeEach
  void givenASelfHelpGroupOfOneTragerAndAClientOfAnother() {
    owner = consultantRepository.findAll().iterator().next();
    owner.setTenantId(GROUP_TENANT);
    owner = consultantRepository.save(owner);
    consultantAgencyRepository.save(
        ConsultantAgency.builder()
            .consultant(owner)
            .agencyId(GROUP_AGENCY)
            .tenantId(GROUP_TENANT)
            .status(ConsultantAgencyStatus.CREATED)
            .build());

    client = userRepository.findAll().iterator().next();
    client.setDeleteDate(null);
    client = userRepository.save(client);

    selfHelpGroup = aGroup(ConversationType.SELF_HELP, "!selfhelp:matrix.test");

    when(agencyService.getAgency(anyLong()))
        .thenAnswer(
            invocation ->
                new AgencyDTO()
                    .id(invocation.getArgument(0))
                    .tenantId(GROUP_TENANT)
                    .name("Agency"));
    flushAndClear();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void aClientCannotJoinAGroupByGuessingItsNumber() throws Exception {
    actingAsClient();

    mockMvc
        .perform(clientPut("/users/chat/" + selfHelpGroup.getId() + "/assign"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void aClientWhoIsNotAMemberCannotOpenASelfHelpGroup() throws Exception {
    actingAsClient();

    mockMvc
        .perform(clientGet("/users/chat/room/" + selfHelpGroup.getId()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void aClientWhoIsNotAMemberFindsNothingByMatrixRoom() throws Exception {
    actingAsClient();

    mockMvc
        .perform(
            clientGet("/users/sessions/room")
                .queryParam("roomIds[]", selfHelpGroup.getMatrixRoomId()))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void aMemberClientOpensTheirGroup() throws Exception {
    givenTheClientIsAMember();
    actingAsClient();

    mockMvc
        .perform(clientGet("/users/chat/room/" + selfHelpGroup.getId()))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void aClientCannotJoinWithAWrongInviteToken() throws Exception {
    actingAsClient();

    mockMvc
        .perform(
            clientPut("/users/chat/" + selfHelpGroup.getId() + "/assign")
                .queryParam("inviteToken", "not-the-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void aClientOfAnyTragerJoinsWithTheInviteLinkAndThenSeesTheGroup() throws Exception {
    actingAsClient();

    mockMvc
        .perform(
            clientPut("/users/chat/" + selfHelpGroup.getId() + "/assign")
                .queryParam("inviteToken", inviteTokenOf(selfHelpGroup)))
        .andExpect(status().isOk());
    mockMvc
        .perform(clientGet("/users/chat/room/" + selfHelpGroup.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("sessions[0].chat.id", is(selfHelpGroup.getId().intValue())))
        .andExpect(jsonPath("sessions[0].chat.inviteToken").doesNotExist());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void aClientCannotJoinAnInternalGroupEvenWithItsToken() throws Exception {
    var internalGroup = aGroup(ConversationType.INTERNAL_GROUP, "!internal:matrix.test");
    flushAndClear();
    actingAsClient();

    mockMvc
        .perform(
            clientPut("/users/chat/" + internalGroup.getId() + "/assign")
                .queryParam("inviteToken", inviteTokenOf(internalGroup)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void theOwnerGetsTheInviteTokenForTheShareLink() throws Exception {
    actingAsOwner();

    mockMvc
        .perform(clientGet("/users/chat/room/" + selfHelpGroup.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("sessions[0].chat.inviteToken", is(inviteTokenOf(selfHelpGroup))));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void aGroupWithoutATokenGetsOneWhenItsOwnerAsksForTheLink() throws Exception {
    givenTheGroupHasNoTokenYet();
    actingAsOwner();

    mockMvc
        .perform(clientGet("/users/chat/room/" + selfHelpGroup.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("sessions[0].chat.inviteToken", notNullValue()));
    flushAndClear();
    org.assertj.core.api.Assertions.assertThat(inviteTokenOf(selfHelpGroup)).isNotBlank();
  }

  private Chat aGroup(ConversationType type, String matrixRoomId) {
    var group =
        chatRepository.save(
            Chat.builder()
                .topic("Group")
                .consultingTypeId(1)
                .initialStartDate(LocalDateTime.now().plusDays(1))
                .startDate(LocalDateTime.now().plusDays(1))
                .duration(60)
                .conversationType(type)
                .matrixRoomId(matrixRoomId)
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
    return group;
  }

  private void givenTheClientIsAMember() {
    userChatRepository.save(
        UserChat.builder()
            .user(userRepository.findById(client.getUserId()).orElseThrow())
            .chat(chatRepository.findById(selfHelpGroup.getId()).orElseThrow())
            .build());
    flushAndClear();
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private String inviteTokenOf(Chat group) {
    return chatRepository.findById(group.getId()).orElseThrow().getInviteToken();
  }

  private void givenTheGroupHasNoTokenYet() {
    entityManager
        .createNativeQuery("UPDATE chat SET invite_token = NULL WHERE id = :id")
        .setParameter("id", selfHelpGroup.getId())
        .executeUpdate();
    flushAndClear();
  }

  private void actingAsOwner() {
    when(authenticatedUser.getUserId()).thenReturn(owner.getId());
    when(authenticatedUser.getUsername()).thenReturn(owner.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));
    when(authenticatedUser.getGrantedAuthorities())
        .thenReturn(Set.of(AuthorityValue.CONSULTANT_DEFAULT));
  }

  private void actingAsClient() {
    when(authenticatedUser.getUserId()).thenReturn(client.getUserId());
    when(authenticatedUser.getUsername()).thenReturn(client.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.USER.getValue()));
    when(authenticatedUser.getGrantedAuthorities()).thenReturn(Set.of(AuthorityValue.USER_DEFAULT));
  }

  private MockHttpServletRequestBuilder clientGet(String path) {
    return get(path)
        .cookie(CSRF_COOKIE)
        .header(CSRF_HEADER, CSRF_VALUE)
        .accept(MediaType.APPLICATION_JSON);
  }

  private MockHttpServletRequestBuilder clientPut(String path) {
    return put(path).cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }
}
