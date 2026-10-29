package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counsellor direct links (#1475) through the generated {@code UsersApi} routing, Spring Security
 * and the persisted link: create/edit/switch off/delete own links, the public 410, and a signed-in
 * person opening their own conversation with the owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@Transactional
class DirectLinkControllerIT {

  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", "test");
  private static final long TENANT_ID = 8_101L;
  private static final long AGENCY_ID = 876_543L;
  private static final long TOPIC_ID = 4_711L;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EntityManager entityManager;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private AgencyInviteLinkRepository linkRepository;
  @Autowired private SessionRepository sessionRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private TopicService topicService;
  @MockitoBean private CreateNewSessionFacade createNewSessionFacade;
  @MockitoBean private TenantService tenantService;

  private Consultant owner;
  private Consultant otherCounsellor;
  private User asker;

  @BeforeEach
  void setUp() {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(
            new AgencyDTO().id(AGENCY_ID).tenantId(TENANT_ID).consultingType(1).postcode("10115"));
    when(topicService.getTopicById(TOPIC_ID)).thenReturn(new TopicDTO().id(TOPIC_ID));
    when(topicService.getAllTopicsMap())
        .thenReturn(Map.of(TOPIC_ID, new TopicDTO().id(TOPIC_ID).name("Schulden")));

    var consultants =
        StreamSupport.stream(consultantRepository.findAll().spliterator(), false)
            .filter(c -> c.getDeleteDate() == null)
            .limit(2)
            .toList();
    owner = inOneAgency(consultants.get(0));
    otherCounsellor = inOneAgency(consultants.get(1));
    asker =
        StreamSupport.stream(userRepository.findAll().spliterator(), false)
            .filter(u -> u.getDeleteDate() == null)
            .findFirst()
            .orElseThrow();
    asker.setTenantId(TENANT_ID);
    userRepository.save(asker);
    entityManager.flush();
  }

  @Test
  void counsellorManagesOwnLinks_AndSwitchedOffLinkSaysNoLongerActive() throws Exception {
    actAsConsultant(owner);
    var created =
        asConsultant(
                post("/users/directlinks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"medium\":\"VIDEO\",\"topicId\":"
                            + TOPIC_ID
                            + ",\"name\":\"Flyer Schule\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.medium", is("VIDEO")))
            .andExpect(jsonPath("$.name", is("Flyer Schule")))
            .andExpect(jsonPath("$.topicName", is("Schulden")))
            .andExpect(jsonPath("$.temporary", is(true)))
            .andExpect(jsonPath("$.active", is(true)))
            .andExpect(jsonPath("$.admission", is("KNOCK")))
            .andExpect(jsonPath("$.agencyId", is((int) AGENCY_ID)))
            .andReturn();
    var body = objectMapper.readTree(created.getResponse().getContentAsString());
    long id = body.get("id").asLong();
    String token = body.get("token").asText();
    assertThat(body.get("url").asText()).endsWith("/invite/" + token);

    asConsultant(get("/users/directlinks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id", is((int) id)));

    asConsultant(
            patch("/users/directlinks/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false,\"name\":\"Signatur\",\"temporary\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active", is(false)))
        .andExpect(jsonPath("$.name", is("Signatur")))
        .andExpect(jsonPath("$.temporary", is(false)))
        .andExpect(jsonPath("$.medium", is("VIDEO")));

    mvc.perform(get("/users/invitelinks/{token}/context", token))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason", is("DIRECT_LINK_INACTIVE")));

    asConsultant(delete("/users/directlinks/{id}", id)).andExpect(status().isNoContent());
    asConsultant(get("/users/directlinks")).andExpect(jsonPath("$", hasSize(0)));
    assertThat(linkRepository.findById(id)).isEmpty();
  }

  @Test
  void anotherCounsellorCannotSeeOrChangeTheLink() throws Exception {
    actAsConsultant(owner);
    long id = createLink("CHAT");

    actAsConsultant(otherCounsellor);
    asConsultant(get("/users/directlinks")).andExpect(jsonPath("$", hasSize(0)));
    asConsultant(
            patch("/users/directlinks/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isNotFound());
    asConsultant(delete("/users/directlinks/{id}", id)).andExpect(status().isNotFound());
    assertThat(linkRepository.findById(id)).get().extracting("status").isEqualTo("ACTIVE");
  }

  @Test
  void askerCannotManageLinks_AndCounsellorCannotOpenAConversation() throws Exception {
    actAsAsker();
    asAsker(get("/users/directlinks")).andExpect(status().isForbidden());

    actAsConsultant(owner);
    long id = createLink("CHAT");
    String token = linkRepository.findById(id).orElseThrow().getToken();
    asConsultant(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isForbidden());
    verify(createNewSessionFacade, never()).initializeNewSession(any(), any(User.class), anyList());
  }

  @Test
  void signedInPersonOpensTheirOwnConversationWithTheOwner() throws Exception {
    actAsConsultant(owner);
    long id = createLink("CHAT");
    String token = linkRepository.findById(id).orElseThrow().getToken();
    when(createNewSessionFacade.initializeNewSession(any(), any(User.class), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!direct:example")
                .sessionId(9_001L)
                .status(HttpStatus.CREATED));

    actAsAsker();
    asAsker(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId", is(9_001)))
        .andExpect(jsonPath("$.admission", is("DIRECT")))
        .andExpect(jsonPath("$.created", is(true)));

    var captor = ArgumentCaptor.forClass(NewRegistrationDto.class);
    verify(createNewSessionFacade)
        .initializeNewSession(captor.capture(), any(User.class), anyList());
    assertThat(captor.getValue().getConsultantId()).isEqualTo(owner.getId());
    assertThat(captor.getValue().getAgencyId()).isEqualTo(AGENCY_ID);
  }

  @Test
  void switchedOffLinkRefusesNewConversations() throws Exception {
    actAsConsultant(owner);
    long id = createLink("AUDIO");
    var link = linkRepository.findById(id).orElseThrow();
    link.setStatus("DISABLED");
    linkRepository.save(link);

    actAsAsker();
    asAsker(post("/users/invitelinks/{token}/conversations", link.getToken()))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason", is("DIRECT_LINK_INACTIVE")));
    verify(createNewSessionFacade, never()).initializeNewSession(any(), any(User.class), anyList());
  }

  @Test
  void callLinkKnocks_AndOnlyTheConversationsCounsellorLetsThePersonIn() throws Exception {
    var session = ownersConversationWithAsker();
    String token = callLinkOpeningInto(session.getId());

    actAsAsker();
    var started =
        asAsker(post("/users/invitelinks/{token}/conversations", token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.admission", is("KNOCK")))
            .andExpect(jsonPath("$.knockStatus", is("PENDING")))
            .andReturn();
    long knockId =
        objectMapper.readTree(started.getResponse().getContentAsString()).get("knockId").asLong();
    // Knocking again while waiting keeps the same place in the queue, in the same conversation.
    asAsker(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created", is(false)))
        .andExpect(jsonPath("$.sessionId", is(session.getId().intValue())))
        .andExpect(jsonPath("$.knockId", is((int) knockId)));

    actAsConsultant(otherCounsellor);
    asConsultant(get("/users/call-knocks")).andExpect(jsonPath("$", hasSize(0)));
    asConsultant(post("/users/call-knocks/{id}/admit", knockId)).andExpect(status().isNotFound());

    actAsConsultant(owner);
    asConsultant(get("/users/call-knocks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].sessionId", is(session.getId().intValue())))
        .andExpect(jsonPath("$[0].medium", is("VIDEO")))
        // The pseudonym the person chose, decoded; never the e-mail address.
        .andExpect(
            jsonPath(
                "$[0].displayName",
                is(new UsernameTranscoder().decodeUsername(asker.getUsername()))));
    // Only the knocks of the current call.
    asConsultant(get("/users/call-knocks").param("sessionId", session.getId().toString()))
        .andExpect(jsonPath("$", hasSize(1)));
    asConsultant(get("/users/call-knocks").param("sessionId", "987654321"))
        .andExpect(jsonPath("$", hasSize(0)));
    asConsultant(post("/users/call-knocks/{id}/admit", knockId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("ADMITTED")));

    actAsAsker();
    asAsker(get("/users/call-knocks/mine/{id}", knockId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("ADMITTED")));
    asAsker(get("/users/call-knocks")).andExpect(status().isForbidden());

    // Let in once: a later visit, on any device, gets the admission back without a new knock.
    asAsker(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created", is(false)))
        .andExpect(jsonPath("$.knockId", is((int) knockId)))
        .andExpect(jsonPath("$.knockStatus", is("ADMITTED")))
        .andExpect(jsonPath("$.callAdmitted", is(true)));
    asAsker(get("/users/call-knocks/mine").param("sessionId", session.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is((int) knockId)))
        .andExpect(jsonPath("$.status", is("ADMITTED")));
    actAsConsultant(owner);
    asConsultant(get("/users/call-knocks")).andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void declinedPersonMayKnockAgain_AndAdmitAllLetsThemIn() throws Exception {
    var session = ownersConversationWithAsker();
    String token = callLinkOpeningInto(session.getId());
    actAsAsker();
    var started =
        asAsker(post("/users/invitelinks/{token}/conversations", token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.callAdmitted", is(false)))
            .andReturn();
    long firstKnock =
        objectMapper.readTree(started.getResponse().getContentAsString()).get("knockId").asLong();

    actAsConsultant(owner);
    asConsultant(post("/users/call-knocks/{id}/decline", firstKnock)).andExpect(status().isOk());

    actAsAsker();
    asAsker(get("/users/call-knocks/mine").param("sessionId", session.getId().toString()))
        .andExpect(jsonPath("$.status", is("DECLINED")));
    asAsker(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.knockStatus", is("PENDING")))
        .andExpect(jsonPath("$.knockId", not((int) firstKnock)));

    actAsConsultant(owner);
    asConsultant(
            post("/users/call-knocks/admit-all").param("sessionId", session.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].status", is("ADMITTED")));
  }

  @Test
  void admitAllWithoutTheCurrentCall_Should_BeABadRequest() throws Exception {
    actAsConsultant(owner);
    asConsultant(post("/users/call-knocks/admit-all")).andExpect(status().isBadRequest());
  }

  @Test
  void ownCallAdmission_Should_BeOnlyThePersonsOwn() throws Exception {
    var session = ownersConversationWithAsker();
    actAsAsker();
    asAsker(get("/users/call-knocks/mine").param("sessionId", session.getId().toString()))
        .andExpect(status().isNotFound());
    actAsConsultant(owner);
    asConsultant(get("/users/call-knocks/mine").param("sessionId", session.getId().toString()))
        .andExpect(status().isForbidden());
  }

  /**
   * Kept apart from the flow above: the 409 marks the test-wide transaction rollback-only, so no
   * later request in the same test may depend on it.
   */
  @Test
  void decliningAnAdmittedKnock_Should_Conflict() throws Exception {
    String token = callLinkOpeningInto(ownersConversationWithAsker().getId());
    actAsAsker();
    var started =
        asAsker(post("/users/invitelinks/{token}/conversations", token))
            .andExpect(status().isCreated())
            .andReturn();
    long knockId =
        objectMapper.readTree(started.getResponse().getContentAsString()).get("knockId").asLong();

    actAsConsultant(owner);
    asConsultant(post("/users/call-knocks/{id}/admit", knockId)).andExpect(status().isOk());
    asConsultant(post("/users/call-knocks/{id}/decline", knockId)).andExpect(status().isConflict());
  }

  private Session ownersConversationWithAsker() {
    var session =
        StreamSupport.stream(sessionRepository.findAll().spliterator(), false)
            .findFirst()
            .orElseThrow();
    session.setConsultant(owner);
    session.setUser(asker);
    sessionRepository.save(session);
    entityManager.flush();
    return session;
  }

  private String callLinkOpeningInto(Long sessionId) throws Exception {
    actAsConsultant(owner);
    long id = createLink("VIDEO");
    // As in production: the first visit creates the conversation, later visits reuse it.
    when(createNewSessionFacade.initializeNewSession(any(), any(User.class), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!direct:example")
                .sessionId(sessionId)
                .status(HttpStatus.CREATED))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!direct:example")
                .sessionId(sessionId)
                .status(HttpStatus.CONFLICT));
    return linkRepository.findById(id).orElseThrow().getToken();
  }

  private long createLink(String medium) throws Exception {
    var result =
        asConsultant(
                post("/users/directlinks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"medium\":\"" + medium + "\",\"topicId\":" + TOPIC_ID + "}"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }

  private Consultant inOneAgency(Consultant consultant) {
    consultantAgencyRepository.deleteAll(
        consultantAgencyRepository.findByConsultantId(consultant.getId()));
    consultant.setTenantId(TENANT_ID);
    var membership =
        consultantAgencyRepository.save(
            ConsultantAgency.builder()
                .consultant(consultant)
                .agencyId(AGENCY_ID)
                .tenantId(TENANT_ID)
                .build());
    var memberships = new HashSet<ConsultantAgency>();
    memberships.add(membership);
    consultant.setConsultantAgencies(memberships);
    return consultantRepository.save(consultant);
  }

  private void actAsConsultant(Consultant consultant) {
    when(authenticatedUser.getUserId()).thenReturn(consultant.getId());
    when(authenticatedUser.getUsername()).thenReturn(consultant.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));
  }

  private void actAsAsker() {
    when(authenticatedUser.getUserId()).thenReturn(asker.getUserId());
    when(authenticatedUser.getUsername()).thenReturn(asker.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.USER.getValue()));
  }

  private ResultActions asConsultant(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(signed(request, AuthorityValue.CONSULTANT_DEFAULT));
  }

  private ResultActions asAsker(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(signed(request, AuthorityValue.USER_DEFAULT));
  }

  private MockHttpServletRequestBuilder signed(
      MockHttpServletRequestBuilder request, String authority) {
    return request
        .with(
            jwt()
                .authorities(new SimpleGrantedAuthority(authority))
                .jwt(token -> token.claim("userId", authenticatedUser.getUserId())))
        .cookie(CSRF_COOKIE)
        .header("X-CSRF-Token", "test");
  }
}
