package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

/** Actual public join/update APIs: assignment is not proof of begun participation. */
class GroupCounsellingDpaEntryIT extends GroupCounsellingDpaHttpFixture {
  @Autowired private UserChatRepository userChats;
  @Autowired private UserRepository users;
  private final AtomicInteger memberReads = new AtomicInteger();
  private final List<String> membershipWritePaths = new ArrayList<>();
  private final List<String> invitedMemberBodies = new ArrayList<>();
  private String members = "{\"members\":[]}";
  private int memberStatus = 200;
  private boolean leaveSucceeds = true;

  @Test
  void assignedNewcomerUsesServingOwnerBeforeMatrixAdmission() throws Exception {
    Chat chat = activeChat(ConversationType.SELF_HELP);
    User newcomer = assignedUser(chat);

    var response = join(chat, newcomer);

    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(1, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, matrixWrites.get());
    assertEquals(
        1,
        database.queryForObject(
            "SELECT COUNT(*) FROM user_chat WHERE chat_id = ?", Integer.class, chat.getId()));
  }

  @Test
  void actualJoinedParticipantContinuesWithoutAnotherOwnerDecision() throws Exception {
    Chat chat = activeChat(ConversationType.SELF_HELP);
    User participant = assignedUser(chat);
    members = "{\"members\":[\"" + participant.getMatrixUserId() + "\"]}";
    ownerStatus = 503;

    var response = join(chat, participant);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, memberReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void internalColleagueGroupKeepsItsExistingJoinWithoutOwnerOrMembershipPreflight()
      throws Exception {
    Chat chat = activeChat(ConversationType.INTERNAL_GROUP);
    ownerStatus = 503;

    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/join",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            OWNER,
            "");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, memberReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"members\":\"unknown\"}",
        "{\"members\":[null]}",
        "{\"members\":[42]}",
        "{\"members\":[\"invalid\"]}"
      })
  void malformedMembershipDoesNotInventContinuationOrLegalDenial(String malformed)
      throws Exception {
    Chat chat = activeChat(ConversationType.SELF_HELP);
    User newcomer = assignedUser(chat);
    members = malformed;

    var response = join(chat, newcomer);

    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void unavailableMembershipIsSanitizedAndDoesNotAdmitTheParticipant() throws Exception {
    Chat chat = activeChat(ConversationType.SELF_HELP);
    User newcomer = assignedUser(chat);
    memberStatus = 503;

    var response = join(chat, newcomer);

    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertFalse(response.body().contains("synthetic-private-membership-detail"));
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void anInactiveFirstOccurrenceKeepsItsExistingConflictBeforeAnyPolicyOrAdmissionRead()
      throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    User newcomer = assignedUser(chat);

    var response = join(chat, newcomer);

    assertEquals(409, response.statusCode(), response.body());
    assertEquals(0, memberReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void addingANewCoModeratorCannotChangeSettingsOrMembershipAfterExpiry() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant newModerator = fixtures.consultant(OWNER, AGENCY);

    var response = update(chat, "[\"" + newModerator.getId() + "\"]");

    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(1, ownerReads.get());
    assertEquals(0, matrixWrites.get());
    assertEquals("Synthetic AVV group", chats.findById(chat.getId()).orElseThrow().getTopic());
    assertEquals(1, participants.findBySeriesId(chat.getId()).size());
  }

  @Test
  void disabledHistoricalIntegrationKeepsOnePureOwnerDecisionForAModeratorBatch() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant first = fixtures.consultant(OWNER, AGENCY);
    Consultant second = fixtures.consultant(OWNER, AGENCY);
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var response = update(chat, "[\"" + first.getId() + "\",\"" + second.getId() + "\"]");
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, ownerReads.get());
    assertEquals(4, matrixWrites.get());
    assertEquals(3, participants.findBySeriesId(chat.getId()).size());
  }

  @Test
  void aRefusedMixedModeratorUpdateDoesNotRemoveTheExistingModeratorFromMatrix() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant existing = addModerator(chat);
    Consultant newcomer = fixtures.consultant(OWNER, AGENCY);

    var response = update(chat, "[\"" + newcomer.getId() + "\"]");

    assertEquals(403, response.statusCode(), response.body());
    assertEquals(0, matrixWrites.get());
    assertTrue(
        participants.findBySeriesId(chat.getId()).stream()
            .anyMatch(member -> member.getConsultantId().equals(existing.getId())));
    assertEquals("Synthetic AVV group", chats.findById(chat.getId()).orElseThrow().getTopic());
  }

  @Test
  void removingAModeratorRemainsPossibleWhileTheOwnerServiceIsUnavailable() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant moderator = addModerator(chat);
    members = "{\"members\":[\"" + moderator.getMatrixUserId() + "\"]}";
    ownerStatus = 503;

    var response = update(chat, "[]");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
    assertEquals(1, matrixWrites.get());
    assertEquals(1, participants.findBySeriesId(chat.getId()).size());
    assertEquals("Changed synthetic group", chats.findById(chat.getId()).orElseThrow().getTopic());
  }

  @Test
  void failedMatrixRemovalRetainsModeratorTrackingAndRollsBackTheUpdate() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant moderator = addModerator(chat);
    members = "{\"members\":[\"" + moderator.getMatrixUserId() + "\"]}";
    leaveSucceeds = false;
    ownerStatus = 503;

    var response = update(chat, "[]");

    assertEquals(500, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
    assertEquals(1, matrixWrites.get());
    assertEquals(2, participants.findBySeriesId(chat.getId()).size());
    assertEquals("Synthetic AVV group", chats.findById(chat.getId()).orElseThrow().getTopic());
  }

  @Test
  void settingsAndExistingModeratorsCanChangeWithoutOpeningNewCounselling() throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant existing = addModerator(chat);
    ownerStatus = 503;

    var response = update(chat, "[\"" + existing.getId() + "\"]");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
    // The existing database row is not proof of Matrix access: retry only this member's
    // idempotent invitation/join without asking the unavailable DPA owner for new enrolment.
    assertEquals(2, matrixWrites.get());
    assertEquals(
        List.of(
            "POST /_matrix/client/r0/rooms/" + chat.getMatrixRoomId() + "/invite",
            "POST /_matrix/client/r0/rooms/" + chat.getMatrixRoomId() + "/join"),
        membershipWritePaths);
    assertEquals(
        List.of("{\"user_id\":\"" + existing.getMatrixUserId() + "\"}"), invitedMemberBodies);
    assertEquals(
        Set.of(consultant.getId(), existing.getId()),
        participants.findBySeriesId(chat.getId()).stream()
            .map(GroupChatParticipant::getConsultantId)
            .collect(Collectors.toSet()));
    assertEquals(2, participants.findBySeriesId(chat.getId()).size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"dpaPublished\":true,\"dpaSigned\":true}",
        "{\"dpaPublished\":true,\"dpaSigned\":true,\"dpaStatus\":\"VALID\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":null,\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}",
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"OUTDATED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":true,\"newCounsellingAllowed\":true}"
      })
  void currentSignatureAndRenewalGracePermitNewcomerAdmissionAndNewModerators(String policy)
      throws Exception {
    gate = policy;
    Chat active = activeChat(ConversationType.SELF_HELP);
    var joinResponse = join(active, assignedUser(active));
    assertEquals(200, joinResponse.statusCode(), joinResponse.body());
    assertEquals(2, matrixWrites.get());

    Chat editable = storedChat(ConversationType.SELF_HELP);
    Consultant moderator = fixtures.consultant(OWNER, AGENCY);
    var updateResponse = update(editable, "[\"" + moderator.getId() + "\"]");
    assertEquals(200, updateResponse.statusCode(), updateResponse.body());
    assertEquals(2, ownerReads.get());
    assertEquals(4, matrixWrites.get());
    assertEquals(2, participants.findBySeriesId(editable.getId()).size());
  }

  @Test
  void aMissingOwnerDecisionRefusesBothNewJoinAndModeratorAdmissionWithoutMatrixWrites()
      throws Exception {
    ownerStatus = 503;
    Chat active = activeChat(ConversationType.SELF_HELP);
    var joinResponse = join(active, assignedUser(active));
    assertEquals(502, joinResponse.statusCode(), joinResponse.body());
    assertEquals(
        "DPA_POLICY_UNAVAILABLE", joinResponse.headers().firstValue("X-Reason").orElse(""));

    Chat editable = storedChat(ConversationType.SELF_HELP);
    Consultant moderator = fixtures.consultant(OWNER, AGENCY);
    var updateResponse = update(editable, "[\"" + moderator.getId() + "\"]");
    assertEquals(502, updateResponse.statusCode(), updateResponse.body());
    assertEquals(
        "DPA_POLICY_UNAVAILABLE", updateResponse.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, matrixWrites.get());
    assertFalse(updateResponse.body().contains("synthetic-owner-private-detail"));
  }

  @Test
  void noAssignmentKeepsItsPermissionDenialBeforeMembershipOrPolicyLookups() throws Exception {
    Chat chat = activeChat(ConversationType.SELF_HELP);
    User outsider = fixtures.adviceSeeker(42L);

    var response = join(chat, outsider);

    assertEquals(403, response.statusCode(), response.body());
    assertNotEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, ownerReads.get());
    assertEquals(0, memberReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "90"})
  void invalidUpdateInputHasNoMatrixEffectsBeforeItFails(String duration) throws Exception {
    Chat chat = storedChat(ConversationType.SELF_HELP);
    Consultant moderator = fixtures.consultant(OWNER, AGENCY);
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    String body = updateBody("[\"" + moderator.getId() + "\"]");
    body =
        duration.equals("null")
            ? body.replace("\"duration\":90", "\"duration\":null")
            : body.replace("Europe/Berlin", "Not/A_Timezone");

    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/update",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            OWNER,
            body);

    assertEquals(duration.equals("null") ? 500 : 400, response.statusCode(), response.body());
    assertEquals(0, matrixWrites.get());
    assertEquals(0, ownerReads.get());
  }

  private Consultant addModerator(Chat chat) {
    Consultant moderator = fixtures.consultant(OWNER, AGENCY);
    participants.save(
        GroupChatParticipant.builder()
            .seriesId(chat.getId())
            .chatId(chat.getId())
            .consultantId(moderator.getId())
            .role(ParticipantRole.CO_MODERATOR)
            .build());
    return moderator;
  }

  private HttpResponse<String> update(Chat chat, String ids) throws Exception {
    return request(
        "PUT",
        "/users/chat/" + chat.getId() + "/update",
        consultant.getId(),
        consultant.getUsername(),
        "consultant",
        OWNER,
        updateBody(ids));
  }

  private String updateBody(String ids) {
    return "{\"topic\":\"Changed synthetic group\",\"startDate\":\"2999-01-02\",\"startTime\":\"14:00\",\"duration\":90,\"repetitive\":false,\"timezone\":\"Europe/Berlin\",\"consultantIds\":"
        + ids
        + "}";
  }

  private Chat activeChat(ConversationType type) {
    var chat = storedChat(type);
    chat.setActive(true);
    return chats.save(chat);
  }

  private User assignedUser(Chat chat) {
    var user = fixtures.adviceSeeker(42L);
    user.setMatrixUserId("@synthetic-newcomer:synthetic.oriso.test");
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
    if (path.startsWith("/_synapse/admin/v1/rooms/") && path.endsWith("/members")) {
      memberReads.incrementAndGet();
      return memberStatus == 200
          ? withSuccess(members, MediaType.APPLICATION_JSON).createResponse(request)
          : withStatus(HttpStatus.valueOf(memberStatus))
              .body("synthetic-private-membership-detail")
              .createResponse(request);
    }
    if (path.endsWith("/invite")
        || path.endsWith("/join")
        || path.contains("/join/")
        || path.endsWith("/leave")) {
      matrixWrites.incrementAndGet();
      membershipWritePaths.add(request.getMethod() + " " + path);
      if (path.endsWith("/invite")) {
        invitedMemberBodies.add(((MockClientHttpRequest) request).getBodyAsString());
      }
      if (path.endsWith("/leave")) {
        if (!leaveSucceeds)
          return withStatus(HttpStatus.INTERNAL_SERVER_ERROR).createResponse(request);
        members = "{\"members\":[]}";
      }
      return withSuccess(
              "{\"room_id\":\"!synthetic-stored:synthetic.oriso.test\"}",
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    return super.additionalExternalResponse(request);
  }
}
