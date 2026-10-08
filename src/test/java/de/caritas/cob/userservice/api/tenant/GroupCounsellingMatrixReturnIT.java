package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.TestPropertySource;

/** Public authenticated return paths; Synapse and owner HTTP are the only outgoing fixtures. */
@TestPropertySource(
    properties = {
      "matrix.group.policy.enabled=true",
      "matrix.group.policy.token=synthetic-dedicated-membership-policy-credential",
      "matrix.group.participation-history.url=http://synapse.synthetic/oriso-internal/group-participation-history"
    })
class GroupCounsellingMatrixReturnIT extends GroupCounsellingDpaHttpFixture {
  @Autowired UserChatRepository userChats;
  @Autowired UserRepository users;
  @Autowired de.caritas.cob.userservice.api.service.chat.GroupChatAdmissionProcessor processor;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("matrixGroupHistoryRestTemplate")
  private org.springframework.web.client.RestTemplate historyTransport;

  private org.springframework.test.web.client.MockRestServiceServer historyDownstream;

  @BeforeEach
  void authenticatedHistoryHttpFixture() {
    historyDownstream =
        org.springframework.test.web.client.MockRestServiceServer.bindTo(historyTransport).build();
    historyDownstream
        .expect(
            org.springframework.test.web.client.ExpectedCount.between(0, Integer.MAX_VALUE),
            org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
        .andRespond(this::additionalExternalResponse);
  }

  private final ObjectMapper json = new ObjectMapper();
  private final AtomicInteger historyReads = new AtomicInteger();
  private final AtomicInteger memberReads = new AtomicInteger();
  private int historyUnavailableAtRead;
  private String historyBodyOverride;
  private boolean historyNon200;
  private boolean matrixInviteDenied;
  private Chat foreignChat;
  private MediaType historyContentType = MediaType.APPLICATION_JSON;
  private final java.util.Set<String> notCommencedActors = new java.util.HashSet<>();
  @Autowired de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants;
  private final AtomicInteger historyBytesRead = new AtomicInteger();
  private String participation = "COMMENCED";
  private String members = "{\"members\":[]}";

  @Test
  void authorizedFormerParticipantReturnsToTheSameFirstRoomAfterExpiry() throws Exception {
    Chat chat = activeChat();
    User former = assignedUser(chat);
    var response = join(chat, former);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void aValidCurrentInviteRestoresAnUnassignedFormerParticipantAfterExpiry() throws Exception {
    Chat chat = activeChat();
    chat.setInviteToken("synthetic-current-invite");
    chats.save(chat);
    User former = fixtures.adviceSeeker(42L);
    former.setMatrixUserId("@synthetic-former:synthetic.oriso.test");
    Tenants.in(42L, () -> users.save(former));
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/assign?inviteToken=synthetic-current-invite",
            former.getUserId(),
            former.getUsername(),
            "user",
            former.getTenantId(),
            "");
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void anAuthorizedFormerCoModeratorCanBeSelectedAgainAfterExpiry() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant former = fixtures.consultant(OWNER, AGENCY);
    var response = update(chat, java.util.List.of(former.getId()));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
    assertEquals(2, participants.findBySeriesId(chat.getId()).size());
  }

  private HttpResponse<String> update(Chat chat, java.util.List<String> ids) throws Exception {
    return request(
        "PUT",
        "/users/chat/" + chat.getId() + "/update",
        consultant.getId(),
        consultant.getUsername(),
        "consultant",
        OWNER,
        "{\"topic\":\"Changed synthetic group\",\"startDate\":\"2999-01-02\",\"startTime\":\"14:00\",\"duration\":90,\"repetitive\":false,\"timezone\":\"Europe/Berlin\",\"consultantIds\":"
            + json.writeValueAsString(ids)
            + "}");
  }

  private Chat requestedGroup;
  private Consultant requester;

  @AfterEach
  void removeJoinRequestRows() {
    historyDownstream.verify();
    if (foreignChat != null) Tenants.acrossAll(() -> chats.deleteById(foreignChat.getId()));
    if (requestedGroup != null) {
      for (String table :
          java.util.List.of(
              "group_chat_admission_matrix_repair_task",
              "group_chat_join_request",
              "group_appointment_mail_outbox",
              "group_appointment_occurrence_state"))
        database.update("DELETE FROM " + table + " WHERE series_id = ?", requestedGroup.getId());
    }
  }

  @Test
  void aModeratorCanReadmitAnAuthorizedFormerParticipantAfterExpiry() throws Exception {
    requestedGroup = activeChat();
    requestedGroup.setInviteToken("synthetic-current-invite");
    chats.save(requestedGroup);
    requester = fixtures.consultant(OWNER);
    var knock =
        consultantCall(
            "POST",
            "/users/chat-series/"
                + requestedGroup.getId()
                + "/join-requests?inviteToken=synthetic-current-invite",
            requester,
            "");
    assertEquals(201, knock.statusCode(), knock.body());
    long requestId = json.readTree(knock.body()).path("id").asLong();
    var response =
        consultantCall(
            "POST",
            "/users/chat-series/"
                + requestedGroup.getId()
                + "/join-requests/"
                + requestId
                + "/admit",
            consultant,
            "{}");
    assertEquals(204, response.statusCode(), response.body());
    var status =
        consultantCall(
            "GET",
            "/users/chat-series/" + requestedGroup.getId() + "/join-requests/mine",
            requester,
            "");
    assertEquals(200, status.statusCode(), status.body());
    assertEquals("ADMITTED", json.readTree(status.body()).path("status").asText());
    assertEquals(2, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void aRevokedDecidingModeratorCannotReplayAQueuedHistoricalReturn() throws Exception {
    long id = knockNewRequest();
    var moderator = fixtures.consultant(OWNER, AGENCY);
    participants.save(
        GroupChatParticipant.builder()
            .chatId(requestedGroup.getId())
            .seriesId(requestedGroup.getId())
            .consultantId(moderator.getId())
            .role(GroupChatParticipant.ParticipantRole.CO_MODERATOR)
            .build());
    historyUnavailableAtRead = 2;
    var admitted =
        consultantCall(
            "POST",
            "/users/chat-series/" + requestedGroup.getId() + "/join-requests/" + id + "/admit",
            moderator,
            "{}");
    assertEquals(204, admitted.statusCode(), admitted.body());
    assertEquals("ADMITTING", ownRequestStatus());
    assertEquals(0, matrixWrites.get());
    var revoked =
        consultantCall(
            "PUT",
            "/users/chat-series/"
                + requestedGroup.getId()
                + "/participants/"
                + moderator.getId()
                + "/role",
            consultant,
            "{\"role\":\"PARTICIPANT\"}");
    assertEquals(204, revoked.statusCode(), revoked.body());
    historyUnavailableAtRead = 0;
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> Tenants.acrossAll(() -> processor.process(id)));
    assertEquals("ADMITTING", ownRequestStatus());
    assertEquals(2, historyReads.get());
    assertEquals(1, memberReads.get());
    assertEquals(0, matrixWrites.get());
  }

  private long knockNewRequest() throws Exception {
    requestedGroup = activeChat();
    requestedGroup.setInviteToken("synthetic-current-invite");
    chats.save(requestedGroup);
    requester = fixtures.consultant(OWNER);
    var knock =
        consultantCall(
            "POST",
            "/users/chat-series/"
                + requestedGroup.getId()
                + "/join-requests?inviteToken=synthetic-current-invite",
            requester,
            "");
    assertEquals(201, knock.statusCode(), knock.body());
    return json.readTree(knock.body()).path("id").asLong();
  }

  private String ownRequestStatus() throws Exception {
    var result =
        consultantCall(
            "GET",
            "/users/chat-series/" + requestedGroup.getId() + "/join-requests/mine",
            requester,
            "");
    assertEquals(200, result.statusCode(), result.body());
    return json.readTree(result.body()).path("status").asText();
  }

  private HttpResponse<String> consultantCall(
      String method, String path, Consultant caller, String body) throws Exception {
    return request(
        method,
        path,
        caller.getId(),
        caller.getUsername(),
        "consultant",
        caller.getTenantId(),
        body);
  }

  @Test
  void ambiguousCommittedRoomOwnershipCannotSupplyAHistoricalExemption() throws Exception {
    Chat chat = activeChat();
    User former = assignedUser(chat);
    duplicateForeignOwnerRoom(chat);
    var response = join(chat, former);
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void aMalformedAuthenticatedHistoryBodyCannotLeakPrivateDataOrGrantReturn() throws Exception {
    Chat chat = activeChat();
    User former = assignedUser(chat);
    historyBodyOverride =
        "{\"contractVersion\":1,\"schemaVersion\":\"synapse-1.158.0-applied-membership-v1\",\"roomId\":\"!synthetic-stored:synthetic.oriso.test\",\"matrixUserId\":\"@synthetic-former:synthetic.oriso.test\",\"participation\":\"COMMENCED\"} synthetic-private-history-detail";
    var response = join(chat, former);
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertFalse(response.body().contains("synthetic-private-history-detail"));
    assertFalse(response.body().contains("synthetic-dedicated-membership-policy-credential"));
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void enabledPolicyDoesNotTreatALegacyFirstSelfHelpRoomAsContinuationForANewcomer()
      throws Exception {
    Chat chat = activeChat();
    chat.setConversationType(null);
    chat.setRepetitive(true);
    chat.setRepeatCount(2);
    chat.setChatInterval(Chat.ChatInterval.WEEKLY);
    chats.save(chat);
    participation = "NOT_COMMENCED";
    var response = join(chat, assignedUser(chat));
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(1, historyReads.get());
    assertEquals(1, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void aHistoryOutageRefusesReturnWithoutConsumingAnUnboundedPrivateErrorBody() throws Exception {
    Chat chat = activeChat();
    historyNon200 = true;
    var response = join(chat, assignedUser(chat));
    assertEquals(502, response.statusCode(), response.body());
    assertEquals(0, historyBytesRead.get());
    assertFalse(response.body().contains("synthetic-private-history-detail"));
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void missingNativeDedicatedCredentialIsRejectedBeforeRoomResolution() throws Exception {
    var response =
        nativePolicy(
            null,
            "{\"contractVersion\":1,\"roomId\":\"!ordinary:synthetic.oriso.test\",\"matrixUserId\":\"@actor:synthetic.oriso.test\"}");
    assertEquals(401, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void nativeFirstAdmissionUsesTheServingOwnerPureGateWithoutHistoryOrMembershipWrites()
      throws Exception {
    Chat chat = activeChat();
    var response =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                Map.of(
                    "contractVersion",
                    1,
                    "roomId",
                    chat.getMatrixRoomId(),
                    "matrixUserId",
                    "@foreign-signed:synthetic.oriso.test")));
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(1, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void aQueuedReturnRecoversAfterHistoryOutageUsingFreshAuthorizationAndHistory() throws Exception {
    long id = knockNewRequest();
    historyUnavailableAtRead = 2;
    var admitted =
        consultantCall(
            "POST",
            "/users/chat-series/" + requestedGroup.getId() + "/join-requests/" + id + "/admit",
            consultant,
            "{}");
    assertEquals(204, admitted.statusCode(), admitted.body());
    assertEquals("ADMITTING", ownRequestStatus());
    assertEquals(0, matrixWrites.get());
    historyUnavailableAtRead = 0;
    Tenants.acrossAll(() -> processor.process(id));
    assertEquals("ADMITTED", ownRequestStatus());
    assertEquals(3, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void aQueuedCoModeratorCannotReturnWithNowInvalidTenantPermissions() throws Exception {
    long id = knockNewRequest();
    historyUnavailableAtRead = 2;
    var admitted =
        consultantCall(
            "POST",
            "/users/chat-series/" + requestedGroup.getId() + "/join-requests/" + id + "/admit",
            consultant,
            "{\"role\":\"CO_MODERATOR\"}");
    assertEquals(204, admitted.statusCode(), admitted.body());
    assertEquals("ADMITTING", ownRequestStatus());
    Tenants.acrossAll(
        () -> {
          var changed = consultants.findById(requester.getId()).orElseThrow();
          changed.setTenantId(42L);
          consultants.save(changed);
        });
    historyUnavailableAtRead = 0;
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class,
        () -> Tenants.acrossAll(() -> processor.process(id)));
    assertEquals(2, historyReads.get());
    assertEquals(1, memberReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PARTICIPANT", "CO_MODERATOR"})
  void anAuthorizedQueuedCurrentJoinCompletesWithoutHistoryWhenTheParticipantRowIsMissing(
      String role) throws Exception {
    long id = knockNewRequest();
    historyUnavailableAtRead = 2;
    var accepted =
        consultantCall(
            "POST",
            "/users/chat-series/" + requestedGroup.getId() + "/join-requests/" + id + "/admit",
            consultant,
            "{\"role\":\"" + role + "\"}");
    assertEquals(204, accepted.statusCode(), accepted.body());
    assertEquals("ADMITTING", ownRequestStatus());
    assertTrue(
        participants
            .findBySeriesIdAndConsultantId(requestedGroup.getId(), requester.getId())
            .isEmpty());
    assertEquals(0, matrixWrites.get());
    members = "{\"members\":[\"" + requester.getMatrixUserId() + "\"]}";
    historyNon200 = true;
    ownerStatus = 503;
    assertDoesNotThrow(() -> Tenants.acrossAll(() -> processor.process(id)));
    assertEquals("ADMITTED", ownRequestStatus());
    var moderatorPermission =
        consultantCall("PUT", "/users/chat/" + requestedGroup.getId() + "/verify", requester, "");
    assertEquals(
        role.equals("CO_MODERATOR") ? 200 : 403,
        moderatorPermission.statusCode(),
        moderatorPermission.body());
    assertEquals(2, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void aMixedReturningAndNewModeratorSelectionIsRefusedAtomically() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    var existing = fixtures.consultant(OWNER, AGENCY);
    participants.save(
        GroupChatParticipant.builder()
            .chatId(chat.getId())
            .seriesId(chat.getId())
            .consultantId(existing.getId())
            .role(GroupChatParticipant.ParticipantRole.CO_MODERATOR)
            .build());
    var former = fixtures.consultant(OWNER, AGENCY);
    var newcomer = fixtures.consultant(OWNER, AGENCY);
    notCommencedActors.add(newcomer.getMatrixUserId());
    var result = update(chat, java.util.List.of(former.getId(), newcomer.getId()));
    assertEquals(403, result.statusCode(), result.body());
    assertEquals(2, historyReads.get());
    assertEquals(1, ownerReads.get());
    assertEquals(0, matrixWrites.get());
    assertEquals("Synthetic AVV group", chats.findById(chat.getId()).orElseThrow().getTopic());
    assertTrue(
        participants.findBySeriesId(chat.getId()).stream()
            .anyMatch(p -> p.getConsultantId().equals(existing.getId())));
    assertEquals(2, participants.findBySeriesId(chat.getId()).size());
  }

  @Test
  void aCurrentJoinedParticipantContinuesDuringOwnerAndHistoryOutages() throws Exception {
    Chat chat = activeChat();
    User former = assignedUser(chat);
    members = "{\"members\":[\"" + former.getMatrixUserId() + "\"]}";
    historyNon200 = true;
    ownerStatus = 503;
    var response = join(chat, former);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void registrationOrInvitationWithoutAnAppliedJoinStillRequiresFreshOwnerPermission()
      throws Exception {
    Chat chat = activeChat();
    participation = "NOT_COMMENCED";
    var result = join(chat, assignedUser(chat));
    assertEquals(403, result.statusCode(), result.body());
    assertEquals(1, historyReads.get());
    assertEquals(1, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void aCurrentOwnerSignatureAllowsANewParticipantWithoutInventingHistoricalParticipation()
      throws Exception {
    Chat chat = activeChat();
    participation = "NOT_COMMENCED";
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var response = join(chat, assignedUser(chat));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, historyReads.get());
    assertEquals(1, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void aRevokedInviteAndAnUnassignedActorRemainDeniedBeforeAnyHistoryRead() throws Exception {
    Chat chat = activeChat();
    chat.setInviteToken("synthetic-current-invite");
    chats.save(chat);
    User actor = fixtures.adviceSeeker(42L);
    var revoked =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/assign?inviteToken=synthetic-revoked-invite",
            actor.getUserId(),
            actor.getUsername(),
            "user",
            42L,
            "");
    assertEquals(403, revoked.statusCode(), revoked.body());
    var outsider = join(chat, actor);
    assertEquals(403, outsider.statusCode(), outsider.body());
    assertEquals(0, historyReads.get());
    assertEquals(0, memberReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ROOM",
        "ACTOR",
        "SCHEMA",
        "VERSION",
        "UNAVAILABLE",
        "DUPLICATE",
        "OVERSIZE",
        "CONTENT_TYPE"
      })
  void untrustedHistoryBindingsCannotGrantReturnOrLeakRawData(String variant) throws Exception {
    Chat chat = activeChat();
    User former = assignedUser(chat);
    String valid =
        "{\"contractVersion\":1,\"schemaVersion\":\"synapse-1.158.0-applied-membership-v1\",\"roomId\":\"!synthetic-stored:synthetic.oriso.test\",\"matrixUserId\":\"@synthetic-former:synthetic.oriso.test\",\"participation\":\"COMMENCED\"}";
    historyBodyOverride =
        switch (variant) {
          case "ROOM" -> valid.replace("!synthetic-stored", "!synthetic-other-room");
          case "ACTOR" -> valid.replace("@synthetic-former", "@synthetic-other-actor");
          case "SCHEMA" ->
              valid.replace(
                  "synapse-1.158.0-applied-membership-v1", "synthetic-private-unsupported-schema");
          case "VERSION" -> valid.replace("contractVersion\":1", "contractVersion\":2");
          case "UNAVAILABLE" -> valid.replace("COMMENCED", "UNAVAILABLE");
          case "DUPLICATE" ->
              valid.replace("contractVersion\":1", "contractVersion\":0,\"contractVersion\":1");
          case "OVERSIZE" -> valid + " ".repeat(4096);
          default -> valid;
        };
    if (variant.equals("CONTENT_TYPE")) historyContentType = MediaType.TEXT_PLAIN;
    var result = join(chat, former);
    assertEquals(502, result.statusCode(), result.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", result.headers().firstValue("X-Reason").orElse(""));
    assertFalse(result.body().contains("synthetic-private"));
    assertFalse(result.body().contains("synthetic-dedicated-membership-policy-credential"));
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "synthetic-matrix-token"})
  void missingOrWrongNativeCredentialRefusesMalformedBodyBeforeParsing(String token)
      throws Exception {
    var result = nativePolicy(token.isEmpty() ? null : token, "{synthetic-private-malformed-input");
    assertEquals(401, result.statusCode(), result.body());
    assertFalse(result.body().contains("synthetic-private"));
    assertEquals(0, ownerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void onlyTheExactNativePostResourceHasMachineAuthenticationExemptions() throws Exception {
    for (var path :
        java.util.List.of(
            "/service/internal/matrix/group-join-policy",
            "/internal/matrix/group-join-policy/extra")) {
      var result =
          nativeRequest("POST", path, "synthetic-dedicated-membership-policy-credential", "{}");
      assertEquals(401, result.statusCode(), result.body());
    }
    var get =
        nativeRequest(
            "GET",
            "/internal/matrix/group-join-policy",
            "synthetic-dedicated-membership-policy-credential",
            "");
    assertEquals(401, get.statusCode(), get.body());
    assertEquals(0, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN", "INTERNAL", "LATER"})
  void nativePolicyDoesNotApplyToOrdinaryInternalOrLaterRooms(String kind) throws Exception {
    String room = "!ordinary-support-room:synthetic.oriso.test";
    if (!kind.equals("UNKNOWN")) {
      Chat chat =
          storedChat(
              kind.equals("INTERNAL")
                  ? ConversationType.INTERNAL_GROUP
                  : ConversationType.SELF_HELP);
      if (kind.equals("LATER")) {
        chat.setCurrentOccurrenceIndex(1);
        chats.save(chat);
      }
      room = chat.getMatrixRoomId();
    }
    var result =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                java.util.Map.of(
                    "contractVersion",
                    1,
                    "roomId",
                    room,
                    "matrixUserId",
                    "@ordinary:synthetic.oriso.test")));
    assertEquals(200, result.statusCode(), result.body());
    assertFalse(json.readTree(result.body()).path("applicable").asBoolean());
    assertEquals(0, ownerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, memberReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 503})
  void nativeFirstAdmissionUsesCurrentOwnerAllowOrSanitizedUnavailable(int status)
      throws Exception {
    Chat chat = activeChat();
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    ownerStatus = status;
    var result =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                java.util.Map.of(
                    "contractVersion",
                    1,
                    "roomId",
                    chat.getMatrixRoomId(),
                    "matrixUserId",
                    "@foreign-signed:synthetic.oriso.test")));
    assertEquals(status == 200 ? 200 : 502, result.statusCode(), result.body());
    if (status == 200) assertTrue(json.readTree(result.body()).path("applicable").asBoolean());
    else assertEquals("DPA_POLICY_UNAVAILABLE", result.headers().firstValue("X-Reason").orElse(""));
    assertFalse(result.body().contains("synthetic-owner-private-detail"));
    assertEquals(1, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, memberReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void nativeMalformedAuthenticatedInputHasNoOwnerLookupOrRawErrorCause() throws Exception {
    var result =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            "{\"contractVersion\":1,\"roomId\":\"!ordinary:synthetic.oriso.test\",\"matrixUserId\":\"@actor:synthetic.oriso.test\"} synthetic-private-trailing-body");
    assertEquals(400, result.statusCode(), result.body());
    assertFalse(result.body().contains("synthetic-private"));
    assertEquals(0, ownerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, matrixWrites.get());
  }

  private HttpResponse<String> nativeRequest(String method, String path, String token, String body)
      throws Exception {
    var builder =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body));
    if (token != null) builder.header("X-Oriso-Group-Policy-Token", token);
    return java.net.http.HttpClient.newHttpClient()
        .send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void nativeAmbiguousRoomOwnerCannotSelectTheRecipientsSignedTenant() throws Exception {
    Chat chat = activeChat();
    duplicateForeignOwnerRoom(chat);
    var result =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                java.util.Map.of(
                    "contractVersion",
                    1,
                    "roomId",
                    chat.getMatrixRoomId(),
                    "matrixUserId",
                    "@foreign-signed:synthetic.oriso.test")));
    assertEquals(502, result.statusCode(), result.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", result.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, memberReads.get());
    assertEquals(0, matrixWrites.get());
  }

  private void duplicateForeignOwnerRoom(Chat original) {
    var foreign = fixtures.consultant(42L);
    foreignChat =
        Tenants.in(
            42L,
            () ->
                chats.save(
                    Chat.builder()
                        .topic("Foreign synthetic group")
                        .chatOwner(foreign)
                        .consultingTypeId(1)
                        .initialStartDate(original.getInitialStartDate())
                        .startDate(original.getStartDate())
                        .duration(60)
                        .conversationType(ConversationType.SELF_HELP)
                        .matrixRoomId(original.getMatrixRoomId())
                        .active(false)
                        .build()));
  }

  @Test
  void historicalParticipationDoesNotOverrideASynapsePermissionOrBanRefusal() throws Exception {
    Chat chat = activeChat();
    matrixInviteDenied = true;
    var result = join(chat, assignedUser(chat));
    assertEquals(500, result.statusCode(), result.body());
    assertEquals(1, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(1, matrixWrites.get());
  }

  @Test
  void laterOccurrenceModeratorBatchRetainsOnePureOwnerDecisionWithoutHistory() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    chat.setCurrentOccurrenceIndex(1);
    chats.save(chat);
    var first = fixtures.consultant(OWNER, AGENCY);
    var second = fixtures.consultant(OWNER, AGENCY);
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    historyNon200 = true;
    var result = update(chat, java.util.List.of(first.getId(), second.getId()));
    assertEquals(200, result.statusCode(), result.body());
    assertEquals(1, ownerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(4, matrixWrites.get());
  }

  private HttpResponse<String> nativePolicy(String token, String body) throws Exception {
    var builder =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://localhost:" + port + "/internal/matrix/group-join-policy"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
    if (token != null) builder.header("X-Oriso-Group-Policy-Token", token);
    return java.net.http.HttpClient.newHttpClient()
        .send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Chat activeChat() {
    var chat = storedChat(ConversationType.SELF_HELP);
    chat.setActive(true);
    return chats.save(chat);
  }

  private User assignedUser(Chat chat) {
    var user = fixtures.adviceSeeker(42L);
    user.setMatrixUserId("@synthetic-former:synthetic.oriso.test");
    Tenants.in(42L, () -> users.save(user));
    userChats.save(UserChat.builder().user(user).chat(chat).build());
    return user;
  }

  private HttpResponse<String> join(Chat chat, User user) throws Exception {
    return request(
        "PUT",
        "/users/chat/" + chat.getId() + "/join",
        user.getUserId(),
        user.getUsername(),
        "user",
        user.getTenantId(),
        "");
  }

  @Override
  protected ClientHttpResponse additionalExternalResponse(ClientHttpRequest request)
      throws IOException {
    String path = request.getURI().getPath();
    if (path.equals("/oriso-internal/group-participation-history")) {
      historyReads.incrementAndGet();
      assertEquals(
          "synthetic-dedicated-membership-policy-credential",
          request.getHeaders().getFirst("X-Oriso-Group-Policy-Token"));
      if (historyNon200) {
        var raw =
            withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body("synthetic-private-history-detail".repeat(500))
                .createResponse(request);
        return new ClientHttpResponse() {
          public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return raw.getStatusCode();
          }

          public String getStatusText() throws IOException {
            return raw.getStatusText();
          }

          public org.springframework.http.HttpHeaders getHeaders() {
            return raw.getHeaders();
          }

          public java.io.InputStream getBody() throws IOException {
            return new java.io.FilterInputStream(raw.getBody()) {
              public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                if (count > 0) historyBytesRead.addAndGet(count);
                return count;
              }
            };
          }

          public void close() {
            raw.close();
          }
        };
      }
      if (historyBodyOverride != null)
        return withSuccess(historyBodyOverride, historyContentType).createResponse(request);
      assertNull(request.getHeaders().getFirst("Authorization"));
      var asked =
          json.readTree(
              ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                  .getBodyAsString());
      assertEquals(1, asked.path("contractVersion").intValue());
      assertEquals("synapse-1.158.0-applied-membership-v1", asked.path("schemaVersion").asText());
      return withSuccess(
              json.writeValueAsString(
                  Map.of(
                      "contractVersion",
                      1,
                      "schemaVersion",
                      "synapse-1.158.0-applied-membership-v1",
                      "roomId",
                      asked.get("roomId").asText(),
                      "matrixUserId",
                      asked.get("matrixUserId").asText(),
                      "participation",
                      historyReads.get() == historyUnavailableAtRead
                          ? "UNAVAILABLE"
                          : notCommencedActors.contains(asked.get("matrixUserId").asText())
                              ? "NOT_COMMENCED"
                              : participation)),
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    if (path.startsWith("/_synapse/admin/v1/rooms/") && path.endsWith("/members")) {
      memberReads.incrementAndGet();
      return withSuccess(members, MediaType.APPLICATION_JSON).createResponse(request);
    }
    if (path.endsWith("/invite")
        || path.endsWith("/join")
        || path.contains("/join/")
        || path.endsWith("/leave")) {
      matrixWrites.incrementAndGet();
      if (matrixInviteDenied && path.endsWith("/invite"))
        return withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
            .body("{\"errcode\":\"M_FORBIDDEN\",\"error\":\"User is banned\"}")
            .createResponse(request);
      return withSuccess(
              "{\"room_id\":\"!synthetic-stored:synthetic.oriso.test\"}",
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    return super.additionalExternalResponse(request);
  }
}
