package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationExpiryService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationRoomMembership;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * ORISO-Frontend#2252 (Direkteinladung) through routing, security and the persisted link,
 * appointment and members: one appointment conversation for everybody, access modes, 410 at expiry,
 * the sweep, "Neuen Link anfragen" and "Neuen Link senden", and invitations from a 1:1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@Transactional
class DirectInvitationControllerIT {

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
  @Autowired private SessionAppointmentRepository appointmentRepository;
  @Autowired private DirectInvitationMemberRepository memberRepository;
  @Autowired private DirectInvitationExpiryService expiryService;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private TopicService topicService;
  @MockitoBean private CreateNewSessionFacade createNewSessionFacade;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private EffectiveAgencySettingsLookup effectiveAgencySettings;
  @MockitoBean private DirectInvitationRoomMembership rooms;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private MatrixSessionSystemMessageService systemMessages;

  private Consultant owner;
  private Consultant otherCounsellor;
  private User asker;
  private User interpreter;
  private Session conversation;

  @BeforeEach
  void setUp() {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(
            new AgencyDTO().id(AGENCY_ID).tenantId(TENANT_ID).consultingType(1).postcode("10115"));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDirectInviteChatEnabled(true)
                        .featureDirectInviteAudioEnabled(true)
                        .featureDirectInviteVideoEnabled(true)));
    when(effectiveAgencySettings.findEffectiveSettings(anyLong(), anyLong()))
        .thenReturn(
            Optional.of(
                new Settings()
                    .featureDirectInviteChatEnabled(true)
                    .featureDirectInviteAudioEnabled(true)
                    .featureDirectInviteVideoEnabled(true)));
    when(topicService.getTopicById(TOPIC_ID)).thenReturn(new TopicDTO().id(TOPIC_ID));
    when(topicService.getAllTopicsMap())
        .thenReturn(Map.of(TOPIC_ID, new TopicDTO().id(TOPIC_ID).name("Schulden")));
    when(systemMessages.postSystemMessage(any(), anyString(), eq(true))).thenReturn(true);
    when(rooms.remove(any(), any())).thenReturn(true);

    var consultants =
        StreamSupport.stream(consultantRepository.findAll().spliterator(), false)
            .filter(c -> c.getDeleteDate() == null)
            .limit(2)
            .toList();
    owner = inOneAgency(consultants.get(0));
    otherCounsellor = inOneAgency(consultants.get(1));
    var users =
        StreamSupport.stream(userRepository.findAll().spliterator(), false)
            .filter(u -> u.getDeleteDate() == null)
            .limit(2)
            .toList();
    asker = users.get(0);
    asker.setTenantId(TENANT_ID);
    userRepository.save(asker);
    interpreter = users.get(1);
    interpreter.setTenantId(TENANT_ID);
    userRepository.save(interpreter);
    conversation =
        StreamSupport.stream(sessionRepository.findAll().spliterator(), false)
            .findFirst()
            .orElseThrow();
    conversation.setConsultant(owner);
    conversation.setUser(asker);
    conversation.setTenantId(TENANT_ID);
    conversation.setAgencyId(AGENCY_ID);
    conversation.setMainTopicId(TOPIC_ID);
    conversation.setMatrixRoomId("!appointment:example");
    conversation.setStatus(SessionStatus.IN_PROGRESS);
    sessionRepository.save(conversation);
    entityManager.flush();
    when(createNewSessionFacade.initializeAppointmentSession(any(), any(User.class)))
        .thenReturn(
            new NewRegistrationResponseDto()
                .sessionId(conversation.getId())
                .matrixRoomId("!appointment:example")
                .status(HttpStatus.CREATED));
  }

  @Test
  void invitationWithAppointment_Should_ShowItsTimes_AndPutEverybodyInOneConversation()
      throws Exception {
    var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
    var created = createInvitation("CHAT", start, 90, "BOTH", null);
    long id = created.get("id").asLong();
    String token = created.get("token").asText();
    assertThat(instant(created, "expiresAt"))
        .isEqualTo(start.plusHours(1).plusMinutes(90).toInstant());
    assertThat(instant(created, "callOpensAt")).isEqualTo(start.minusMinutes(15).toInstant());
    assertThat(created.get("accessMode").asText()).isEqualTo("BOTH");
    assertThat(created.get("appointment").get("status").asText()).isEqualTo("SCHEDULED");
    assertThat(created.get("expired").asBoolean()).isFalse();

    mvc.perform(get("/users/invitelinks/{token}/context", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessMode", is("BOTH")))
        .andExpect(jsonPath("$.appointmentStartTime", notNullValue()))
        .andExpect(jsonPath("$.callOpensAt", notNullValue()))
        .andExpect(jsonPath("$.expiresAt", notNullValue()));

    actAsUser(asker);
    asUser(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId", is(conversation.getId().intValue())))
        .andExpect(jsonPath("$.appointmentConversation", is(true)));

    actAsUser(interpreter);
    asUser(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId", is(conversation.getId().intValue())));
    verify(rooms).addToAppointmentRoom(any(), any());
    // A second visit returns the same conversation and joins nobody again.
    asUser(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId", is(conversation.getId().intValue())));
    verify(createNewSessionFacade).initializeAppointmentSession(any(), any(User.class));

    actAsConsultant(owner);
    asConsultant(get("/users/directlinks"))
        .andExpect(jsonPath("$[0].id", is((int) id)))
        .andExpect(jsonPath("$[0].sessionId", is(conversation.getId().intValue())));
  }

  @Test
  void invitationWithoutAppointment_Should_NeverExpire_AndGiveEachPersonTheirOwnConversation()
      throws Exception {
    actAsConsultant(owner);
    var created =
        body(
            asConsultant(
                    post("/users/directlinks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medium\":\"CHAT\",\"topicId\":" + TOPIC_ID + "}"))
                .andExpect(status().isCreated()));
    assertThat(created.path("appointment").isMissingNode() || created.get("appointment").isNull())
        .isTrue();
    assertThat(created.path("expiresAt").isMissingNode() || created.get("expiresAt").isNull())
        .isTrue();
    assertThat(created.get("accessMode").asText()).isEqualTo("ACCOUNTS_ONLY");
    when(createNewSessionFacade.initializeNewSession(
            any(), any(User.class), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .sessionId(conversation.getId())
                .matrixRoomId("!own:example")
                .status(HttpStatus.CREATED));

    actAsUser(asker);
    asUser(post("/users/invitelinks/{token}/conversations", created.get("token").asText()))
        .andExpect(status().isCreated());
    verify(createNewSessionFacade, never()).initializeAppointmentSession(any(), any());
  }

  @Test
  void expiredInvitation_Should_AnswerGone_AndTheSweepRemovesGuestsAndArchives() throws Exception {
    var created =
        createInvitation("CHAT", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), 0, "BOTH", null);
    String token = created.get("token").asText();
    actAsUser(asker);
    asUser(post("/users/invitelinks/{token}/conversations", token)).andExpect(status().isCreated());
    guest(interpreter, created.get("id").asLong());
    actAsUser(interpreter);
    asUser(post("/users/invitelinks/{token}/conversations", token)).andExpect(status().isCreated());

    var link = linkRepository.findById(created.get("id").asLong()).orElseThrow();
    link.setExpiresAt(java.time.LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    linkRepository.save(link);

    mvc.perform(get("/users/invitelinks/{token}/context", token))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason", is("DIRECT_LINK_EXPIRED")));
    asUser(post("/users/invitelinks/{token}/conversations", token))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason", is("DIRECT_LINK_EXPIRED")));

    assertThat(expiryService.expire(link.getId())).isTrue();
    entityManager.flush();
    assertThat(sessionRepository.findById(conversation.getId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.IN_ARCHIVE);
    verify(rooms).remove(any(), eq(interpreter.getMatrixUserId()));
    var guestRow =
        memberRepository
            .findBySessionIdAndUserId(conversation.getId(), interpreter.getUserId())
            .orElseThrow();
    assertThat(guestRow.getRemovedAt()).isNotNull();
    var accountRow =
        memberRepository
            .findBySessionIdAndUserId(conversation.getId(), asker.getUserId())
            .orElseThrow();
    assertThat(accountRow.getRemovedAt()).as("accounts keep their membership").isNull();
    assertThat(expiryService.expire(link.getId())).as("a second run changes nothing").isFalse();
  }

  @Test
  void requestAndSendANewLink_Should_ReplaceTheTokenForTheSameAppointment() throws Exception {
    var created =
        createInvitation("CHAT", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), 60, "BOTH", null);
    long id = created.get("id").asLong();
    String oldToken = created.get("token").asText();
    actAsUser(asker);
    asUser(post("/users/invitelinks/{token}/conversations", oldToken))
        .andExpect(status().isCreated());

    asUser(post("/users/invitelinks/{token}/renewal-requests", oldToken))
        .andExpect(status().isAccepted());
    verify(notifications)
        .createDirectInvitationRenewalRequestedNotification(
            any(), eq(id), eq(asker.getUserId()), eq(oldToken));

    actAsUser(interpreter);
    asUser(post("/users/invitelinks/{token}/renewal-requests", oldToken))
        .andExpect(status().isNotFound());

    actAsConsultant(otherCounsellor);
    asConsultant(post("/users/directlinks/{id}/renewal", id)).andExpect(status().isNotFound());

    actAsConsultant(owner);
    var renewed =
        body(
            asConsultant(
                    post("/users/directlinks/{id}/renewal", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validityAfterEndMinutes\":120}"))
                .andExpect(status().isOk()));
    String newToken = renewed.get("token").asText();
    assertThat(newToken).isNotEqualTo(oldToken);
    assertThat(renewed.get("appointment").get("id").asText())
        .isEqualTo(created.get("appointment").get("id").asText());
    assertThat(renewed.get("validityAfterEndMinutes").asInt()).isEqualTo(120);
    verify(systemMessages)
        .postSystemMessage(
            any(), org.mockito.ArgumentMatchers.contains("DIRECT_INVITATION_RENEWED"), eq(true));

    mvc.perform(get("/users/invitelinks/{token}/context", oldToken))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason", is("DIRECT_LINK_EXPIRED")));
    mvc.perform(get("/users/invitelinks/{token}/context", newToken)).andExpect(status().isOk());
    // The person with the old link can still ask again.
    actAsUser(asker);
    asUser(post("/users/invitelinks/{token}/renewal-requests", oldToken))
        .andExpect(status().isAccepted());
  }

  @Test
  void accessMode_Should_BeEnforcedWhenOpening() throws Exception {
    var created =
        createInvitation(
            "CHAT", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), 60, "GUESTS_ONLY", null);
    actAsUser(asker);
    asUser(post("/users/invitelinks/{token}/conversations", created.get("token").asText()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.reason", is("DIRECT_INVITATION_GUESTS_ONLY")));

    var accountsOnly =
        createInvitation(
            "CHAT", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), 60, "ACCOUNTS_ONLY", null);
    guest(interpreter, accountsOnly.get("id").asLong());
    actAsUser(interpreter);
    asUser(post("/users/invitelinks/{token}/conversations", accountsOnly.get("token").asText()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.reason", is("DIRECT_INVITATION_ACCOUNTS_ONLY")));
  }

  @Test
  void invitationFromA1to1_Should_BeOnlyForItsCounsellor_AndAddPeopleToThatConversation()
      throws Exception {
    var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    var request =
        post("/users/sessions/{sessionId}/direct-invitations", conversation.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"appointment\":{\"startTime\":\""
                    + start
                    + "\",\"endTime\":\""
                    + start.plusHours(1)
                    + "\"},\"name\":\"Dolmetscherin\",\"knock\":false}");

    actAsConsultant(otherCounsellor);
    asConsultant(request).andExpect(status().isNotFound());
    actAsUser(asker);
    asUser(request).andExpect(status().isForbidden());

    actAsConsultant(owner);
    var created = body(asConsultant(request).andExpect(status().isCreated()));
    assertThat(created.get("joinsExistingSession").asBoolean()).isTrue();
    assertThat(created.get("sessionId").asLong()).isEqualTo(conversation.getId());
    assertThat(created.get("medium").asText()).isEqualTo("VIDEO");
    assertThat(created.get("knock").asBoolean()).isFalse();
    var appointmentId = java.util.UUID.fromString(created.get("appointment").get("id").asText());
    assertThat(appointmentRepository.findById(appointmentId).orElseThrow().getSession().getId())
        .isEqualTo(conversation.getId());

    actAsUser(interpreter);
    asUser(post("/users/invitelinks/{token}/conversations", created.get("token").asText()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId", is(conversation.getId().intValue())))
        .andExpect(jsonPath("$.knockId").doesNotExist());
    verify(rooms).addInvitedGuestToExistingRoom(any(), any(), any());
    verify(createNewSessionFacade, never()).initializeAppointmentSession(any(), any());

    // The expiry takes the invited people out, but never archives the 1:1.
    var link = linkRepository.findById(created.get("id").asLong()).orElseThrow();
    link.setExpiresAt(java.time.LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    linkRepository.save(link);
    expiryService.expire(link.getId());
    entityManager.flush();
    assertThat(sessionRepository.findById(conversation.getId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.IN_PROGRESS);
  }

  @Test
  void knockSwitch_Should_BeChangeableOnAnExistingInvitation() throws Exception {
    var created =
        createInvitation("VIDEO", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), 60, null, true);
    actAsConsultant(owner);
    asConsultant(
            patch("/users/directlinks/{id}", created.get("id").asLong())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knock\":false,\"validityAfterEndMinutes\":2880}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.knock", is(false)))
        .andExpect(jsonPath("$.admission", is("DIRECT")))
        .andExpect(jsonPath("$.validityAfterEndMinutes", is(2880)));
    asConsultant(
            patch("/users/directlinks/{id}", created.get("id").asLong())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"validityAfterEndMinutes\":2881}"))
        .andExpect(status().isBadRequest());
  }

  // --- helpers ---

  private com.fasterxml.jackson.databind.JsonNode createInvitation(
      String medium, OffsetDateTime start, Integer validity, String accessMode, Boolean knock)
      throws Exception {
    actAsConsultant(owner);
    var json = new StringBuilder();
    json.append("{\"medium\":\"")
        .append(medium)
        .append("\",\"topicId\":")
        .append(TOPIC_ID)
        .append(",\"appointment\":{\"startTime\":\"")
        .append(start)
        .append("\",\"endTime\":\"")
        .append(start.plusHours(1))
        .append("\"}");
    if (validity != null) json.append(",\"validityAfterEndMinutes\":").append(validity);
    if (accessMode != null) json.append(",\"accessMode\":\"").append(accessMode).append("\"");
    if (knock != null) json.append(",\"knock\":").append(knock);
    json.append("}");
    return body(
        asConsultant(
                post("/users/directlinks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.toString()))
            .andExpect(status().isCreated()));
  }

  private static java.time.Instant instant(
      com.fasterxml.jackson.databind.JsonNode node, String field) {
    return OffsetDateTime.parse(node.get(field).asText()).toInstant();
  }

  private com.fasterxml.jackson.databind.JsonNode body(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  /** The person entered through this link with a temporary account. */
  private void guest(User user, long linkId) {
    user.setTemporaryAccountSnapshot(
        TemporaryAccountSnapshot.builder()
            .medium(de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.CHAT)
            .inviteLinkId(linkId)
            .build());
    if (user.getMatrixUserId() == null) {
      user.setMatrixUserId("@guest-" + user.getUserId() + ":example");
    }
    userRepository.save(user);
    entityManager.flush();
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

  private void actAsUser(User user) {
    when(authenticatedUser.getUserId()).thenReturn(user.getUserId());
    when(authenticatedUser.getUsername()).thenReturn(user.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.USER.getValue()));
  }

  private ResultActions asConsultant(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(signed(request, AuthorityValue.CONSULTANT_DEFAULT));
  }

  private ResultActions asUser(MockHttpServletRequestBuilder request) throws Exception {
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

  @SuppressWarnings("unused")
  private static List<String> none() {
    return List.of();
  }
}
