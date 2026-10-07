package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.TaskIdentity;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
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
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

  @Autowired private TaskIdentityConfiguration identities;
  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private AuthenticatedUser authenticatedUser;
  @Autowired private AgencyService agencyService;
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  @Autowired
  private de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory
      ownerFactory;

  @Autowired
  private de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory
      agencyFactory;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("restTemplate")
  private org.springframework.web.client.RestTemplate externalTransport;

  private de.caritas.cob.userservice.api.testHelper.DpaOwnerHttpFixtures permittedOwner;
  private org.springframework.web.client.RestTemplate previousAgencyTransport;
  private org.springframework.http.client.ClientHttpRequestFactory previousIdentityRequestFactory;
  private org.springframework.test.web.client.MockRestServiceServer agencyHttp;
  private org.springframework.test.web.client.MockRestServiceServer identityHttp;

  private Consultant owner;
  private User client;
  private Chat selfHelpGroup;

  @BeforeEach
  void givenASelfHelpGroupOfOneTragerAndAClientOfAnother() {
    permittedExternalOwnerAndAgencies();
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

    flushAndClear();
  }

  /** Existing permission journeys keep real AVV policy and explicitly permitted owner HTTP. */
  private void permittedExternalOwnerAndAgencies() {
    var policyJwt = BoundedIdentityHttpFixtures.taskJwt(TaskIdentity.RUNTIME_POLICY, identities);
    when(jwtDecoder.decode(policyJwt.getTokenValue())).thenReturn(policyJwt);
    permittedOwner =
        de.caritas.cob.userservice.api.testHelper.DpaOwnerHttpFixtures.permit(
            ownerFactory, GROUP_TENANT);
    previousAgencyTransport =
        (org.springframework.web.client.RestTemplate)
            org.springframework.test.util.ReflectionTestUtils.getField(
                agencyFactory, "restTemplate");
    var transport = new org.springframework.web.client.RestTemplate();
    agencyHttp =
        org.springframework.test.web.client.MockRestServiceServer.bindTo(transport).build();
    org.springframework.test.util.ReflectionTestUtils.setField(
        agencyFactory, "restTemplate", transport);
    agencyHttp
        .expect(
            org.springframework.test.web.client.ExpectedCount.between(0, Integer.MAX_VALUE),
            request ->
                org.junit.jupiter.api.Assertions.assertEquals(
                    org.springframework.http.HttpMethod.GET, request.getMethod()))
        .andRespond(
            request -> {
              String path = request.getURI().getPath();
              String id = path.substring(path.lastIndexOf('/') + 1);
              return org.springframework.test.web.client.response.MockRestResponseCreators
                  .withSuccess(
                      "[{\"id\":"
                          + id
                          + ",\"tenantId\":"
                          + GROUP_TENANT
                          + ",\"name\":\"Agency\",\"consultingType\":1}]",
                      org.springframework.http.MediaType.APPLICATION_JSON)
                  .createResponse(request);
            });
    previousIdentityRequestFactory = externalTransport.getRequestFactory();
    identityHttp =
        org.springframework.test.web.client.MockRestServiceServer.bindTo(externalTransport).build();
    identityHttp
        .expect(
            org.springframework.test.web.client.ExpectedCount.between(0, Integer.MAX_VALUE),
            org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
        .andRespond(
            request -> {
              String path = request.getURI().getPath();
              if (path.endsWith("/token")) {
                var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
                for (var field :
                    ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                        .getBodyAsString()
                        .split("&")) {
                  var pair = field.split("=", 2);
                  form.add(
                      URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                      URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
                }
                var credential = identities.require(TaskIdentity.RUNTIME_POLICY);
                org.junit.jupiter.api.Assertions.assertEquals(
                    org.springframework.http.HttpMethod.POST, request.getMethod());
                org.junit.jupiter.api.Assertions.assertEquals(
                    "client_credentials", form.getFirst("grant_type"));
                org.junit.jupiter.api.Assertions.assertEquals(
                    credential.getClientId(), form.getFirst("client_id"));
                org.junit.jupiter.api.Assertions.assertEquals(
                    credential.getClientSecret(), form.getFirst("client_secret"));
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withSuccess(
                        "{\"access_token\":\""
                            + policyJwt.getTokenValue()
                            + "\",\"expires_in\":300}",
                        org.springframework.http.MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.endsWith("/logout"))
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withNoContent()
                    .createResponse(request);
              throw new AssertionError("Unexpected identity HTTP path: " + path);
            });
  }

  @org.junit.jupiter.api.AfterEach
  void restoreExternalHttpFixtures() {
    if (permittedOwner != null) permittedOwner.close();
    org.springframework.test.util.ReflectionTestUtils.setField(
        agencyFactory, "restTemplate", previousAgencyTransport);
    if (previousIdentityRequestFactory != null)
      externalTransport.setRequestFactory(previousIdentityRequestFactory);
    if (agencyHttp != null) agencyHttp.reset();
    if (identityHttp != null) identityHttp.reset();
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
