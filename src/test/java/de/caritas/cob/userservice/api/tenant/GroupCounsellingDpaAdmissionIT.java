package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import java.io.IOException;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/** Authenticated public moderation admission with real committed persistence and owner policy. */
class GroupCounsellingDpaAdmissionIT extends GroupCounsellingDpaHttpFixture {
  private static final String GRACE =
      """
      {"dpaPublished":true,"dpaSigned":false,"dpaStatus":"OUTDATED",
       "currentDpaVersion":"v2","signingDeadlineAt":"2999-01-01T00:00:00Z",
       "renewalGraceActive":true,"newCounsellingAllowed":true}
      """;
  private static final String SIGNED =
      """
      {"dpaPublished":true,"dpaSigned":true,"dpaStatus":"VALID",
       "currentDpaVersion":"v2","signingDeadlineAt":"2999-01-01T00:00:00Z",
       "renewalGraceActive":false,"newCounsellingAllowed":true}
      """;
  @Autowired private ObjectMapper json;
  private Chat group;
  private Consultant requester;
  private boolean expireDuringMembershipRead;
  private int ownerStatusDuringMembershipRead = 200;

  @AfterEach
  void removeAdmissionRows() {
    if (group != null) {
      database.update(
          "DELETE FROM group_chat_admission_matrix_repair_task WHERE series_id = ?", group.getId());
      database.update("DELETE FROM group_chat_join_request WHERE series_id = ?", group.getId());
      database.update(
          "DELETE FROM group_appointment_mail_outbox WHERE series_id = ?", group.getId());
      database.update(
          "DELETE FROM group_appointment_occurrence_state WHERE series_id = ?", group.getId());
    }
  }

  @Test
  void expiredServingOwnerRefusesAdmissionAndLeavesTheRequestPending() throws Exception {
    long requestId = knock();

    var response = admit(requestId, consultant, "{}");

    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals("PENDING", ownStatus());
    assertEquals(1, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void legacyRepeatingGroupCannotBypassExpiredOwnerAdmission() throws Exception {
    long requestId = knock();
    group.setConversationType(null);
    group.setRepetitive(true);
    chats.save(group);

    var response = admit(requestId, consultant, "{}");

    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals("PENDING", ownStatus());
    assertEquals(1, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 503})
  void graceAdmissionRechecksExpiryOrOutageBeforeMatrixWrites(int ownerStatusAfterPreflight)
      throws Exception {
    long requestId = knock();
    gate = GRACE;
    expireDuringMembershipRead = true;
    ownerStatusDuringMembershipRead = ownerStatusAfterPreflight;

    var response = admit(requestId, consultant, "{}");

    assertEquals(204, response.statusCode(), response.body());
    assertEquals("ADMITTING", ownStatus());
    assertEquals(2, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void currentConfirmationRestoresAdmissionWithoutRecreatingThePendingRequest() throws Exception {
    long requestId = knock();
    assertEquals(403, admit(requestId, consultant, "{}").statusCode());
    gate = SIGNED;

    var response = admit(requestId, consultant, "{}");

    assertEquals(204, response.statusCode(), response.body());
    assertEquals("ADMITTED", ownStatus());
    assertEquals(3, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void renewalGracePermitsNormalAdmission() throws Exception {
    long requestId = knock();
    gate = GRACE;

    var response = admit(requestId, consultant, "{}");

    assertEquals(204, response.statusCode(), response.body());
    assertEquals("ADMITTED", ownStatus());
    assertEquals(2, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void ownerOutageIsAServiceFailureAndLeavesTheRequestPending() throws Exception {
    long requestId = knock();
    ownerStatus = 503;

    var response = admit(requestId, consultant, "{}");

    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertFalse(response.body().contains("synthetic-owner-private-detail"));
    assertEquals("PENDING", ownStatus());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"newCounsellingAllowed\":true}"
      })
  void malformedOwnerDecisionIsAServiceFailureWithoutAdmission(String malformed) throws Exception {
    long requestId = knock();
    gate = malformed;

    var response = admit(requestId, consultant, "{}");

    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals("PENDING", ownStatus());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void nonModeratorIsRefusedBeforeAnyOwnerDecision() throws Exception {
    long requestId = knock();
    gate = GRACE;

    var response = admit(requestId, requester, "{}");

    assertEquals(403, response.statusCode(), response.body());
    assertEquals("PENDING", ownStatus());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void anotherTenantsSignedRequesterKeepsTheExistingTenantRefusal() throws Exception {
    long requestId = knock(42L);

    var response = admit(requestId, consultant, "{}");

    assertEquals(404, response.statusCode(), response.body());
    assertEquals("PENDING", ownStatus());
    assertEquals(0, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void anExistingParticipantRowOnlyResolvesTheRequestWithoutNewMatrixAccess() throws Exception {
    long requestId = knock();
    participants.save(
        GroupChatParticipant.builder()
            .chatId(group.getId())
            .seriesId(group.getId())
            .consultantId(requester.getId())
            .role(ParticipantRole.PARTICIPANT)
            .build());
    ownerStatus = 503;

    var response = admit(requestId, consultant, "{}");

    assertEquals(204, response.statusCode(), response.body());
    assertEquals("ADMITTED", ownStatus());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void internalGroupsKeepTheirExistingAdmissionRouteRefusalWithoutAnAvvDecision() throws Exception {
    long requestId = knock();
    group.setConversationType(ConversationType.INTERNAL_GROUP);
    chats.save(group);

    var response = admit(requestId, consultant, "{}");

    assertEquals(400, response.statusCode(), response.body());
    assertEquals("PENDING", ownStatus());
    assertEquals(0, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aLaterOccurrenceKeepsItsExistingAdmissionWhileRecurrencePolicyIsPending(boolean legacy)
      throws Exception {
    long requestId = knock();
    group.setCurrentOccurrenceIndex(1);
    if (legacy) {
      group.setConversationType(null);
      group.setRepetitive(true);
    }
    chats.save(group);

    var response = admit(requestId, consultant, "{}");

    assertEquals(204, response.statusCode(), response.body());
    assertEquals("ADMITTED", ownStatus());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void aLaterLegacyOccurrenceKeepsItsExistingAssignmentWithoutAnOwnerDecision() throws Exception {
    laterLegacyGroup();
    var recipient = fixtures.adviceSeeker(42L);

    var response =
        request(
            "PUT",
            "/users/chat/" + group.getId() + "/assign?inviteToken=" + group.getInviteToken(),
            recipient.getUserId(),
            recipient.getUsername(),
            "user",
            42L,
            "");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void aLaterLegacyOccurrenceKeepsItsExistingModeratorAdditionWithoutAnOwnerDecision()
      throws Exception {
    laterLegacyGroup();
    var moderator = fixtures.consultant(OWNER, AGENCY);

    var response =
        call(
            "PUT",
            "/users/chat/" + group.getId() + "/update",
            consultant,
            "{\"topic\":\"Synthetic later group\",\"startDate\":\"2999-01-01\",\"startTime\":\"12:00\",\"duration\":60,\"repeatCount\":2,\"timezone\":\"UTC\",\"consultantIds\":[\""
                + moderator.getId()
                + "\"]}");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  private void laterLegacyGroup() {
    group = storedChat(ConversationType.SELF_HELP);
    group.setConversationType(null);
    group.setRepetitive(true);
    group.setRepeatCount(2);
    group.setChatInterval(Chat.ChatInterval.WEEKLY);
    group.setCurrentOccurrenceIndex(1);
    group = chats.save(group);
  }

  private long knock() throws Exception {
    return knock(OWNER);
  }

  private long knock(long requesterTenant) throws Exception {
    group = storedChat(ConversationType.SELF_HELP);
    group.setInviteToken("synthetic-admission-invite");
    group.setActive(true);
    group = chats.save(group);
    requester = fixtures.consultant(requesterTenant);
    var response =
        call(
            "POST",
            "/users/chat-series/"
                + group.getId()
                + "/join-requests?inviteToken="
                + group.getInviteToken(),
            requester,
            "");
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body()).path("id").asLong();
  }

  private HttpResponse<String> admit(long requestId, Consultant moderator, String body)
      throws Exception {
    return call(
        "POST",
        "/users/chat-series/" + group.getId() + "/join-requests/" + requestId + "/admit",
        moderator,
        body);
  }

  private String ownStatus() throws Exception {
    var response =
        call("GET", "/users/chat-series/" + group.getId() + "/join-requests/mine", requester, "");
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body()).path("status").asText();
  }

  private HttpResponse<String> call(String method, String path, Consultant caller, String body)
      throws Exception {
    return request(
        method,
        path,
        caller.getId(),
        caller.getUsername(),
        "consultant",
        caller.getTenantId(),
        body);
  }

  @Override
  protected ClientHttpResponse additionalExternalResponse(ClientHttpRequest request)
      throws IOException {
    String path = request.getURI().getPath();
    if (path.startsWith("/_synapse/admin/v1/rooms/") && path.endsWith("/members")) {
      if (expireDuringMembershipRead) {
        gate = EXPIRED;
        ownerStatus = ownerStatusDuringMembershipRead;
      }
      return withSuccess("{\"members\":[]}", MediaType.APPLICATION_JSON).createResponse(request);
    }
    if (path.endsWith("/invite") || path.endsWith("/join") || path.contains("/join/")) {
      matrixWrites.incrementAndGet();
      return withSuccess(
              "{\"room_id\":\"!synthetic-stored:synthetic.oriso.test\"}",
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    return super.additionalExternalResponse(request);
  }
}
