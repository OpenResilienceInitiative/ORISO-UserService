package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.model.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Real public HTTP and rotation regressions for the confirmed recurring-group policy. */
class GroupCounsellingRecurringMatrixIT extends GroupCounsellingMatrixHistoryHttpFixture {
  @Autowired
  de.caritas.cob.userservice.api.service.matrixgroup.ChatMatrixRoomHistory roomProvenance;

  @Autowired de.caritas.cob.userservice.api.actions.chat.ChatReCreator reCreator;

  @Test
  void aNewParticipantCannotEnterALaterOccurrenceAfterExpiry() throws Exception {
    Chat chat = activeChat();
    chat.setRepetitive(true);
    chat.setRepeatCount(3);
    chat.setChatInterval(Chat.ChatInterval.WEEKLY);
    chats.save(chat);
    reCreator.updateAsNextChat(chat, "!synthetic-next:synthetic.oriso.test");
    chat.setActive(true);
    chats.save(chat);
    participation = "NOT_COMMENCED";
    var response = join(chat, assignedUser(chat));
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, matrixWrites.get());
  }

  private Chat laterOccurrence(boolean completeHistory) {
    Chat chat = activeChat();
    chat.setRepetitive(true);
    chat.setRepeatCount(3);
    chat.setChatInterval(Chat.ChatInterval.WEEKLY);
    chats.save(chat);
    if (completeHistory) {
      reCreator.updateAsNextChat(chat, "!synthetic-next:synthetic.oriso.test");
      chat.setActive(true);
    } else {
      chat.setCurrentOccurrenceIndex(1);
    }
    return chats.save(chat);
  }

  @Test
  void aFormerParticipantFromAnEarlierOccurrenceCanContinueAfterExpiry() throws Exception {
    Chat chat = laterOccurrence(true);
    participation = "NOT_COMMENCED";
    roomParticipation.put("!synthetic-stored:synthetic.oriso.test", "COMMENCED");
    var response = join(chat, assignedUser(chat));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(2, historyReads.get());
    assertEquals(0, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void incompleteLegacyHistoryDoesNotMislabelUncertainReturnAsALegalRefusal() throws Exception {
    Chat chat = laterOccurrence(false);
    participation = "NOT_COMMENCED";
    var response = join(chat, assignedUser(chat));
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(1, ownerReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void incompleteLegacyHistoryStillAllowsNewWorkWhenTheFreshOwnerAllowsIt() throws Exception {
    Chat chat = laterOccurrence(false);
    participation = "NOT_COMMENCED";
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var response = join(chat, assignedUser(chat));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, ownerReads.get());
    assertEquals(2, matrixWrites.get());
  }

  @Test
  void knownAppliedParticipationContinuesEvenWhenOlderRoomProvenanceIsIncomplete()
      throws Exception {
    Chat chat = laterOccurrence(false);
    var response = join(chat, assignedUser(chat));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, historyReads.get());
    assertEquals(0, ownerReads.get());
  }

  @Test
  void nativeContextBindsTheCurrentAndEarlierRoomsAndItsCompleteness() throws Exception {
    Chat chat = laterOccurrence(true);
    var response =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                Map.of(
                    "contractVersion",
                    2,
                    "operation",
                    "CONTEXT",
                    "roomId",
                    chat.getMatrixRoomId(),
                    "matrixUserId",
                    "@actor:synthetic.oriso.test")));
    assertEquals(200, response.statusCode(), response.body());
    var body = json.readTree(response.body());
    assertEquals(6, body.size());
    assertTrue(body.path("historyComplete").booleanValue());
    assertEquals(chat.getMatrixRoomId(), body.path("roomIds").get(0).textValue());
    assertEquals("!synthetic-stored:synthetic.oriso.test", body.path("roomIds").get(1).textValue());
    assertEquals(0, ownerReads.get());
    assertEquals(0, historyReads.get());
  }

  @Test
  void nativeOwnerGateAlsoRejectsNewParticipationAtLaterOccurrences() throws Exception {
    Chat chat = laterOccurrence(true);
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
                    "@actor:synthetic.oriso.test")));
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    var body = json.readTree(response.body());
    assertEquals(1, body.size());
    assertEquals("DPA_NEW_COUNSELLING_NOT_ALLOWED", body.path("reason").textValue());
    assertEquals(0, historyReads.get());
  }

  @Test
  void aReservedNextRoomIsBoundAndRefusesNewNativeWorkBeforeCanonicalAdvance() throws Exception {
    Chat chat = activeChat();
    var oldRoom = chat.getMatrixRoomId();
    var prepared = roomProvenance.reserveNextRoom(chat, "!prepared:synthetic.oriso.test");
    assertEquals(
        prepared, roomProvenance.reserveNextRoom(chat, "!unused-race:synthetic.oriso.test"));
    var response =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                Map.of(
                    "contractVersion",
                    2,
                    "operation",
                    "CONTEXT",
                    "roomId",
                    prepared,
                    "matrixUserId",
                    "@actor:synthetic.oriso.test")));
    assertEquals(200, response.statusCode(), response.body());
    var context = json.readTree(response.body());
    assertEquals(prepared, context.path("roomIds").get(0).textValue());
    assertEquals(oldRoom, context.path("roomIds").get(1).textValue());
    assertTrue(context.path("historyComplete").booleanValue());
    var refusal =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                Map.of(
                    "contractVersion",
                    1,
                    "roomId",
                    prepared,
                    "matrixUserId",
                    "@new:synthetic.oriso.test")));
    assertEquals(403, refusal.statusCode(), refusal.body());
    assertEquals(oldRoom, chats.findById(chat.getId()).orElseThrow().getMatrixRoomId());
    assertEquals(0, historyReads.get());
    assertEquals(0, matrixWrites.get());
  }

  @Test
  void failedMemberCopyKeepsTheOldRoomAndRetryReusesItsBoundNextRoom() throws Exception {
    Chat chat = recurringForRotation();
    var actor = assignedUser(chat);
    members =
        json.writeValueAsString(
            Map.of(
                "members",
                java.util.List.of(consultant.getMatrixUserId(), actor.getMatrixUserId())));
    matrixInviteDenied = true;
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException.class,
        () -> Tenants.in(OWNER, () -> reCreator.recreateMessengerChat(chat)));
    var prepared = roomProvenance.preparedNextRoom(chat).orElseThrow();
    assertEquals("!synthetic-created:synthetic.oriso.test", prepared);
    assertEquals(0, snapshotCount);
    assertEquals(0, purgeCount);
    matrixInviteDenied = false;
    var next = Tenants.in(OWNER, () -> reCreator.recreateMessengerChat(chat));
    assertEquals(prepared, next);
    assertEquals(1, snapshotCount);
    assertEquals(1, purgeCount);
    assertTrue(copiedActors.contains(actor.getMatrixUserId()));
    reCreator.updateAsNextChat(chat, next);
    assertEquals(1, chats.findById(chat.getId()).orElseThrow().getCurrentOccurrenceIndex());
  }

  @Test
  void aForeignAssignmentWithoutActualRoomMembershipIsNotCopiedToTheNextOccurrence()
      throws Exception {
    Chat chat = recurringForRotation();
    var neverJoined = assignedUser(chat);
    members =
        json.writeValueAsString(
            Map.of(
                "members",
                java.util.List.of(
                    consultant.getMatrixUserId(), "@technical:synthetic.oriso.test")));
    Tenants.in(OWNER, () -> reCreator.recreateMessengerChat(chat));
    assertFalse(copiedActors.contains(neverJoined.getMatrixUserId()));
    assertFalse(copiedActors.contains("@technical:synthetic.oriso.test"));
    assertTrue(copiedActors.isEmpty());
  }

  @Test
  void missingDurableSnapshotPreventsRecurringPurgeAndLeavesRetryableProvenance() throws Exception {
    Chat chat = recurringForRotation();
    members =
        json.writeValueAsString(Map.of("members", java.util.List.of(consultant.getMatrixUserId())));
    snapshotUnavailable = true;
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
            .class,
        () -> Tenants.in(OWNER, () -> reCreator.recreateMessengerChat(chat)));
    assertEquals(0, purgeCount);
    assertTrue(roomProvenance.preparedNextRoom(chat).isPresent());
    assertEquals(
        "!synthetic-stored:synthetic.oriso.test",
        chats.findById(chat.getId()).orElseThrow().getMatrixRoomId());
  }

  @Test
  void trueGroupDeletionForgetsEveryStoredRoomBeforeDeletingTheSeries() throws Exception {
    Chat chat = laterOccurrence(true);
    roomProvenance.reserveNextRoom(chat, "!prepared-delete:synthetic.oriso.test");
    chat.setRepetitive(false);
    chats.save(chat);
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/stop",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            OWNER,
            "");
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        java.util.Set.of(
            "!synthetic-stored:synthetic.oriso.test",
            "!prepared-delete:synthetic.oriso.test",
            chat.getMatrixRoomId()),
        java.util.Set.copyOf(forgottenRooms));
    assertTrue(chats.findById(chat.getId()).isEmpty());
    assertEquals(3, purgeCount);
  }

  @Test
  void failedReceiptErasurePreservesTheSeriesAndItsRetryableProvenance() throws Exception {
    Chat chat = laterOccurrence(true);
    chat.setRepetitive(false);
    chats.save(chat);
    forgetUnavailable = true;
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/stop",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            OWNER,
            "");
    assertEquals(502, response.statusCode(), response.body());
    assertTrue(chats.findById(chat.getId()).isPresent());
    assertEquals(1, purgeCount);
  }

  private Chat recurringForRotation() {
    Chat chat = activeChat();
    chat.setRepetitive(true);
    chat.setRepeatCount(3);
    chat.setChatInterval(Chat.ChatInterval.WEEKLY);
    return chats.save(chat);
  }

  @Test
  void nativeSeriesContextUsesStoredRoomProvenanceWithoutLegalOrHistoryCalls() throws Exception {
    Chat chat = activeChat();
    var response =
        nativePolicy(
            "synthetic-dedicated-membership-policy-credential",
            json.writeValueAsString(
                Map.of(
                    "contractVersion",
                    2,
                    "operation",
                    "CONTEXT",
                    "roomId",
                    chat.getMatrixRoomId(),
                    "matrixUserId",
                    "@actor:synthetic.oriso.test")));
    assertEquals(200, response.statusCode(), response.body());
    var body = json.readTree(response.body());
    assertEquals(2, body.path("contractVersion").intValue());
    assertTrue(body.path("applicable").booleanValue());
    assertEquals(chat.getMatrixRoomId(), body.path("roomIds").get(0).textValue());
    assertEquals(0, ownerReads.get());
    assertEquals(0, historyReads.get());
    assertEquals(0, matrixWrites.get());
  }
}
