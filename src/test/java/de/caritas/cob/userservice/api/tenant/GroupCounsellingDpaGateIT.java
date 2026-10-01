package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Public AVV behavior of independent group creation, first opening and new enrolment. */
class GroupCounsellingDpaGateIT extends GroupCounsellingDpaHttpFixture {
  @Test
  void expiredOwnerRefusesExternalCreationBeforeAnyGroupOrMatrixWrite() throws Exception {
    var response = create(true, "/users/chat/v2/new");
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertTrue(chats.findByChatOwner(consultant).isEmpty());
    assertEquals(0, matrixWrites.get());
    assertEquals(
        0,
        database.queryForObject(
            "SELECT COUNT(*) FROM user WHERE user_id = 'group-chat-system-41'", Integer.class));
  }

  @Test
  void expiredOwnerRefusesOpeningAnUnbegunFirstOccurrence() throws Exception {
    var chat = storedChat(ConversationType.SELF_HELP);
    var response = start(chat, consultant);
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertFalse(chats.findById(chat.getId()).orElseThrow().isActive());
    assertEquals(
        0,
        database.queryForObject(
            "SELECT COUNT(*) FROM event_notification WHERE deduplication_key LIKE ?",
            Integer.class,
            "group-chat:%:" + chat.getId() + ":%"));
  }

  @Test
  void foreignRegisteredInviteRecipientCannotEnrolWhenTheServingOwnerHasExpired() throws Exception {
    var chat = storedChat(ConversationType.SELF_HELP);
    var recipient = fixtures.adviceSeeker(42L);
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/assign?inviteToken=" + chat.getInviteToken(),
            recipient.getUserId(),
            recipient.getUsername(),
            "user",
            42L,
            "");
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(1, ownerReads.get());
    assertEquals(
        0,
        database.queryForObject(
            "SELECT COUNT(*) FROM user_chat WHERE chat_id = ?", Integer.class, chat.getId()));
  }

  private enum Route {
    CREATE_V1,
    CREATE_V2,
    FIRST_START,
    ROOM_ASSIGN,
    INVITE_ASSIGN
  }

  private static java.util.stream.Stream<Arguments> deniedPolicies() {
    String unsigned =
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"UNSIGNED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":false}";
    return java.util.Arrays.stream(Route.values())
        .flatMap(
            route ->
                java.util.stream.Stream.of(
                        EXPIRED, unsigned, "{\"dpaPublished\":true,\"dpaSigned\":false}")
                    .map(response -> Arguments.of(route, response)));
  }

  private static java.util.stream.Stream<Arguments> permittedPolicies() {
    return java.util.Arrays.stream(Route.values())
        .flatMap(
            route ->
                java.util.stream.Stream.of(
                        "{\"dpaPublished\":true,\"dpaSigned\":true}",
                        "{\"dpaPublished\":true,\"dpaSigned\":true,\"dpaStatus\":\"VALID\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":null,\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}",
                        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"OUTDATED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":true,\"newCounsellingAllowed\":true}")
                    .map(response -> Arguments.of(route, response)));
  }

  private Chat addressedChat;

  private HttpResponse<String> enter(Route route) throws Exception {
    if (route == Route.CREATE_V1 || route == Route.CREATE_V2) {
      return create(true, route == Route.CREATE_V1 ? "/users/chat/new" : "/users/chat/v2/new");
    }
    addressedChat = storedChat(ConversationType.SELF_HELP);
    if (route == Route.FIRST_START) return start(addressedChat, consultant);
    // This occurrence is already open. Registration still does not establish participation.
    addressedChat.setActive(true);
    chats.save(addressedChat);
    var recipient = fixtures.adviceSeeker(42L);
    String reference =
        route == Route.INVITE_ASSIGN
            ? addressedChat.getId().toString()
            : addressedChat.getMatrixRoomId();
    String query =
        route == Route.INVITE_ASSIGN ? "?inviteToken=" + addressedChat.getInviteToken() : "";
    return request(
        "PUT",
        "/users/chat/" + reference + "/assign" + query,
        recipient.getUserId(),
        recipient.getUsername(),
        "user",
        42L,
        "");
  }

  private void assertNoFirstEntryWrites() {
    assertEquals(0, matrixWrites.get());
    if (addressedChat == null) {
      assertTrue(chats.findByChatOwner(consultant).isEmpty());
      assertEquals(
          0,
          database.queryForObject(
              "SELECT COUNT(*) FROM session WHERE consultant_id = ?",
              Integer.class,
              consultant.getId()));
      assertEquals(
          systemUserExisted ? 1 : 0,
          database.queryForObject(
              "SELECT COUNT(*) FROM user WHERE user_id = 'group-chat-system-41'", Integer.class));
    } else {
      assertEquals(
          0,
          database.queryForObject(
              "SELECT COUNT(*) FROM user_chat WHERE chat_id = ?",
              Integer.class,
              addressedChat.getId()));
      assertEquals(
          0,
          database.queryForObject(
              "SELECT COUNT(*) FROM event_notification WHERE deduplication_key LIKE ?",
              Integer.class,
              "group-chat:%:" + addressedChat.getId() + ":%"));
      assertEquals(
          addressedChat.isActive(), chats.findById(addressedChat.getId()).orElseThrow().isActive());
    }
  }

  @ParameterizedTest
  @MethodSource("deniedPolicies")
  void unsignedExpiredAndLegacyUnsignedOwnersRefuseNewExternalWork(
      Route route, String ownerResponse) throws Exception {
    gate = ownerResponse;
    var response = enter(route);
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(1, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertNoFirstEntryWrites();
  }

  @ParameterizedTest
  @MethodSource("permittedPolicies")
  void currentRenewalGraceAndCompleteLegacySignaturePermitExternalWork(
      Route route, String ownerResponse) throws Exception {
    gate = ownerResponse;
    var response = enter(route);
    assertEquals(
        route == Route.CREATE_V1 || route == Route.CREATE_V2 ? 201 : 200,
        response.statusCode(),
        response.body());
    assertEquals(1, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    if (addressedChat == null) {
      var created = chats.findByChatOwner(consultant).getFirst();
      assertEquals(ConversationType.SELF_HELP, created.getConversationType());
      assertFalse(created.isActive());
      assertEquals(1, matrixWrites.get());
      assertEquals(
          41L,
          database.queryForObject(
              "SELECT tenant_id FROM session WHERE consultant_id = ?",
              Long.class,
              consultant.getId()));
    } else if (route == Route.FIRST_START) {
      assertTrue(chats.findById(addressedChat.getId()).orElseThrow().isActive());
      assertEquals(
          1,
          database.queryForObject(
              "SELECT COUNT(*) FROM event_notification WHERE deduplication_key LIKE ?",
              Integer.class,
              "group-chat:%:" + addressedChat.getId() + ":%"));
    } else {
      assertEquals(
          1,
          database.queryForObject(
              "SELECT COUNT(*) FROM user_chat WHERE chat_id = ?",
              Integer.class,
              addressedChat.getId()));
    }
  }

  @ParameterizedTest
  @EnumSource(Route.class)
  void unavailableOwnerRefusesNewExternalWorkWithSanitizedDependencyFailure(Route route)
      throws Exception {
    ownerStatus = 503;
    var response = enter(route);
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertFalse(response.body().contains("synthetic-owner-private-detail"));
    assertNoFirstEntryWrites();
  }

  @ParameterizedTest
  @EnumSource(Route.class)
  void malformedOwnerRefusesNewExternalWorkAsDependencyFailure(Route route) throws Exception {
    gate = "{\"newCounsellingAllowed\":false}";
    var response = enter(route);
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertNoFirstEntryWrites();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"/users/chat/new", "/users/chat/v2/new"})
  void internalColleagueCreationRemainsExemptEvenWhenTheOwnerIsUnavailable(String path)
      throws Exception {
    ownerStatus = 503;
    var response = create(false, path);
    assertEquals(201, response.statusCode(), response.body());
    var chat = chats.findByChatOwner(consultant).getFirst();
    assertEquals(ConversationType.INTERNAL_GROUP, chat.getConversationType());
    assertTrue(chat.isActive());
    assertEquals(0, ownerReads.get());
    assertEquals(1, matrixWrites.get());
  }

  @Test
  void internalColleagueStartAndRoomEnrolmentRemainExempt() throws Exception {
    ownerStatus = 503;
    var chat = storedChat(ConversationType.INTERNAL_GROUP);
    assertEquals(200, start(chat, consultant).statusCode());
    var recipient = fixtures.adviceSeeker(42L);
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getMatrixRoomId() + "/assign",
            recipient.getUserId(),
            recipient.getUsername(),
            "user",
            42L,
            "");
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
  }

  @Test
  void alreadyEnrolledUserKeepsDuplicateConflictBeforeAnyOwnerRead() throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var chat = storedChat(ConversationType.SELF_HELP);
    var recipient = fixtures.adviceSeeker(42L);
    String path = "/users/chat/" + chat.getId() + "/assign?inviteToken=" + chat.getInviteToken();
    assertEquals(
        200,
        request("PUT", path, recipient.getUserId(), recipient.getUsername(), "user", 42L, "")
            .statusCode());
    ownerReads.set(0);
    ownerStatus = 503;
    var duplicate =
        request("PUT", path, recipient.getUserId(), recipient.getUsername(), "user", 42L, "");
    assertEquals(409, duplicate.statusCode(), duplicate.body());
    assertEquals(0, ownerReads.get());
    assertEquals(
        1,
        database.queryForObject(
            "SELECT COUNT(*) FROM user_chat WHERE chat_id = ?", Integer.class, chat.getId()));
  }

  @Test
  void wrongInviteTokenIsRefusedBeforeReadingOwnerPolicy() throws Exception {
    var chat = storedChat(ConversationType.SELF_HELP);
    var recipient = fixtures.adviceSeeker(42L);
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/assign?inviteToken=invalid",
            recipient.getUserId(),
            recipient.getUsername(),
            "user",
            42L,
            "");
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
  }

  @Test
  void nonModeratorCannotStartOrReadTheOwnerPolicy() throws Exception {
    var chat = storedChat(ConversationType.SELF_HELP);
    var outsider = fixtures.consultant(42L, 420L);
    var response = start(chat, outsider);
    assertEquals(403, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
    assertFalse(chats.findById(chat.getId()).orElseThrow().isActive());
  }

  @Test
  void alreadyOpenedOccurrenceRetainsConflictBeforeOwnerRead() throws Exception {
    var chat = storedChat(ConversationType.SELF_HELP);
    chat.setActive(true);
    chats.save(chat);
    var response = start(chat, consultant);
    assertEquals(409, response.statusCode(), response.body());
    assertEquals(0, ownerReads.get());
  }

  @Test
  void disagreementBetweenStoredOwnerAndServingAgencyRefusesFirstStartAsDependencyFailure()
      throws Exception {
    agencyTenant = 42L;
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var chat = storedChat(ConversationType.SELF_HELP);
    var response = start(chat, consultant);
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertFalse(chats.findById(chat.getId()).orElseThrow().isActive());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"/users/chat/new", "/users/chat/v2/new"})
  void foreignAgencyWithSignedOwnerCannotCreateAWrongOwnedExternalGroup(String path)
      throws Exception {
    agencyTenant = 42L;
    var response = create(true, path);
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertNoFirstEntryWrites();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource(
      value = {"NULL", "0"},
      nullValues = "NULL")
  void unknownOrTechnicalStoredOwnerCannotBeReplacedByTheInviteRecipient(Long tenant)
      throws Exception {
    var chat = storedChat(ConversationType.SELF_HELP);
    database.update(
        "UPDATE consultant SET tenant_id = ? WHERE consultant_id = ?", tenant, consultant.getId());
    var recipient = fixtures.adviceSeeker(42L);
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/assign?inviteToken=" + chat.getInviteToken(),
            recipient.getUserId(),
            recipient.getUsername(),
            "user",
            42L,
            "");
    assertEquals(502, response.statusCode(), response.body());
    assertEquals("DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse(""));
    assertEquals(0, ownerReads.get());
    assertEquals(0, recipientOwnerReads.get());
    assertEquals(
        0,
        database.queryForObject(
            "SELECT COUNT(*) FROM user_chat WHERE chat_id = ?", Integer.class, chat.getId()));
  }
}
