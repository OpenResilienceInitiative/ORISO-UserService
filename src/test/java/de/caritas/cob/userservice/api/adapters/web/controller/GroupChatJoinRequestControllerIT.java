package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knock-to-join for self-help groups (ORISO-Frontend#1499): a counsellor of any Träger who holds
 * the invite link may ask to join; only a Series Owner or Co-Moderator may admit or decline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@Transactional
class GroupChatJoinRequestControllerIT {

  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", "test");
  private static final long CHAT_AGENCY_ID = 987_654L;
  private static final long OWN_TENANT_ID = 7_001L;
  private static final long OTHER_TENANT_ID = 7_002L;
  private static final long OTHER_AGENCY_ID = 987_655L;
  private static final long SESSION_ID = 424_242L;
  private static final String GROUP_TITLE = "Trauer-Gesprächskreis Nord";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EntityManager entityManager;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ChatAgencyRepository chatAgencyRepository;
  @Autowired private GroupChatParticipantRepository participantRepository;
  @Autowired private GroupChatJoinRequestRepository joinRequestRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private GroupChatMembershipService membershipService;
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private TenantService tenantService;

  private Consultant owner;
  private Consultant coModerator;
  private Consultant participant;
  private Consultant otherTraegerCounsellor;
  private Consultant sameAgencyCounsellor;
  private Consultant unrelatedCounsellor;
  private Chat group;

  @BeforeEach
  void setUp() {
    var consultants =
        StreamSupport.stream(consultantRepository.findAll().spliterator(), false)
            .filter(consultant -> consultant.getDeleteDate() == null)
            .limit(6)
            .toList();
    assertThat(consultants).hasSize(6);
    owner = prepare(consultants.get(0), OWN_TENANT_ID, "@owner:matrix.test");
    coModerator = prepare(consultants.get(1), OWN_TENANT_ID, "@comod:matrix.test");
    participant = prepare(consultants.get(2), OWN_TENANT_ID, "@participant:matrix.test");
    otherTraegerCounsellor =
        prepare(consultants.get(3), OTHER_TENANT_ID, "@other-traeger:matrix.test");
    sameAgencyCounsellor = prepare(consultants.get(4), OWN_TENANT_ID, "@same-agency:matrix.test");
    unrelatedCounsellor = prepare(consultants.get(5), OWN_TENANT_ID, "@unrelated:matrix.test");

    consultantAgencyRepository.save(
        ConsultantAgency.builder()
            .consultant(sameAgencyCounsellor)
            .agencyId(CHAT_AGENCY_ID)
            .tenantId(OWN_TENANT_ID)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
    consultantAgencyRepository.save(
        ConsultantAgency.builder()
            .consultant(otherTraegerCounsellor)
            .agencyId(OTHER_AGENCY_ID)
            .tenantId(OTHER_TENANT_ID)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());

    var now = LocalDateTime.now();
    group =
        chatRepository.save(
            Chat.builder()
                .topic(GROUP_TITLE)
                .consultingTypeId(1)
                .initialStartDate(now)
                .startDate(now)
                .duration(60)
                .repetitive(false)
                .active(true)
                .conversationType(ConversationType.SELF_HELP)
                .matrixRoomId("!group:matrix.test")
                .chatOwner(owner)
                .createDate(now)
                .updateDate(now)
                .build());
    assertThat(group.getInviteToken()).isNotBlank();
    chatAgencyRepository.save(ChatAgency.builder().chat(group).agencyId(CHAT_AGENCY_ID).build());
    saveParticipant(owner, ParticipantRole.OWNER);
    saveParticipant(coModerator, ParticipantRole.CO_MODERATOR);
    saveParticipant(participant, ParticipantRole.PARTICIPANT);

    entityManager.flush();
    entityManager.clear();

    when(membershipService.addMemberToRoom(any(Chat.class), any())).thenReturn(true);
    when(agencyService.getAgency(OTHER_AGENCY_ID))
        .thenReturn(new AgencyDTO().id(OTHER_AGENCY_ID).name("Beratungsstelle Süd"));
    when(tenantService.getRestrictedTenantData(OTHER_TENANT_ID))
        .thenReturn(new RestrictedTenantDTO().id(OTHER_TENANT_ID).name("Anderer Träger"));
  }

  @Test
  void otherTraegerCounsellorKnocksAndSeesOnlyTheRequestStatus() throws Exception {
    actAs(otherTraegerCounsellor);

    knock()
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.status", is("PENDING")))
        .andExpect(jsonPath("$.requestedAt").isNotEmpty())
        .andExpect(jsonPath("$.decidedAt").value(nullValue()));

    var body = readJson(knock().andReturn().getResponse().getContentAsString());
    assertThat(fieldNames(body)).containsOnly("id", "status", "requestedAt", "decidedAt");
  }

  @Test
  void knockingTwiceReturnsTheSamePendingRequest() throws Exception {
    actAs(otherTraegerCounsellor);
    var first = readJson(knock().andExpect(status().isCreated()).andReturn());

    var second = readJson(knock().andExpect(status().isOk()).andReturn());

    assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
    assertThat(second.get("status").asText()).isEqualTo("PENDING");
  }

  @Test
  void counsellorWhoAlreadyHasAccessCannotKnock() throws Exception {
    actAs(sameAgencyCounsellor);
    knock().andExpect(status().isConflict());

    actAs(participant);
    knock().andExpect(status().isConflict());
  }

  @Test
  void knockingOnAnUnknownSeriesLooksLikeAWrongInviteLink() throws Exception {
    actAs(otherTraegerCounsellor);

    mvc.perform(
            withCsrf(
                post("/users/chat-series/{seriesId}/join-requests", 99_999_999L)
                    .queryParam("inviteToken", group.getInviteToken())))
        .andExpect(status().isForbidden());
  }

  @Test
  void knockingWithoutTheInviteTokenIsForbiddenAndPersistsNothing() throws Exception {
    actAs(otherTraegerCounsellor);

    mvc.perform(withCsrf(post("/users/chat-series/{seriesId}/join-requests", group.getId())))
        .andExpect(status().isForbidden());

    mine().andExpect(status().isNoContent());
  }

  @Test
  void knockingWithAWrongInviteTokenIsForbiddenAndPersistsNothing() throws Exception {
    actAs(otherTraegerCounsellor);

    knockWith("not-the-token").andExpect(status().isForbidden());
    knockWith("").andExpect(status().isForbidden());

    mine().andExpect(status().isNoContent());
  }

  @Test
  void aWrongTokenDoesNotRevealWhetherTheChatIsASelfHelpGroup() throws Exception {
    givenGroupClassifiedAs(ConversationType.INTERNAL_GROUP, false);
    actAs(otherTraegerCounsellor);

    knockWith("not-the-token").andExpect(status().isForbidden());
  }

  @Test
  void counsellorOfAnotherTraegerLinkedToTheGroupsAgencyMayKnock() throws Exception {
    consultantAgencyRepository.save(
        ConsultantAgency.builder()
            .consultant(consultantRepository.findById(otherTraegerCounsellor.getId()).orElseThrow())
            .agencyId(CHAT_AGENCY_ID)
            .tenantId(OTHER_TENANT_ID)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
    entityManager.flush();
    entityManager.clear();
    actAs(otherTraegerCounsellor);

    knock().andExpect(status().isCreated());
  }

  @Test
  void pendingKnockGrantsNoReadAccessToTheGroup() throws Exception {
    actAs(otherTraegerCounsellor);
    knock().andExpect(status().isCreated());

    mvc.perform(withCsrf(get("/users/chat/{chatId}", group.getId())))
        .andExpect(status().isForbidden());
  }

  @Test
  void ownMineReflectsTheNewestRequestOrNoContent() throws Exception {
    actAs(otherTraegerCounsellor);
    mine().andExpect(status().isNoContent());

    var created = readJson(knock().andReturn());

    mine()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(created.get("id").asLong()))
        .andExpect(jsonPath("$.status", is("PENDING")));
  }

  @Test
  void ownerSeesPendingRequestWithRequesterInfoButNoLeakToParticipants() throws Exception {
    actAs(otherTraegerCounsellor);
    var created = readJson(knock().andReturn());

    actAs(owner);
    pending()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(created.get("id").asLong()))
        .andExpect(jsonPath("$[0].seriesId").value(group.getId()))
        .andExpect(jsonPath("$[0].groupTitle", is(GROUP_TITLE)))
        .andExpect(jsonPath("$[0].status", is("PENDING")))
        .andExpect(jsonPath("$[0].via", is("INVITE_LINK")))
        .andExpect(jsonPath("$[0].viewerRole", is("OWNER")))
        .andExpect(jsonPath("$[0].requester.consultantId", is(otherTraegerCounsellor.getId())))
        .andExpect(jsonPath("$[0].requester.firstName", is(otherTraegerCounsellor.getFirstName())))
        .andExpect(jsonPath("$[0].requester.lastName", is(otherTraegerCounsellor.getLastName())))
        .andExpect(jsonPath("$[0].requester.agencyName", is("Beratungsstelle Süd")))
        .andExpect(jsonPath("$[0].requester.tenantName", is("Anderer Träger")))
        .andExpect(jsonPath("$[0].requester.sameAgency", is(false)))
        .andExpect(jsonPath("$[0].requester.sameTenant", is(false)));

    actAs(coModerator);
    pending()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].viewerRole", is("CO_MODERATOR")));

    actAs(participant);
    pending().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

    actAs(unrelatedCounsellor);
    pending().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void unresolvableAgencyAndTenantNamesAreNullNotAnError() throws Exception {
    when(agencyService.getAgency(OTHER_AGENCY_ID)).thenThrow(new IllegalStateException("down"));
    when(tenantService.getRestrictedTenantData(OTHER_TENANT_ID))
        .thenThrow(new IllegalStateException("down"));
    actAs(otherTraegerCounsellor);
    knock();

    actAs(owner);
    pending()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].requester.agencyName").value(nullValue()))
        .andExpect(jsonPath("$[0].requester.tenantName").value(nullValue()));
  }

  @Test
  void ownerAdmissionPersistsAnIntentWithoutGrantingAccessBeforeMatrixJoins() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    actAs(owner);
    admit(requestId, null).andExpect(status().isNoContent());

    assertThat(
            participantRepository.findBySeriesIdAndConsultantId(
                group.getId(), otherTraegerCounsellor.getId()))
        .isEmpty();
    assertThat(joinRequestRepository.findById(requestId).orElseThrow().getAdmissionRequestedAt())
        .isNotNull();
    verify(membershipService, never()).addMemberToRoom(any(Chat.class), any());

    actAs(otherTraegerCounsellor);
    mine().andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ADMITTING")));
    mvc.perform(withCsrf(get("/users/chat/{chatId}", group.getId())))
        .andExpect(status().isForbidden());
    mvc.perform(withCsrf(delete("/users/chat-series/{seriesId}/join-requests/mine", group.getId())))
        .andExpect(status().isConflict());

    actAs(owner);
    pending().andExpect(jsonPath("$", hasSize(0)));
    decline(requestId).andExpect(status().isConflict());
  }

  @Test
  void coModeratorAdmitsAsParticipant() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    actAs(coModerator);
    admit(requestId, "PARTICIPANT").andExpect(status().isNoContent());

    assertThat(joinRequestRepository.findById(requestId).orElseThrow().getAdmittedRole())
        .isEqualTo(ParticipantRole.PARTICIPANT);
  }

  @Test
  void plainParticipantOrUnrelatedCounsellorMayNotAdmitOrDecline() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    actAs(participant);
    admit(requestId, null).andExpect(status().isForbidden());
    decline(requestId).andExpect(status().isForbidden());

    actAs(unrelatedCounsellor);
    admit(requestId, null).andExpect(status().isForbidden());

    actAs(otherTraegerCounsellor);
    mine().andExpect(jsonPath("$.status", is("PENDING")));
    verify(membershipService, never()).addMemberToRoom(any(Chat.class), any());
  }

  @Test
  void onlyAnOwnerMayGrantCoModeratorAndOnlyWithinTheOwnersTenant() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    actAs(coModerator);
    admit(requestId, "CO_MODERATOR").andExpect(status().isForbidden());

    actAs(owner);
    admit(requestId, "CO_MODERATOR").andExpect(status().isBadRequest());

    actAs(otherTraegerCounsellor);
    mine().andExpect(jsonPath("$.status", is("PENDING")));
  }

  @Test
  void ownerAdmitsSameTenantRequesterAsCoModerator() throws Exception {
    actAs(unrelatedCounsellor);
    var requestId = readJson(knock().andExpect(status().isCreated()).andReturn());

    actAs(owner);
    admit(requestId.get("id").asLong(), "CO_MODERATOR").andExpect(status().isNoContent());

    assertThat(
            joinRequestRepository
                .findById(requestId.get("id").asLong())
                .orElseThrow()
                .getAdmittedRole())
        .isEqualTo(ParticipantRole.CO_MODERATOR);
  }

  @Test
  void admissionIsDurableEvenWhenMatrixIsUnavailable() throws Exception {
    when(membershipService.addMemberToRoom(any(Chat.class), any())).thenReturn(false);
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    actAs(owner);
    admit(requestId, null).andExpect(status().isNoContent());

    assertThat(
            participantRepository.findBySeriesIdAndConsultantId(
                group.getId(), otherTraegerCounsellor.getId()))
        .isEmpty();
    actAs(otherTraegerCounsellor);
    mine().andExpect(jsonPath("$.status", is("ADMITTING")));
    assertThat(joinRequestRepository.findById(requestId).orElseThrow().getAdmissionRequestedAt())
        .isNotNull();
  }

  @Test
  void admittingARequestOfAnotherSeriesOrAnUnknownRequestIsNotFound() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    actAs(owner);
    mvc.perform(
            withCsrf(
                post(
                    "/users/chat-series/{seriesId}/join-requests/{requestId}/admit",
                    group.getId(),
                    99_999_999L)))
        .andExpect(status().isNotFound());

    var otherGroup = anotherGroupOwnedBy(owner);
    mvc.perform(
            withCsrf(
                post(
                    "/users/chat-series/{seriesId}/join-requests/{requestId}/admit",
                    otherGroup.getId(),
                    requestId)))
        .andExpect(status().isNotFound());
  }

  @Test
  void declinedRequestIsVisibleToTheRequesterAndCannotBeAdmittedAnymore() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    actAs(owner);
    decline(requestId).andExpect(status().isNoContent());
    admit(requestId, null).andExpect(status().isConflict());
    decline(requestId).andExpect(status().isConflict());

    actAs(otherTraegerCounsellor);
    mine()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("DECLINED")))
        .andExpect(jsonPath("$.decidedAt").isNotEmpty());
    mvc.perform(withCsrf(get("/users/chat/{chatId}", group.getId())))
        .andExpect(status().isForbidden());
  }

  @Test
  void requesterCancelsAndModeratorsNoLongerSeeTheRequest() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andReturn()).get("id").asLong();

    mvc.perform(withCsrf(delete("/users/chat-series/{seriesId}/join-requests/mine", group.getId())))
        .andExpect(status().isNoContent());
    mvc.perform(withCsrf(delete("/users/chat-series/{seriesId}/join-requests/mine", group.getId())))
        .andExpect(status().isNoContent());
    mine()
        .andExpect(jsonPath("$.id").value(requestId))
        .andExpect(jsonPath("$.status", is("CANCELLED")));

    actAs(owner);
    pending().andExpect(jsonPath("$", hasSize(0)));
    admit(requestId, null).andExpect(status().isConflict());
  }

  @Test
  void knockingAgainAfterADeclineCreatesANewPendingRequest() throws Exception {
    actAs(otherTraegerCounsellor);
    var firstId = readJson(knock().andReturn()).get("id").asLong();
    actAs(owner);
    decline(firstId);

    actAs(otherTraegerCounsellor);
    var second = readJson(knock().andExpect(status().isCreated()).andReturn());

    assertThat(second.get("id").asLong()).isNotEqualTo(firstId);
    assertThat(second.get("status").asText()).isEqualTo("PENDING");
  }

  @Test
  void knockingOnAnInternalGroupChatIsRejectedAndPersistsNothing() throws Exception {
    givenGroupClassifiedAs(ConversationType.INTERNAL_GROUP, false);
    actAs(otherTraegerCounsellor);

    knock()
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", is("Only self-help groups accept join requests")));

    mine().andExpect(status().isNoContent());
  }

  @Test
  void legacyChatWithoutConversationTypeIsSelfHelpOnlyWhenItRepeats() throws Exception {
    actAs(otherTraegerCounsellor);

    givenGroupClassifiedAs(null, false);
    knock().andExpect(status().isBadRequest());

    givenGroupClassifiedAs(null, true);
    knock().andExpect(status().isCreated());
  }

  @Test
  void requestsOfANonSelfHelpChatAreNeverListedAdmittedOrDeclined() throws Exception {
    actAs(otherTraegerCounsellor);
    var requestId = readJson(knock().andExpect(status().isCreated()).andReturn()).get("id");
    givenGroupClassifiedAs(ConversationType.INTERNAL_GROUP, false);

    actAs(owner);
    pending().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    admit(requestId.asLong(), null).andExpect(status().isBadRequest());
    decline(requestId.asLong()).andExpect(status().isBadRequest());

    assertThat(
            participantRepository.findBySeriesIdAndConsultantId(
                group.getId(), otherTraegerCounsellor.getId()))
        .isEmpty();
    verify(membershipService, never()).addMemberToRoom(any(Chat.class), any());
  }

  private void givenGroupClassifiedAs(ConversationType conversationType, boolean repetitive) {
    var chat = chatRepository.findById(group.getId()).orElseThrow();
    chat.setConversationType(conversationType);
    chat.setRepetitive(repetitive);
    chatRepository.save(chat);
    entityManager.flush();
    entityManager.clear();
  }

  private Consultant prepare(Consultant consultant, long tenantId, String matrixUserId) {
    // Seed agencies would make "who shares an agency with the group" depend on seed data.
    consultantAgencyRepository.deleteAll(
        consultantAgencyRepository.findByConsultantId(consultant.getId()));
    consultant.setConsultantAgencies(new java.util.HashSet<>());
    consultant.setTenantId(tenantId);
    consultant.setMatrixUserId(matrixUserId);
    return consultantRepository.save(consultant);
  }

  private void saveParticipant(Consultant consultant, ParticipantRole role) {
    participantRepository.save(
        GroupChatParticipant.builder()
            .chatId(SESSION_ID)
            .seriesId(group.getId())
            .consultantId(consultant.getId())
            .role(role)
            .build());
  }

  private Chat anotherGroupOwnedBy(Consultant chatOwner) {
    var now = LocalDateTime.now();
    var other =
        chatRepository.save(
            Chat.builder()
                .topic("Another group")
                .consultingTypeId(1)
                .initialStartDate(now)
                .startDate(now)
                .duration(60)
                .active(true)
                .conversationType(ConversationType.SELF_HELP)
                .matrixRoomId("!other:matrix.test")
                .chatOwner(consultantRepository.findById(chatOwner.getId()).orElseThrow())
                .build());
    participantRepository.save(
        GroupChatParticipant.builder()
            .chatId(SESSION_ID + 1)
            .seriesId(other.getId())
            .consultantId(chatOwner.getId())
            .role(ParticipantRole.OWNER)
            .build());
    entityManager.flush();
    return other;
  }

  private void actAs(Consultant consultant) {
    when(authenticatedUser.getUserId()).thenReturn(consultant.getId());
    when(authenticatedUser.getUsername()).thenReturn(consultant.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));
  }

  private ResultActions knock() throws Exception {
    return knockWith(group.getInviteToken());
  }

  private ResultActions knockWith(String inviteToken) throws Exception {
    return mvc.perform(
        withCsrf(
            post("/users/chat-series/{seriesId}/join-requests", group.getId())
                .queryParam("inviteToken", inviteToken)));
  }

  private ResultActions mine() throws Exception {
    return mvc.perform(
        withCsrf(get("/users/chat-series/{seriesId}/join-requests/mine", group.getId())));
  }

  private ResultActions pending() throws Exception {
    return mvc.perform(withCsrf(get("/users/chat-series/join-requests")));
  }

  private ResultActions admit(long requestId, String role) throws Exception {
    var request =
        post(
            "/users/chat-series/{seriesId}/join-requests/{requestId}/admit",
            group.getId(),
            requestId);
    if (role != null) {
      request.contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}");
    }
    return mvc.perform(withCsrf(request));
  }

  private ResultActions decline(long requestId) throws Exception {
    return mvc.perform(
        withCsrf(
            post(
                "/users/chat-series/{seriesId}/join-requests/{requestId}/decline",
                group.getId(),
                requestId)));
  }

  private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
    return request
        .with(
            jwt()
                .authorities(new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))
                .jwt(token -> token.claim("userId", authenticatedUser.getUserId())))
        .cookie(CSRF_COOKIE)
        .header("X-CSRF-Token", "test");
  }

  private JsonNode readJson(org.springframework.test.web.servlet.MvcResult result)
      throws Exception {
    return readJson(result.getResponse().getContentAsString());
  }

  private JsonNode readJson(String json) throws Exception {
    return objectMapper.readTree(json);
  }

  private List<String> fieldNames(JsonNode node) {
    var names = new ArrayList<String>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
