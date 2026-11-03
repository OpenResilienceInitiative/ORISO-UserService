package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.service.chat.InternalGroupChatRecipientScopeResolver;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ORISO-UserService#1418: the members-only room for a subset of selected colleagues.
 *
 * <p>Real HTTP, security, JPA and Matrix client. Only the other side of every outgoing call is
 * played: Keycloak, AgencyService, TenantService and a stateful Synapse that remembers its rooms,
 * invitations and joins and whether a room was created encrypted. The assertions are on what a
 * counsellor and Matrix can observe, never on a call that was made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testing")
@TestPropertySource(
    properties = {
      "matrix.adminUsername=synthetic-matrix-admin",
      "matrix.adminPassword=synthetic-fixture",
      "matrix.encryptionEnabled=true",
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true",
      "feature.topics.enabled=false",
      "feature.demographics.enabled=false",
      "logging.level.root=WARN",
      "logging.level.org.springframework.web=WARN",
      "logging.level.org.springframework.web.servlet.mvc.method.annotation=WARN"
    })
@Import({TenantFixtures.class, IndividualCounsellingDpaGateIT.IncomingIdentityFixture.class})
class InternalSubsetChatHttpIT {

  private static final long TRAEGER = 41L;
  private static final long OTHER_TRAEGER = 42L;
  private static final long CENTRE = 410L;
  private static final long OTHER_CENTRE = 411L;
  private static final long FOREIGN_CENTRE = 420L;
  private static final String SERVER = "synthetic.oriso.test";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private TenantFixtures fixtures;
  @Autowired private ChatRepository chats;
  @Autowired private SessionRepository sessions;
  @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
  @Autowired private de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation revocation;
  @Autowired private GroupChatParticipantRepository participants;
  @Autowired private JdbcTemplate database;
  @Autowired private UserAgencyRepository userAgencies;
  @Autowired private MatrixConfig matrixConfig;
  @Autowired private InternalGroupChatRecipientScopeResolver recipientScopes;

  @Autowired
  @Qualifier("restTemplate")
  private RestTemplate transport;

  @MockitoBean private JwtDecoder jwtDecoder;

  private final SyntheticSynapse synapse = new SyntheticSynapse();
  private final Map<Long, String> recipientScopeOfTraeger = new LinkedHashMap<>();
  private final AtomicInteger tenantPolicyReads = new AtomicInteger();
  private MockRestServiceServer downstream;
  private boolean tenantServiceKnowsTheSetting = true;
  private boolean systemUserExisted41;
  private boolean systemUserExisted42;

  private Consultant creator;
  private Consultant sameCentre;
  private Consultant anotherSameCentre;
  private Consultant otherCentre;
  private Consultant otherTraeger;
  private final List<Consultant> everyone = new ArrayList<>();

  @BeforeEach
  void aTraegerWithColleaguesAndAStatefulMatrix() {
    signedIn.clear();
    matrixConfig.setEncryptionEnabled(true);
    theTraegerScopeIs("AGENCY");
    systemUserExisted41 = systemUserExists(TRAEGER);
    systemUserExisted42 = systemUserExists(OTHER_TRAEGER);
    creator = fixtures.consultant(TRAEGER, CENTRE);
    sameCentre = fixtures.consultant(TRAEGER, CENTRE);
    anotherSameCentre = fixtures.consultant(TRAEGER, CENTRE);
    otherCentre = fixtures.consultant(TRAEGER, OTHER_CENTRE);
    otherTraeger = fixtures.consultant(OTHER_TRAEGER, FOREIGN_CENTRE);
    everyone.addAll(List.of(creator, sameCentre, anotherSameCentre, otherCentre, otherTraeger));
    downstream = MockRestServiceServer.bindTo(transport).build();
    downstream
        .expect(ExpectedCount.between(0, Integer.MAX_VALUE), anything())
        .andRespond(this::externalWorld);
  }

  @AfterEach
  void removeWhatTheTestCreated() {
    Tenants.acrossAll(
        () -> {
          var owners =
              everyone.stream().map(c -> "'" + c.getId() + "'").collect(Collectors.joining(","));
          database.update(
              "DELETE FROM session_supervisor WHERE supervisor_consultant_id IN ("
                  + owners
                  + ") OR added_by_consultant_id IN ("
                  + owners
                  + ")");
          database.update(
              "DELETE FROM event_notification WHERE deduplication_key LIKE 'group-chat:%'");
          database.update(
              "DELETE FROM group_chat_participant WHERE consultant_id IN (" + owners + ")");
          database.update(
              "DELETE FROM chat_agency WHERE chat_id IN (SELECT id FROM chat WHERE"
                  + " consultant_id_owner IN ("
                  + owners
                  + "))");
          database.update(
              "DELETE FROM case_handover_request WHERE session_id IN (SELECT id FROM session WHERE consultant_id IN ("
                  + owners
                  + "))");
          database.update("DELETE FROM chat WHERE consultant_id_owner IN (" + owners + ")");
          database.update("DELETE FROM session WHERE consultant_id IN (" + owners + ")");
          if (!systemUserExisted41) {
            database.update("DELETE FROM user WHERE user_id = 'group-chat-system-41'");
          }
          if (!systemUserExisted42) {
            database.update("DELETE FROM user WHERE user_id = 'group-chat-system-42'");
          }
        });
    fixtures.removeAll();
    TenantContext.clear();
    matrixConfig.setEncryptionEnabled(true);
  }

  @Test
  void aSubsetGetsADedicatedEncryptedRoomOfExactlyTheCreatorAndTheSelection() throws Exception {
    var response = openRoom(creator, sameCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    var roomId = field(response, "matrixRoomId");
    assertThat(synapse.joined(roomId))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));
    assertThat(synapse.isEncrypted(roomId)).isTrue();
    var chat = storedChat(Long.parseLong(field(response, "chatId")));
    assertThat(chat.isMembersOnly()).isTrue();
    assertThat(chat.getConversationType()).isEqualTo(ConversationType.INTERNAL_GROUP);
    assertThat(chat.getMatrixRoomId()).isEqualTo(roomId);
    assertThat(participantsOf(chat)).containsExactlyInAnyOrder(creator.getId(), sameCentre.getId());
  }

  @Test
  void theSameSelectionOpensTheSameRoomFromEitherSideAndInAnyOrder() throws Exception {
    var first = openRoom(creator, sameCentre, anotherSameCentre);
    var again = openRoom(creator, anotherSameCentre, sameCentre, creator);
    var fromTheOtherSide = openRoom(sameCentre, creator, anotherSameCentre);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    assertThat(fromTheOtherSide.statusCode()).as(fromTheOtherSide.body()).isEqualTo(200);
    assertThat(field(again, "matrixRoomId")).isEqualTo(field(first, "matrixRoomId"));
    assertThat(field(again, "chatId")).isEqualTo(field(first, "chatId"));
    assertThat(field(fromTheOtherSide, "matrixRoomId")).isEqualTo(field(first, "matrixRoomId"));
    assertThat(synapse.roomsCreated()).isEqualTo(1);
  }

  @Test
  void aDifferentSelectionGetsItsOwnRoomAndNeverASupersetOrSubsetRoom() throws Exception {
    var pair = openRoom(creator, sameCentre);
    var trio = openRoom(creator, sameCentre, anotherSameCentre);
    var otherPair = openRoom(creator, anotherSameCentre);

    assertThat(List.of(pair.statusCode(), trio.statusCode(), otherPair.statusCode()))
        .containsOnly(201);
    assertThat(
            Set.of(
                field(pair, "matrixRoomId"),
                field(trio, "matrixRoomId"),
                field(otherPair, "matrixRoomId")))
        .hasSize(3);
    assertThat(synapse.joined(field(trio, "matrixRoomId")))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre), matrix(anotherSameCentre));
    assertThat(synapse.joined(field(pair, "matrixRoomId")))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));
  }

  @Test
  void anOrdinaryInternalChatWithTheSameMembersIsNotReusedBecauseColleaguesCanStillOpenIt()
      throws Exception {
    var ordinary =
        request(
            "POST",
            "/users/chat/v2/new",
            creator,
            "{\"topic\":\"Team talk\",\"agencyId\":410,\"startDate\":\"2999-01-01\","
                + "\"startTime\":\"12:00\",\"duration\":60,\"repetitive\":false,"
                + "\"timezone\":\"UTC\",\"modality\":\"TEXT\",\"consultantIds\":[\""
                + sameCentre.getId()
                + "\"]}");
    assertThat(ordinary.statusCode()).as(ordinary.body()).isEqualTo(201);

    var subset = openRoom(creator, sameCentre);

    assertThat(subset.statusCode()).as(subset.body()).isEqualTo(201);
    assertThat(field(subset, "matrixRoomId")).isNotEqualTo(field(ordinary, "matrixRoomId"));
    assertThat(synapse.roomsCreated()).isEqualTo(2);
  }

  @Test
  void aRoomThatGainedAnotherMemberInMatrixIsNeverReused() throws Exception {
    var first = openRoom(creator, sameCentre);
    synapse.joinDirectly(field(first, "matrixRoomId"), matrix(anotherSameCentre));

    var again = openRoom(creator, sameCentre);

    assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
    assertThat(field(again, "matrixRoomId")).isNotEqualTo(field(first, "matrixRoomId"));
    assertThat(synapse.joined(field(again, "matrixRoomId")))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));
  }

  @Test
  void aColleagueOutsideTheScopeRefusesTheWholeRequestAndCreatesNothing() throws Exception {
    var response = openRoom(creator, sameCentre, otherCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    assertThat(reason(response)).isEqualTo("INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE");
    assertThat(response.body()).contains("\"reason\":\"INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE\"");
    assertNothingWasCreated();
  }

  @Test
  void aSettingTheTenantServiceDoesNotKnowYetMeansCounsellingCentreOnly() throws Exception {
    tenantServiceKnowsTheSetting = false;
    theTraegerScopeIs("AGENCY");

    var response = openRoom(creator, otherCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    assertThat(reason(response)).isEqualTo("INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE");
    assertNothingWasCreated();
  }

  @Test
  void theTraegerScopeWidensWhoCanBePicked() throws Exception {
    theTraegerScopeIs("TENANT");
    var sameTraeger = openRoom(creator, otherCentre);
    var foreign = openRoom(creator, otherTraeger);

    assertThat(sameTraeger.statusCode()).as(sameTraeger.body()).isEqualTo(201);
    assertThatTheColleagueFindsAndOpens(otherCentre, sameTraeger);
    assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(403);
    assertThat(reason(foreign)).isEqualTo("INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE");

    theTraegerScopeIs("PLATFORM");
    var acrossTraeger = openRoom(creator, otherTraeger);
    assertThat(acrossTraeger.statusCode()).as(acrossTraeger.body()).isEqualTo(201);
    assertThat(synapse.joined(field(acrossTraeger, "matrixRoomId")))
        .containsExactlyInAnyOrder(matrix(creator), matrix(otherTraeger));
    assertThatTheColleagueFindsAndOpens(otherTraeger, acrossTraeger);
  }

  private void assertThatTheColleagueFindsAndOpens(Consultant colleague, HttpResponse<String> room)
      throws Exception {
    var list = sessionListOf(colleague);
    assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
    assertThat(list.body()).as("session list").contains(field(room, "matrixRoomId"));
    var opened = request("GET", "/users/chat/" + field(room, "chatId"), colleague, "");
    assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
  }

  private HttpResponse<String> sessionListOf(Consultant consultant) throws Exception {
    return request(
        "GET",
        "/users/sessions/consultants?status=2&offset=0&count=100&filter=all",
        consultant,
        "");
  }

  @Test
  void aColleagueTheCallerMayNotPickLooksTheSameWhetherOrNotThatConsultantExists()
      throws Exception {
    var unknown = openWithIds(creator, sameCentre.getId(), "no-such-colleague");
    var existsElsewhere = openRoom(creator, sameCentre, otherTraeger);

    assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(403);
    assertThat(reason(unknown)).isEqualTo("INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE");
    assertThat(existsElsewhere.statusCode()).isEqualTo(unknown.statusCode());
    assertThat(existsElsewhere.body()).isEqualTo(unknown.body());
    assertNothingWasCreated();
  }

  @Test
  void aColleagueOfTheCentreWithoutAChatIdentityIsRefusedNotSkipped() throws Exception {
    Tenants.acrossAll(
        () ->
            database.update(
                "UPDATE consultant SET matrix_user_id = NULL WHERE consultant_id = ?",
                anotherSameCentre.getId()));

    var response = openRoom(creator, sameCentre, anotherSameCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(reason(response)).isEqualTo("INTERNAL_CHAT_RECIPIENT_UNKNOWN");
    assertNothingWasCreated();
  }

  @Test
  void aFailedInviteWhoseRoomCannotBeRemovedIsNotReportedAsCleanedUp() throws Exception {
    synapse.refuseInvitationOf(matrix(anotherSameCentre));
    synapse.failPurge = true;

    var response = openRoom(creator, sameCentre, anotherSameCentre);

    synapse.failPurge = false;
    assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
    assertThat(reason(response)).isNotEqualTo("INTERNAL_CHAT_MEMBERSHIP_FAILED");
    // The records are gone, so nobody can find the chat; the room is the operator's to remove.
    assertThat(allChatsOf(everyone)).isEmpty();
    assertThat(allSessionsOf(everyone)).isEmpty();
    assertThat(synapse.liveRooms()).hasSize(1);
  }

  @Test
  void aRoomWithAMemberWeCannotMapToAColleagueIsNeverReused() throws Exception {
    var first = openRoom(creator, sameCentre);
    synapse.joinDirectly(field(first, "matrixRoomId"), "@stranger:elsewhere.example");

    var again = openRoom(creator, sameCentre);

    assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
    assertThat(field(again, "matrixRoomId")).isNotEqualTo(field(first, "matrixRoomId"));
  }

  @Test
  void aRoomWhoseMembersCannotBeReadIsNeitherReusedNorReplacedByASecondRoom() throws Exception {
    var first = openRoom(creator, sameCentre);
    synapse.failMemberReads = true;

    var again = openRoom(creator, sameCentre);

    synapse.failMemberReads = false;
    assertThat(again.statusCode()).as(again.body()).isEqualTo(503);
    assertThat(reason(again)).isEqualTo("INTERNAL_CHAT_BUSY");
    assertThat(synapse.roomsCreated()).as("no second room").isEqualTo(1);
    // Once Matrix answers again, the same room is found.
    var retried = openRoom(creator, sameCentre);
    assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
    assertThat(field(retried, "matrixRoomId")).isEqualTo(field(first, "matrixRoomId"));
  }

  @Test
  void anUnreadableOlderDuplicateDoesNotHideALaterRoomWithTheExactMembers() throws Exception {
    var older = openRoom(creator, sameCentre);
    synapse.joinDirectly(field(older, "matrixRoomId"), matrix(anotherSameCentre));
    var newer = openRoom(creator, sameCentre);
    assertThat(newer.statusCode()).as(newer.body()).isEqualTo(201);
    synapse.unreadableRooms.add(field(older, "matrixRoomId"));

    var again = openRoom(creator, sameCentre);

    assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    assertThat(field(again, "matrixRoomId")).isEqualTo(field(newer, "matrixRoomId"));
    assertThat(synapse.roomsCreated()).as("no third room").isEqualTo(2);
  }

  @Test
  void whenNoCandidateMatchesAndOneCannotBeReadNoSecondRoomIsCreated() throws Exception {
    var older = openRoom(creator, sameCentre);
    synapse.joinDirectly(field(older, "matrixRoomId"), matrix(anotherSameCentre));
    var newer = openRoom(creator, sameCentre);
    synapse.joinDirectly(field(newer, "matrixRoomId"), matrix(otherCentre));
    synapse.unreadableRooms.add(field(older, "matrixRoomId"));

    var again = openRoom(creator, sameCentre);

    assertThat(again.statusCode()).as(again.body()).isEqualTo(503);
    assertThat(reason(again)).isEqualTo("INTERNAL_CHAT_BUSY");
    assertThat(synapse.roomsCreated()).as("no third room").isEqualTo(2);
  }

  @Test
  void theTechnicalAdminInTheRoomDoesNotPreventItsReuse() throws Exception {
    var first = openRoom(creator, sameCentre);
    synapse.joinDirectly(
        field(first, "matrixRoomId"),
        "@" + matrixConfig.getAdminUsername() + ":" + matrixConfig.getServerName());

    var again = openRoom(creator, sameCentre);

    assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    assertThat(field(again, "matrixRoomId")).isEqualTo(field(first, "matrixRoomId"));
  }

  @Test
  void twoSimultaneousRequestsForTheSameSelectionMakeOneRoom() throws Exception {
    synapse.createDelayMillis = 400;
    signIn(creator);
    signIn(sameCentre);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var go = new java.util.concurrent.CountDownLatch(1);
      var one =
          pool.submit(
              () -> {
                go.await();
                return openRoom(creator, sameCentre);
              });
      var other =
          pool.submit(
              () -> {
                go.await();
                return openRoom(sameCentre, creator);
              });
      go.countDown();
      var statuses = List.of(one.get().statusCode(), other.get().statusCode());

      assertThat(statuses).containsExactlyInAnyOrder(200, 201);
      assertThat(field(one.get(), "matrixRoomId")).isEqualTo(field(other.get(), "matrixRoomId"));
      assertThat(synapse.roomsCreated()).isEqualTo(1);
    } finally {
      synapse.createDelayMillis = 0;
      pool.shutdownNow();
    }
  }

  @Test
  void withoutANamedCentreTheRoomIsCreatedForACentreTheWholeSelectionBelongsTo() throws Exception {
    var inTwoCentres = fixtures.consultant(TRAEGER, CENTRE, OTHER_CENTRE);
    everyone.add(inTwoCentres);

    var response = openWithoutCentre(inTwoCentres, otherCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(centresOf(response)).containsExactly(OTHER_CENTRE);
  }

  @Test
  void withoutANamedCentreASelectionSpreadOverCentresTheCallerHasNoneInCommonWithIsRefused()
      throws Exception {
    var inTwoCentres = fixtures.consultant(TRAEGER, CENTRE, OTHER_CENTRE);
    everyone.add(inTwoCentres);

    var response = openWithoutCentre(inTwoCentres, sameCentre, otherCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    assertThat(reason(response)).isEqualTo("INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE");
    assertNothingWasCreated();
  }

  @Test
  void aRequestWithoutAnyColleagueBesidesTheCallerIsRefused() throws Exception {
    var onlySelf = openRoom(creator, creator);

    assertThat(onlySelf.statusCode()).as(onlySelf.body()).isEqualTo(400);
    assertThat(reason(onlySelf)).isEqualTo("INTERNAL_CHAT_RECIPIENTS_REQUIRED");
    assertNothingWasCreated();
  }

  @Test
  void aBlankOrMissingEntryInTheSelectionRefusesTheRequestInsteadOfBeingDropped() throws Exception {
    for (var invalidEntry : List.of("\"\"", "\"   \"", "null")) {
      var response =
          request(
              "POST",
              "/users/chat/internal-subset",
              creator,
              "{\"consultantIds\":[\""
                  + sameCentre.getId()
                  + "\","
                  + invalidEntry
                  + "],\"agencyId\":"
                  + CENTRE
                  + "}");

      assertThat(response.statusCode()).as(invalidEntry + ": " + response.body()).isEqualTo(400);
      assertThat(reason(response)).as(invalidEntry).isEqualTo("INTERNAL_CHAT_RECIPIENT_UNKNOWN");
      assertNothingWasCreated();
    }
  }

  @Test
  void aFailedInviteLeavesNoRoomNoChatAndNoSessionBehind() throws Exception {
    synapse.refuseInvitationOf(matrix(anotherSameCentre));

    var response = openRoom(creator, sameCentre, anotherSameCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(502);
    assertThat(reason(response)).isEqualTo("INTERNAL_CHAT_MEMBERSHIP_FAILED");
    assertThat(synapse.liveRooms()).isEmpty();
    assertThat(allChatsOf(everyone)).isEmpty();
    assertThat(allSessionsOf(everyone)).isEmpty();
  }

  @Test
  void withoutEncryptionNoPrivateRoomIsCreated() throws Exception {
    matrixConfig.setEncryptionEnabled(false);

    var response = openRoom(creator, sameCentre);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
    assertThat(reason(response)).isEqualTo("INTERNAL_CHAT_ENCRYPTION_REQUIRED");
    assertNothingWasCreated();
  }

  @Test
  void aColleagueOfTheCentreWhoIsNotInTheRoomCannotSeeOpenOrJoinIt() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");

    for (var outsider : List.of(anotherSameCentre, otherCentre)) {
      assertThat(request("GET", "/users/chat/" + chatId, outsider, "").statusCode())
          .as("open by id")
          .isEqualTo(403);
      assertThat(request("PUT", "/users/chat/" + chatId + "/join", outsider, "").statusCode())
          .as("join")
          .isEqualTo(403);
      assertThat(request("GET", "/users/chat/" + chatId + "/members", outsider, "").statusCode())
          .as("members")
          .isEqualTo(403);
      var list = sessionListOf(outsider);
      assertThat(list.statusCode()).as(list.body()).isIn(200, 204);
      assertThat(list.body()).as("session list").doesNotContain(roomId);
    }
    assertThat(synapse.joined(roomId))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));

    var memberList = sessionListOf(sameCentre);
    assertThat(memberList.statusCode()).as(memberList.body()).isEqualTo(200);
    assertThat(memberList.body()).contains(roomId);
    var opened = request("GET", "/users/chat/" + chatId, sameCentre, "");
    assertThat(opened.statusCode()).as(opened.body() + opened.headers()).isEqualTo(200);
  }

  @Test
  void aCreatorWhoTransferredOwnershipAndLeftNoLongerSeesTheSessionOfTheRoom() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId =
        Tenants.acrossAll(
            () ->
                database.queryForObject(
                    "SELECT chat_id FROM group_chat_participant WHERE series_id = ? LIMIT 1",
                    Long.class,
                    Long.parseLong(chatId)));
    assertThat(sessionListOf(creator).body()).as("before leaving").contains(roomId);

    var transfer =
        request(
            "POST",
            "/users/chat-series/" + chatId + "/transfer-ownership",
            creator,
            "{\"consultantId\":\"" + sameCentre.getId() + "\"}");
    assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(204);
    var left = request("PUT", "/users/chat/" + chatId + "/leave", creator, "");
    assertThat(left.statusCode()).as(left.body()).isIn(200, 204);

    var list = sessionListOf(creator);
    assertThat(list.statusCode()).as(list.body()).isIn(200, 204);
    assertThat(list.body()).as("session list of the former member").doesNotContain(roomId);
    var bySessionId = request("GET", "/users/sessions/room/" + sessionId, creator, "");
    // A session the caller may not see is answered with an empty 204.
    assertThat(bySessionId.statusCode()).as("session by id").isEqualTo(204);
    assertThat(bySessionId.body()).as("session by id").isEmpty();
    assertThat(request("GET", "/users/chat/" + chatId, creator, "").statusCode())
        .as("chat by id")
        .isEqualTo(403);
    var memberList = sessionListOf(sameCentre);
    assertThat(memberList.body()).as("the new owner keeps the session").contains(roomId);
  }

  @Test
  void anAdviceSeekerOfTheCentreCannotOpenJoinOrListMembersOfAMembersOnlyRoom() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var adviceSeeker = fixtures.adviceSeeker(TRAEGER);
    Tenants.in(TRAEGER, () -> userAgencies.save(new UserAgency(adviceSeeker, CENTRE)));
    try {
      for (var call :
          List.of(
              List.of("GET", "/users/chat/" + chatId),
              List.of("PUT", "/users/chat/" + chatId + "/join"),
              List.of("GET", "/users/chat/" + chatId + "/members"))) {
        var response = requestAsAdviceSeeker(call.get(0), call.get(1), adviceSeeker);
        assertThat(response.statusCode()).as(call + " " + response.body()).isEqualTo(403);
      }
      assertThat(synapse.joined(roomId))
          .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));
    } finally {
      Tenants.in(
          TRAEGER,
          () ->
              database.update(
                  "DELETE FROM user_agency WHERE user_id = ?", adviceSeeker.getUserId()));
    }
  }

  @Test
  void anAdviceSeekerCannotAttachAMembersOnlyRoomToTheirChatsByItsRoomId() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = Long.parseLong(field(created, "chatId"));
    var roomId = field(created, "matrixRoomId");
    var adviceSeeker = fixtures.adviceSeeker(TRAEGER);

    var assigned = requestAsAdviceSeeker("PUT", "/users/chat/" + roomId + "/assign", adviceSeeker);

    assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(403);
    assertThat(
            Tenants.acrossAll(
                () ->
                    database.queryForObject(
                        "SELECT COUNT(*) FROM user_chat WHERE chat_id = ?", Integer.class, chatId)))
        .as("no relation to the room")
        .isZero();
  }

  @Test
  void anOrdinaryInternalChatStaysOpenToTheColleaguesOfItsCentre() throws Exception {
    var ordinary =
        request(
            "POST",
            "/users/chat/v2/new",
            creator,
            "{\"topic\":\"Team talk\",\"agencyId\":410,\"startDate\":\"2999-01-01\","
                + "\"startTime\":\"12:00\",\"duration\":60,\"repetitive\":false,"
                + "\"timezone\":\"UTC\",\"modality\":\"TEXT\",\"consultantIds\":[\""
                + sameCentre.getId()
                + "\"]}");
    assertThat(ordinary.statusCode()).as(ordinary.body()).isEqualTo(201);
    var chatId =
        Tenants.acrossAll(
                () -> chats.findByMatrixRoomId(field(ordinary, "matrixRoomId")).orElseThrow())
            .getId();

    assertThat(request("GET", "/users/chat/" + chatId, anotherSameCentre, "").statusCode())
        .isEqualTo(200);
    assertThat(request("GET", "/users/chat/" + chatId, otherCentre, "").statusCode())
        .isEqualTo(403);
  }

  @Test
  void aMemberWhoLeftIsRefusedByEveryEndpoint() throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    var left = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");
    assertThat(left.statusCode()).as(left.body()).isIn(200, 204);

    assertEveryEndpointRefuses(sameCentre, chatId, roomId, sessionId);
    assertTheRoomStillWorksFor(creator, chatId, roomId, sessionId);
  }

  @Test
  void aLeaveThatMatrixDoesNotCarryOutKeepsTheMemberAndCanBeRepeated() throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    synapse.roomsRefusingLeave.add(roomId);

    var refused = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");

    // Not a half revoked state: the database still says participant, as Matrix does.
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(502);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId)))).contains(sameCentre.getId());
    assertThat(synapse.joined(roomId)).contains(matrix(sameCentre));
    assertTheRoomStillWorksFor(sameCentre, chatId, roomId, sessionId);

    synapse.roomsRefusingLeave.clear();
    var repeated = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");

    assertThat(repeated.statusCode()).as(repeated.body()).isIn(200, 204);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .doesNotContain(sameCentre.getId());
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(sameCentre));
  }

  @Test
  void aRemovalThatMatrixDoesNotCarryOutKeepsTheParticipantAndCanBeRepeated() throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var uri = "/users/chat-series/" + chatId + "/participants/" + sameCentre.getId();
    synapse.roomsRefusingLeave.add(roomId);

    var refused = request("DELETE", uri, creator, "");

    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(502);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId)))).contains(sameCentre.getId());
    assertThat(synapse.joined(roomId)).contains(matrix(sameCentre));

    synapse.roomsRefusingLeave.clear();
    var repeated = request("DELETE", uri, creator, "");

    assertThat(repeated.statusCode()).as(repeated.body()).isIn(200, 204);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .doesNotContain(sameCentre.getId());
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(sameCentre));
  }

  @Test
  void aSupervisionSideRoomThatCannotBeLeftKeepsTheMemberAndTheSupervisionUntilRepeated()
      throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var sessionId = sessionOf(chatId);
    var sideRoom = "!side-of-" + sessionId + ":oriso";
    Tenants.acrossAll(
        () ->
            database.update(
                "INSERT INTO session_supervisor (session_id, supervisor_consultant_id,"
                    + " added_by_consultant_id, added_date, is_active, matrix_room_id)"
                    + " VALUES (?, ?, ?, NOW(), 1, ?)",
                sessionId,
                sameCentre.getId(),
                creator.getId(),
                sideRoom));
    synapse.roomsRefusingLeave.add(sideRoom);

    var refused = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");

    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(502);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId)))).contains(sameCentre.getId());
    assertThat(activeSupervisions(sessionId)).isEqualTo(1);

    synapse.roomsRefusingLeave.clear();
    var repeated = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");

    assertThat(repeated.statusCode()).as(repeated.body()).isIn(200, 204);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .doesNotContain(sameCentre.getId());
    assertThat(activeSupervisions(sessionId)).isZero();
  }

  @Test
  void aMainRoomThatCannotBeLeftAfterTheSideRoomWasLeftRollsTheSupervisionBack() throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    Tenants.acrossAll(
        () ->
            database.update(
                "INSERT INTO session_supervisor (session_id, supervisor_consultant_id,"
                    + " added_by_consultant_id, added_date, is_active, matrix_room_id)"
                    + " VALUES (?, ?, ?, NOW(), 1, ?)",
                sessionId,
                sameCentre.getId(),
                creator.getId(),
                "!side-of-" + sessionId + ":oriso"));
    synapse.roomsRefusingLeave.add(roomId);

    var refused = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");

    // The side room is left first, the main room last: nobody is removed in the database, the
    // person keeps the room that matters, and only the harmless side room is gone. A repeat ends
    // it.
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(502);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId)))).contains(sameCentre.getId());
    assertThat(activeSupervisions(sessionId)).isEqualTo(1);
    assertThat(synapse.joined(roomId)).contains(matrix(sameCentre));

    synapse.roomsRefusingLeave.clear();
    var repeated = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");
    assertThat(repeated.statusCode()).as(repeated.body()).isIn(200, 204);
    assertThat(activeSupervisions(sessionId)).isZero();
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(sameCentre));
  }

  @Test
  void aSoftDeletedCoModeratorIsTakenOutOfTheMatrixRoomWhenTheSelectionDropsThem()
      throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    Tenants.acrossAll(
        () -> {
          // Settings and selection can only be edited while the chat is not running.
          database.update("UPDATE chat SET is_active = 0 WHERE id = ?", Long.parseLong(chatId));
          return database.update(
              "UPDATE consultant SET delete_date = NOW() WHERE consultant_id = ?",
              sameCentre.getId());
        });

    var updated =
        request(
            "PUT",
            "/users/chat/" + chatId + "/update",
            creator,
            "{\"topic\":\"Same\",\"agencyId\":"
                + CENTRE
                + ",\"startDate\":\"2999-01-01\",\"startTime\":\"12:00\",\"duration\":60,"
                + "\"repetitive\":false,\"timezone\":\"UTC\",\"modality\":\"TEXT\","
                + "\"consultantIds\":[\""
                + anotherSameCentre.getId()
                + "\"]}");

    assertThat(updated.statusCode()).as(updated.body()).isIn(200, 204);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .doesNotContain(sameCentre.getId());
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(sameCentre));
  }

  private Integer statusOf(Long sessionId) {
    return Tenants.acrossAll(
        () ->
            database.queryForObject(
                "SELECT status FROM session WHERE id = ?", Integer.class, sessionId));
  }

  private int activeSupervisions(Long sessionId) {
    return Tenants.acrossAll(
        () ->
            database.queryForObject(
                "SELECT COUNT(*) FROM session_supervisor WHERE session_id = ? AND is_active = 1",
                Integer.class,
                sessionId));
  }

  @Test
  void aColleagueFromAnotherTraegerLeavingDoesNotDeleteTheRoomOthersAreStillIn() throws Exception {
    theTraegerScopeIs("PLATFORM");
    var created = openRoom(creator, otherTraeger, sameCentre);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");

    var left = request("PUT", "/users/chat/" + chatId + "/leave", otherTraeger, "");

    assertThat(left.statusCode()).as(left.body()).isIn(200, 204);
    // The others belong to another Traeger than the one who left, and are still in the room.
    assertThat(Tenants.acrossAll(() -> chats.findById(Long.parseLong(chatId)))).isPresent();
    assertThat(synapse.joined(roomId)).contains(matrix(creator), matrix(sameCentre));
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(otherTraeger));
    assertTheRoomStillWorksFor(creator, chatId, roomId, sessionOf(chatId));
  }

  @Test
  void aParticipantFromAnotherTraegerOpensTheSessionOfTheRoomAndAnOutsiderOfThatTraegerDoesNot()
      throws Exception {
    theTraegerScopeIs("PLATFORM");
    var outsiderOfThatTraeger = fixtures.consultant(OTHER_TRAEGER, FOREIGN_CENTRE);
    everyone.add(outsiderOfThatTraeger);
    var created = openRoom(creator, otherTraeger, sameCentre);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(field(created, "chatId"));

    var participant = request("GET", "/users/sessions/room/" + sessionId, otherTraeger, "");
    var outsider = request("GET", "/users/sessions/room/" + sessionId, outsiderOfThatTraeger, "");

    // The session is assigned to the creator of another Traeger: participation alone admits.
    assertThat(participant.statusCode()).as(participant.body()).isEqualTo(200);
    assertThat(participant.body()).contains(roomId);
    assertThat(outsider.statusCode()).as(outsider.body()).isEqualTo(204);
    assertThat(outsider.body()).isEmpty();
  }

  @Test
  void aParticipantFromAnotherTraegerUsesTheMatrixSessionEndpointsAndAnOutsiderStillGetsNotFound()
      throws Exception {
    theTraegerScopeIs("PLATFORM");
    var outsiderOfThatTraeger = fixtures.consultant(OTHER_TRAEGER, FOREIGN_CENTRE);
    everyone.add(outsiderOfThatTraeger);
    var created = openRoom(creator, otherTraeger, sameCentre);
    var sessionId = sessionOf(field(created, "chatId"));

    var participantSync =
        request("GET", "/matrix/sessions/" + sessionId + "/sync", otherTraeger, "");
    var participantUnregister =
        request("DELETE", "/matrix/sync/register/" + sessionId, otherTraeger, "");
    var outsiderSync =
        request("GET", "/matrix/sessions/" + sessionId + "/sync", outsiderOfThatTraeger, "");
    var outsiderUnregister =
        request("DELETE", "/matrix/sync/register/" + sessionId, outsiderOfThatTraeger, "");

    assertThat(participantSync.statusCode()).as(participantSync.body()).isEqualTo(200);
    assertThat(participantUnregister.statusCode()).as(participantUnregister.body()).isEqualTo(200);
    assertThat(outsiderSync.statusCode()).as(outsiderSync.body()).isEqualTo(404);
    assertThat(outsiderUnregister.statusCode()).as(outsiderUnregister.body()).isEqualTo(404);
  }

  @Test
  void theCreatorSeesTheNameOfAColleagueAdmittedFromAnotherTraeger() throws Exception {
    theTraegerScopeIs("PLATFORM");
    assertThat(openRoom(creator, otherTraeger, sameCentre).statusCode()).isEqualTo(201);

    var opened = sessionListOf(creator);

    assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
    var shown = new java.util.HashMap<String, String>();
    for (var participant :
        java.util.Objects.requireNonNull(
            JSON.readTree(opened.body()).findValue("participants"), opened.body())) {
      shown.put(participant.get("consultantId").asText(), participant.get("displayName").asText());
    }
    assertThat(shown.get(otherTraeger.getId()))
        .isEqualTo("Synthetic " + otherTraeger.getLastName());
    assertThat(shown.get(sameCentre.getId())).isEqualTo("Synthetic " + sameCentre.getLastName());
  }

  @Test
  void everyParticipantSeesEveryOtherParticipantInTheMemberListWhateverTheirTraeger()
      throws Exception {
    theTraegerScopeIs("PLATFORM");
    var created = openRoom(creator, otherTraeger, sameCentre);
    var chatId = field(created, "chatId");
    var everyoneInTheRoom =
        List.of(creator.getId(), otherTraeger.getId(), sameCentre.getId()).toArray(String[]::new);

    for (var viewer : List.of(creator, otherTraeger, sameCentre)) {
      var members = request("GET", "/users/chat/" + chatId + "/members", viewer, "");

      assertThat(members.statusCode()).as(members.body()).isEqualTo(200);
      var listed = new java.util.ArrayList<String>();
      JSON.readTree(members.body())
          .get("members")
          .forEach(m -> listed.add(m.get("userId").asText()));
      assertThat(listed).containsExactlyInAnyOrder(everyoneInTheRoom);
    }
  }

  @Test
  void aParticipantFromAnotherTraegerReadsTheCaseAndSeesTheNameOfTheOwner() throws Exception {
    theTraegerScopeIs("PLATFORM");
    var outsiderOfThatTraeger = fixtures.consultant(OTHER_TRAEGER, FOREIGN_CENTRE);
    everyone.add(outsiderOfThatTraeger);
    var created = openRoom(creator, otherTraeger, sameCentre);
    var chatId = field(created, "chatId");
    var sessionId = sessionOf(chatId);
    Tenants.acrossAll(
        () ->
            database.update(
                "UPDATE consultant SET display_name = 'Shown Name' WHERE consultant_id = ?",
                creator.getId()));

    var theCase = request("GET", "/users/consultants/sessions/" + sessionId, otherTraeger, "");
    var theCaseOfAnOutsider =
        request("GET", "/users/consultants/sessions/" + sessionId, outsiderOfThatTraeger, "");
    var theChat = request("GET", "/users/chat/room/" + chatId, otherTraeger, "");

    assertThat(theCase.statusCode()).as(theCase.body()).isEqualTo(200);
    assertThat(theCaseOfAnOutsider.statusCode()).isEqualTo(404);
    assertThat(theChat.statusCode()).as(theChat.body()).isEqualTo(200);
    assertThat(theChat.body()).contains("\"displayName\":\"Shown Name\"");
  }

  @Test
  void noParticipantCanReassignTheCaseOfAMembersOnlyChatAndNobodyIsTakenOutOfTheRoom()
      throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    var members = synapse.joined(roomId);

    var reassigned =
        request(
            "PUT",
            "/users/sessions/" + sessionId + "/consultant/" + sameCentre.getId(),
            creator,
            "");

    assertThat(reassigned.statusCode()).as(reassigned.body()).isEqualTo(409);
    assertThat(reason(reassigned)).isEqualTo("MEMBERS_ONLY_CHAT_STOP_REQUIRED");
    assertThat(synapse.joined(roomId)).as("room membership unchanged").isEqualTo(members);
    assertThat(
            Tenants.acrossAll(
                () ->
                    database.queryForObject(
                        "SELECT consultant_id FROM session WHERE id = ?", String.class, sessionId)))
        .as("the case stays with the creator")
        .isEqualTo(creator.getId());
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .containsExactlyInAnyOrder(creator.getId(), sameCentre.getId(), anotherSameCentre.getId());
  }

  @Test
  void aDeletedConsultantLeavesThePrivateRoomAndAFailedLeaveRollsTheDeletionBack()
      throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var transaction = new org.springframework.transaction.support.TransactionTemplate(txManager);
    synapse.roomsRefusingLeave.add(roomId);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                Tenants.acrossAll(
                    () ->
                        transaction.execute(
                            status -> {
                              revocation.revokeAllAccessOf(sameCentre);
                              return null;
                            })))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class);

    // Nothing was revoked: the participation is back and so is the room membership.
    assertThat(participantsOf(storedChat(Long.parseLong(chatId)))).contains(sameCentre.getId());
    assertThat(synapse.joined(roomId)).contains(matrix(sameCentre));

    synapse.roomsRefusingLeave.clear();
    Tenants.acrossAll(
        () ->
            transaction.execute(
                status -> {
                  revocation.revokeAllAccessOf(sameCentre);
                  return null;
                }));

    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .containsExactlyInAnyOrder(creator.getId(), anotherSameCentre.getId());
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(sameCentre));
  }

  @Test
  void aDeletedOwnerPassesTheChatToARemainingParticipantInsteadOfLeavingItOwnerless()
      throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = Long.parseLong(field(created, "chatId"));
    var roomId = field(created, "matrixRoomId");

    revokeInTransaction(creator);

    var chat = storedChat(chatId);
    assertThat(participantsOf(chat))
        .containsExactlyInAnyOrder(sameCentre.getId(), anotherSameCentre.getId());
    var owners =
        Tenants.acrossAll(
            () ->
                participants.findBySeriesId(chatId).stream()
                    .filter(p -> p.getRole() == GroupChatParticipant.ParticipantRole.OWNER)
                    .map(GroupChatParticipant::getConsultantId)
                    .toList());
    assertThat(owners).hasSize(1);
    assertThat(Tenants.acrossAll(() -> chats.findById(chatId).orElseThrow().getChatOwner().getId()))
        .isEqualTo(owners.get(0))
        .isIn(sameCentre.getId(), anotherSameCentre.getId());
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(creator));
  }

  @Test
  void aDeletedOwnerWhoIsTheLastMemberRetiresTheChatAndPurgesItsRoom() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = Long.parseLong(field(created, "chatId"));
    var roomId = field(created, "matrixRoomId");
    revokeInTransaction(sameCentre);
    // The creator, still the owner, is the last member now.
    synapse.failPurge = true;
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> revokeInTransaction(creator))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class);
    synapse.failPurge = false;
    assertThat(Tenants.acrossAll(() -> chats.findById(chatId))).as("rolled back").isPresent();
    assertThat(participantsOf(storedChat(chatId))).containsExactly(creator.getId());

    revokeInTransaction(creator);

    assertThat(Tenants.acrossAll(() -> chats.findById(chatId))).isEmpty();
    assertThat(Tenants.acrossAll(() -> participants.findBySeriesId(chatId))).isEmpty();
    assertThat(synapse.hasRoom(roomId)).as("the room is purged").isFalse();
  }

  @Test
  void repeatingADeletionThatFailedAfterTheRoomWasLeftTreatsAnAlreadyLeftRoomAsDone()
      throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = Long.parseLong(field(created, "chatId"));
    var roomId = field(created, "matrixRoomId");
    // A first attempt left the room in Matrix; its transaction rolled back afterwards.
    synapse.leaveDirectly(roomId, matrix(sameCentre));

    revokeInTransaction(sameCentre);

    assertThat(participantsOf(storedChat(chatId)))
        .containsExactlyInAnyOrder(creator.getId(), anotherSameCentre.getId());
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(sameCentre));
  }

  @Test
  void repeatingADeletionThatFailedAfterTheRoomWasPurgedTreatsAGoneRoomAsDone() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = Long.parseLong(field(created, "chatId"));
    var roomId = field(created, "matrixRoomId");
    revokeInTransaction(sameCentre);
    // A first attempt purged the room of the last member; its transaction rolled back afterwards.
    synapse.purgeDirectly(roomId);

    revokeInTransaction(creator);

    assertThat(Tenants.acrossAll(() -> chats.findById(chatId))).isEmpty();
  }

  @Test
  void theCaseOfADeletedAssigneeGoesToARemainingActiveParticipantAndIsNotDeleted()
      throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var sessionId = sessionOf(chatId);
    Tenants.acrossAll(
        () ->
            database.update(
                "UPDATE consultant SET delete_date = NOW() WHERE consultant_id = ?",
                sameCentre.getId()));

    // The forced deletion of the creator: the case is theirs, the chat and its room stay.
    var kept =
        Tenants.acrossAll(
            () -> {
              var session = sessions.findById(sessionId).orElseThrow();
              return revocation.keepCaseOnDeletionOf(session, creator);
            });

    assertThat(kept).isTrue();
    assertThat(
            Tenants.acrossAll(
                () ->
                    database.queryForObject(
                        "SELECT consultant_id FROM session WHERE id = ?", String.class, sessionId)))
        .as("the soft-deleted participant is skipped")
        .isEqualTo(anotherSameCentre.getId());
    assertThat(Tenants.acrossAll(() -> chats.findById(Long.parseLong(chatId)))).isPresent();
  }

  @Test
  void theSessionOfAMembersOnlyChatCanNeitherBeArchivedNorReactivatedWhileTheChatStays()
      throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var sessionId = sessionOf(chatId);
    var before = statusOf(sessionId);

    for (var action : List.of("archive", "dearchive")) {
      var participant = request("PUT", "/users/sessions/" + sessionId + "/" + action, creator, "");
      assertThat(participant.statusCode()).as(action + participant.body()).isEqualTo(409);
      assertThat(reason(participant)).as(action).isEqualTo("MEMBERS_ONLY_CHAT_STOP_REQUIRED");
      var outsider =
          request("PUT", "/users/sessions/" + sessionId + "/" + action, anotherSameCentre, "");
      assertThat(outsider.statusCode()).as(action + outsider.body()).isEqualTo(403);
    }
    assertThat(statusOf(sessionId)).as("the session status is untouched").isEqualTo(before);
  }

  @Test
  void aLeftOverAssignmentAndSupervisionGrantAFormerMemberNothing() throws Exception {
    var created = openRoom(creator, sameCentre, anotherSameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    var left = request("PUT", "/users/chat/" + chatId + "/leave", sameCentre, "");
    assertThat(left.statusCode()).as(left.body()).isIn(200, 204);
    // What the data of a release without revocation looks like: the session assigned to the
    // former member and an active supervision row.
    Tenants.acrossAll(
        () -> {
          database.update(
              "UPDATE session SET consultant_id = ? WHERE id = ?", sameCentre.getId(), sessionId);
          database.update(
              "INSERT INTO session_supervisor (session_id, supervisor_consultant_id,"
                  + " added_by_consultant_id, added_date, is_active, matrix_room_id)"
                  + " VALUES (?, ?, ?, NOW(), 1, ?)",
              sessionId,
              sameCentre.getId(),
              creator.getId(),
              "!side-of-" + sessionId + ":oriso");
          return null;
        });

    assertEveryEndpointRefuses(sameCentre, chatId, roomId, sessionId);
  }

  @Test
  void privateChatRefusesHandoverMutationsWithoutChangingItsAudienceOrOwner() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    var before = Tenants.acrossAll(() -> sessions.findById(sessionId).orElseThrow());
    var response =
        request(
            "POST",
            "/users/sessions/" + sessionId + "/case-handover",
            anotherSameCentre,
            "{\"reasonCode\":\"UNPLANNED_ABSENCE\",\"explanation\":\"Synthetic regression\"}");
    // Observe protocol and storage effects before interpreting an error response.
    assertThat(synapse.joined(roomId))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .containsExactlyInAnyOrder(creator.getId(), sameCentre.getId());
    var after = Tenants.acrossAll(() -> sessions.findById(sessionId).orElseThrow());
    assertThat(after.getConsultant().getId()).isEqualTo(before.getConsultant().getId());
    assertThat(after.getOwnershipRevision()).isEqualTo(before.getOwnershipRevision());
    assertThat(
            database.queryForObject(
                "SELECT COUNT(*) FROM case_handover_request WHERE session_id=?",
                Integer.class,
                sessionId))
        .isZero();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    var preference =
        request(
            "PUT",
            "/users/sessions/" + sessionId + "/case-handover/consent-preference",
            creator,
            "{\"alwaysAskBeforeAdditionalAccess\":true}");
    // These asker-only endpoints reject a consultant before reaching the service. The session's
    // own user is a technical system account without an asker login, so the service guard is
    // covered in CaseHandoverServiceTest instead.
    assertThat(preference.statusCode()).as(preference.body()).isEqualTo(403);
    var consent =
        request(
            "POST",
            "/users/sessions/" + sessionId + "/case-handover/123/client-consent",
            creator,
            "{\"approved\":true}");
    assertThat(consent.statusCode()).as(consent.body()).isEqualTo(403);
    assertThat(
            Tenants.acrossAll(() -> sessions.findById(sessionId).orElseThrow())
                .isAlwaysAskBeforeAdditionalAccess())
        .isEqualTo(before.isAlwaysAskBeforeAdditionalAccess());
    assertThat(synapse.joined(roomId))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));
  }

  @Test
  void aColleagueOfTheCentreWhoWasNeverInvitedIsRefusedByEveryEndpoint() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);

    assertEveryEndpointRefuses(anotherSameCentre, chatId, roomId, sessionId);

    var handedOver =
        request(
            "PUT",
            "/users/sessions/" + sessionId + "/consultant/" + anotherSameCentre.getId(),
            creator,
            "");
    assertThat(handedOver.statusCode()).as("assign the room to a non-member").isEqualTo(403);
    var supervised =
        request(
            "POST",
            "/users/sessions/" + sessionId + "/supervisors",
            creator,
            "{\"supervisorConsultantId\":\"" + anotherSameCentre.getId() + "\"}");
    // The technical session counts as anonymous, which supervision refuses for everyone (400).
    assertThat(supervised.statusCode()).as("supervisor who is no member").isIn(400, 403);
    var candidates = request("GET", "/users/case-handover/candidates", anotherSameCentre, "");
    assertThat(candidates.body()).as("case-handover candidates").doesNotContain(roomId);
    assertThat(synapse.joined(roomId))
        .containsExactlyInAnyOrder(matrix(creator), matrix(sameCentre));
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .containsExactlyInAnyOrder(creator.getId(), sameCentre.getId());
  }

  @Test
  void anOwnerCanRemoveAndHandOverToAColleagueAdmittedFromAnotherTraeger() throws Exception {
    theTraegerScopeIs("PLATFORM");
    var created = openRoom(creator, otherTraeger, sameCentre);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");

    var transferred =
        request(
            "POST",
            "/users/chat-series/" + chatId + "/transfer-ownership",
            creator,
            "{\"consultantId\":\"" + otherTraeger.getId() + "\"}");
    assertThat(transferred.statusCode()).as("ownership to the other Traeger").isEqualTo(204);
    var handedBack =
        request(
            "POST",
            "/users/chat-series/" + chatId + "/transfer-ownership",
            otherTraeger,
            "{\"consultantId\":\"" + creator.getId() + "\"}");
    assertThat(handedBack.statusCode()).as(handedBack.body()).isEqualTo(204);
    var removed =
        request(
            "DELETE",
            "/users/chat-series/" + chatId + "/participants/" + otherTraeger.getId(),
            creator,
            "");

    assertThat(removed.statusCode()).as(removed.body()).isIn(200, 204);
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .containsExactlyInAnyOrder(creator.getId(), sameCentre.getId());
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(otherTraeger));
    assertThat(request("GET", "/users/chat/" + chatId, otherTraeger, "").statusCode())
        .as("the removed colleague of the other Traeger")
        .isEqualTo(403);
  }

  @Test
  void stoppingTheChatFailsAndChangesNothingWhileTheRoomCannotBePurged() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    var before = statusOf(sessionId);
    synapse.failPurge = true;

    var refused = request("PUT", "/users/chat/" + chatId + "/stop", creator, "");

    synapse.failPurge = false;
    // Everyone is still in the encrypted room, so the chat, its participants and its case stay.
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(502);
    assertThat(reason(refused)).isEqualTo("INTERNAL_CHAT_MEMBERSHIP_FAILED");
    assertThat(Tenants.acrossAll(() -> chats.findById(Long.parseLong(chatId)))).isPresent();
    assertThat(participantsOf(storedChat(Long.parseLong(chatId))))
        .contains(creator.getId(), sameCentre.getId());
    assertThat(statusOf(sessionId)).isEqualTo(before);
    assertThat(synapse.joined(roomId)).contains(matrix(creator), matrix(sameCentre));

    var repeated = request("PUT", "/users/chat/" + chatId + "/stop", creator, "");

    assertThat(repeated.statusCode()).as(repeated.body()).isIn(200, 204);
    assertThat(Tenants.acrossAll(() -> chats.findById(Long.parseLong(chatId)))).isEmpty();
    assertThat(synapse.hasRoom(roomId)).as("the room is purged").isFalse();
  }

  @Test
  void stoppingTheChatRetiresItsSessionSoNobodyKeepsABrokenEntry() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var roomId = field(created, "matrixRoomId");
    var sessionId = sessionOf(chatId);
    assertThat(sessionListOf(creator).body()).as("before stopping").contains(roomId);

    var stopped = request("PUT", "/users/chat/" + chatId + "/stop", creator, "");
    assertThat(stopped.statusCode()).as(stopped.body()).isIn(200, 204);

    assertThat(Tenants.acrossAll(() -> chats.findById(Long.parseLong(chatId))))
        .as("the chat is gone")
        .isEmpty();
    assertThat(
            Tenants.acrossAll(
                () ->
                    database.queryForObject(
                        "SELECT status FROM session WHERE id = ?", Integer.class, sessionId)))
        .as("the paired session is closed, not left in progress")
        .isNotEqualTo(Session.SessionStatus.IN_PROGRESS.getValue());
    for (var former : List.of(creator, sameCentre, anotherSameCentre)) {
      for (var status : List.of(1, 2, 3)) {
        var list =
            request(
                "GET",
                "/users/sessions/consultants?status=" + status + "&offset=0&count=100&filter=all",
                former,
                "");
        assertThat(list.body())
            .as("session list of " + former.getId() + ", status " + status)
            .doesNotContain(roomId);
      }
      var bySessionId = request("GET", "/users/sessions/room/" + sessionId, former, "");
      assertThat(bySessionId.statusCode()).as("session by id").isEqualTo(204);
    }
  }

  @Test
  void theSessionOfAMembersOnlyChatIsNotDeletedThroughTheGenericRoute() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var sessionId = sessionOf(chatId);
    var outsider = request("DELETE", "/users/sessions/" + sessionId, anotherSameCentre, "");
    var participant = request("DELETE", "/users/sessions/" + sessionId, sameCentre, "");

    assertThat(outsider.statusCode())
        .as("non-participant " + outsider.body() + outsider.headers())
        .isEqualTo(403);
    assertThat(participant.statusCode()).as("participant " + participant.body()).isEqualTo(409);
    assertThat(reason(participant)).isEqualTo("MEMBERS_ONLY_CHAT_STOP_REQUIRED");
    assertThat(
            Tenants.acrossAll(
                () ->
                    database.queryForObject(
                        "SELECT COUNT(*) FROM session WHERE id = ?", Integer.class, sessionId)))
        .as("the session is still there")
        .isEqualTo(1);
    assertThat(storedChat(Long.parseLong(chatId)).isMembersOnly()).isTrue();
  }

  @Test
  void theSessionOfAMembersOnlyChatIsNotFinishedAsAnAnonymousConversation() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    var sessionId = sessionOf(chatId);

    // The assigned consultant of the technical session, who is no anonymous conversation's.
    var finished = request("PUT", "/conversations/anonymous/" + sessionId + "/finish", creator, "");

    assertThat(finished.statusCode()).as(finished.body()).isEqualTo(403);
    assertThat(
            Tenants.acrossAll(
                () ->
                    database.queryForObject(
                        "SELECT status FROM session WHERE id = ?", Integer.class, sessionId)))
        .as("still in progress")
        .isEqualTo(Session.SessionStatus.IN_PROGRESS.getValue());
  }

  @Test
  void anAdviceSeekerCannotWriteSessionDataOfAMembersOnlyChat() throws Exception {
    var created = openRoom(creator, sameCentre);
    var sessionId = sessionOf(field(created, "chatId"));
    var adviceSeeker = fixtures.adviceSeeker(TRAEGER);

    var written =
        requestAsAdviceSeeker(
            "PUT", "/users/sessions/" + sessionId + "/data", adviceSeeker, "{\"age\":\"30\"}");

    assertThat(written.statusCode()).as(written.body()).isEqualTo(403);
    assertThat(
            Tenants.acrossAll(
                () ->
                    database.queryForObject(
                        "SELECT COUNT(*) FROM session_data WHERE session_id = ?",
                        Integer.class,
                        sessionId)))
        .isZero();
  }

  @Test
  void aFormerOwnerCannotChangeTheSettingsOfTheChatAnyMore() throws Exception {
    var created = openRoom(creator, sameCentre);
    var chatId = field(created, "chatId");
    request(
        "POST",
        "/users/chat-series/" + chatId + "/transfer-ownership",
        creator,
        "{\"consultantId\":\"" + sameCentre.getId() + "\"}");
    request("PUT", "/users/chat/" + chatId + "/leave", creator, "");

    var updated =
        request(
            "PUT",
            "/users/chat/" + chatId + "/update",
            creator,
            "{\"topic\":\"Hijacked\",\"agencyId\":410,\"startDate\":\"2999-01-01\","
                + "\"startTime\":\"12:00\",\"duration\":60,\"repetitive\":false,"
                + "\"timezone\":\"UTC\",\"modality\":\"TEXT\",\"consultantIds\":[]}");

    assertThat(updated.statusCode()).as(updated.body()).isEqualTo(403);
    assertThat(storedChat(Long.parseLong(chatId)).getTopic()).isNotEqualTo("Hijacked");
  }

  private Long sessionOf(String chatId) {
    return Tenants.acrossAll(
        () ->
            database.queryForObject(
                "SELECT chat_id FROM group_chat_participant WHERE series_id = ? LIMIT 1",
                Long.class,
                Long.parseLong(chatId)));
  }

  /** The one rule seen from outside: whoever is no participant gets nothing, on any path. */
  private void assertEveryEndpointRefuses(
      Consultant outsider, String chatId, String roomId, Long sessionId) throws Exception {
    var session = "/users/sessions/" + sessionId;
    var chat = "/users/chat/" + chatId;
    for (var call :
        List.of(
            List.of("GET", chat),
            List.of("PUT", chat + "/join"),
            List.of("GET", chat + "/members"),
            List.of("PUT", chat + "/start"),
            List.of("PUT", chat + "/stop"),
            List.of("PUT", session + "/archive"),
            List.of("GET", session + "/supervisors"),
            List.of("GET", session + "/case-handover"))) {
      var response = request(call.get(0), call.get(1), outsider, "");
      assertThat(response.statusCode()).as(call + " " + response.body()).isIn(403, 404);
    }
    var supervisors =
        request(
            "POST",
            session + "/supervisors",
            outsider,
            "{\"supervisorConsultantId\":\"" + outsider.getId() + "\"}");
    assertThat(supervisors.statusCode()).as("add supervisor " + supervisors.body()).isEqualTo(403);
    var assign = request("PUT", session + "/consultant/" + outsider.getId(), outsider, "");
    assertThat(assign.statusCode()).as("assign to self " + assign.body()).isEqualTo(403);
    var unassign = request("DELETE", session + "/consultant/" + creator.getId(), outsider, "");
    assertThat(unassign.statusCode()).as("unassign the creator " + unassign.body()).isEqualTo(403);
    var bySessionId = request("GET", "/users/sessions/room/" + sessionId, outsider, "");
    assertThat(bySessionId.statusCode()).as("session by id").isEqualTo(204);
    assertThat(bySessionId.body()).as("session by id").isEmpty();
    for (var status : List.of(1, 2, 3)) {
      var list =
          request(
              "GET",
              "/users/sessions/consultants?status=" + status + "&offset=0&count=100&filter=all",
              outsider,
              "");
      assertThat(list.statusCode()).as(list.body()).isIn(200, 204);
      assertThat(list.body()).as("session list, status " + status).doesNotContain(roomId);
    }
    var candidates = request("GET", "/users/case-handover/candidates", outsider, "");
    assertThat(candidates.body()).as("case-handover candidates").doesNotContain(roomId);
    assertThat(synapse.joined(roomId)).doesNotContain(matrix(outsider));
  }

  private void assertTheRoomStillWorksFor(
      Consultant member, String chatId, String roomId, Long sessionId) throws Exception {
    assertThat(request("GET", "/users/chat/" + chatId, member, "").statusCode()).isEqualTo(200);
    assertThat(sessionListOf(member).body()).contains(roomId);
  }

  /** The resolver remembers an answer for a minute; a test that changes the setting forgets it. */
  private void theTraegerScopeIs(String scope) {
    recipientScopeOfTraeger.put(TRAEGER, scope);
    ((java.util.Map<?, ?>) ReflectionTestUtils.getField(recipientScopes, "remembered")).clear();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private HttpResponse<String> openRoom(Consultant caller, Consultant... selection)
      throws Exception {
    var ids =
        java.util.Arrays.stream(selection)
            .map(c -> "\"" + c.getId() + "\"")
            .collect(Collectors.joining(","));
    return request(
        "POST",
        "/users/chat/internal-subset",
        caller,
        "{\"consultantIds\":[" + ids + "],\"agencyId\":" + CENTRE + "}");
  }

  private HttpResponse<String> openWithIds(Consultant caller, String... ids) throws Exception {
    var list =
        java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
    return request(
        "POST",
        "/users/chat/internal-subset",
        caller,
        "{\"consultantIds\":[" + list + "],\"agencyId\":" + CENTRE + "}");
  }

  private HttpResponse<String> openWithoutCentre(Consultant caller, Consultant... selection)
      throws Exception {
    var ids =
        java.util.Arrays.stream(selection)
            .map(c -> "\"" + c.getId() + "\"")
            .collect(Collectors.joining(","));
    return request(
        "POST", "/users/chat/internal-subset", caller, "{\"consultantIds\":[" + ids + "]}");
  }

  private List<Long> centresOf(HttpResponse<String> created) {
    return Tenants.acrossAll(
        () ->
            database.queryForList(
                "SELECT agency_id FROM chat_agency WHERE chat_id = ?",
                Long.class,
                Long.parseLong(field(created, "chatId"))));
  }

  private final Set<String> signedIn = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Stubbing the decoder is not thread-safe: do it once, before concurrent requests start. */
  private synchronized void signIn(Consultant caller) {
    if (!signedIn.add(caller.getId())) {
      return;
    }
    var token =
        Jwt.withTokenValue("synthetic-token-" + caller.getId())
            .header("alg", "none")
            .subject(caller.getId())
            .claim("username", caller.getUsername())
            .claim("tenantId", caller.getTenantId())
            .claim("realm_access", Map.of("roles", List.of("consultant")))
            .build();
    when(jwtDecoder.decode("synthetic-token-" + caller.getId())).thenReturn(token);
  }

  private HttpResponse<String> request(String method, String path, Consultant caller, String body)
      throws Exception {
    signIn(caller);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer synthetic-token-" + caller.getId())
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> requestAsAdviceSeeker(String method, String path, User adviceSeeker)
      throws Exception {
    return requestAsAdviceSeeker(method, path, adviceSeeker, "");
  }

  private HttpResponse<String> requestAsAdviceSeeker(
      String method, String path, User adviceSeeker, String body) throws Exception {
    var token =
        Jwt.withTokenValue("synthetic-token-" + adviceSeeker.getUserId())
            .header("alg", "none")
            .subject(adviceSeeker.getUserId())
            .claim("username", adviceSeeker.getUsername())
            .claim("tenantId", adviceSeeker.getTenantId())
            .claim("realm_access", Map.of("roles", List.of("user")))
            .build();
    when(jwtDecoder.decode("synthetic-token-" + adviceSeeker.getUserId())).thenReturn(token);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer synthetic-token-" + adviceSeeker.getUserId())
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String field(HttpResponse<String> response, String name) {
    return JSON.readTree(response.body()).get(name).asText();
  }

  private static String reason(HttpResponse<String> response) {
    return response.headers().firstValue("X-Reason").orElse("");
  }

  private static String matrix(Consultant consultant) {
    return consultant.getMatrixUserId();
  }

  private void revokeInTransaction(Consultant leaving) {
    var transaction = new org.springframework.transaction.support.TransactionTemplate(txManager);
    Tenants.acrossAll(
        () ->
            transaction.execute(
                status -> {
                  revocation.revokeAllAccessOf(leaving);
                  return null;
                }));
  }

  private Chat storedChat(Long chatId) {
    return Tenants.acrossAll(() -> chats.findById(chatId).orElseThrow());
  }

  private Set<String> participantsOf(Chat chat) {
    return Tenants.acrossAll(
        () ->
            participants.findBySeriesId(chat.getId()).stream()
                .map(GroupChatParticipant::getConsultantId)
                .collect(Collectors.toSet()));
  }

  private void assertNothingWasCreated() {
    assertThat(synapse.roomsCreated()).isZero();
    assertThat(allChatsOf(everyone)).isEmpty();
    assertThat(allSessionsOf(everyone)).isEmpty();
  }

  private List<Long> allChatsOf(List<Consultant> owners) {
    return Tenants.acrossAll(
        () ->
            owners.stream()
                .flatMap(owner -> chats.findByChatOwner(owner).stream())
                .map(Chat::getId)
                .toList());
  }

  private List<Long> allSessionsOf(List<Consultant> owners) {
    return Tenants.acrossAll(
        () ->
            database.queryForList(
                "SELECT id FROM session WHERE consultant_id IN ("
                    + owners.stream()
                        .map(c -> "'" + c.getId() + "'")
                        .collect(Collectors.joining(","))
                    + ")",
                Long.class));
  }

  private boolean systemUserExists(long tenant) {
    return database.queryForObject(
            "SELECT COUNT(*) FROM user WHERE user_id = 'group-chat-system-" + tenant + "'",
            Integer.class)
        > 0;
  }

  // ---- the other side of every outgoing call -------------------------------------------------

  private ClientHttpResponse externalWorld(ClientHttpRequest request) throws IOException {
    var path = request.getURI().getPath();
    var method = request.getMethod();
    if (path.endsWith("/token")) {
      return json(
          request,
          "{\"access_token\":\"synthetic-service-token\",\"expires_in\":60,"
              + "\"refresh_expires_in\":60,\"refresh_token\":\"synthetic-refresh\"}");
    }
    if (path.endsWith("/logout")) {
      return withNoContent().createResponse(request);
    }
    if (path.startsWith("/tenant/public/id/") || path.equals("/tenant/public/single")) {
      var id = path.endsWith("/42") ? 42 : 41;
      return json(request, "{\"id\":" + id + ",\"settings\":{\"featureGroupChatV2Enabled\":true}}");
    }
    if (path.startsWith("/tenantadmin/") && path.endsWith("/permission-policies")) {
      tenantPolicyReads.incrementAndGet();
      var tenant = Long.parseLong(path.split("/")[2]);
      var scope = recipientScopeOfTraeger.getOrDefault(tenant, "AGENCY");
      return json(
          request,
          tenantServiceKnowsTheSetting
              ? "{\"tenantId\":"
                  + tenant
                  + ",\"policies\":{},\"internalGroupChat\":{\"recipientScope\":"
                  + "{\"value\":\""
                  + scope
                  + "\",\"mode\":\"SUGGESTED\",\"inherited\":false}}}"
              : "{\"tenantId\":" + tenant + ",\"policies\":{}}");
    }
    if (path.matches(".*/agencies/\\d+")) {
      var agencyId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
      var tenant = agencyId == FOREIGN_CENTRE ? OTHER_TRAEGER : TRAEGER;
      return json(
          request,
          "[{\"id\":"
              + agencyId
              + ",\"tenantId\":"
              + tenant
              + ",\"consultingType\":1,\"teamAgency\":false,\"topicIds\":[],\"settings\":"
              + "{\"featureGroupChatV2Enabled\":true,\"featureInternalGroupChatEnabled\":true}}]");
    }
    if (path.startsWith("/_matrix/") || path.startsWith("/_synapse/")) {
      var body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
      return synapse.answer(request, method, path, body);
    }
    throw new AssertionError("Unexpected external HTTP path: " + method + " " + path);
  }

  private static ClientHttpResponse json(ClientHttpRequest request, String body)
      throws IOException {
    return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
  }

  /** A Synapse that remembers: rooms, who was invited, who joined, and whether encryption is on. */
  private static final class SyntheticSynapse {
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Set<String> refusedInvitations = new HashSet<>();
    private final AtomicInteger created = new AtomicInteger();
    private int numbering;
    volatile int createDelayMillis;
    volatile boolean failMemberReads;
    final Set<String> unreadableRooms = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile boolean failPurge;
    final Set<String> roomsRefusingLeave = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class Room {
      final Set<String> invited = new LinkedHashSet<>();
      final Set<String> joined = new LinkedHashSet<>();
      boolean encrypted;
    }

    private static void pause(int millis) {
      if (millis > 0) {
        try {
          Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    synchronized int roomsCreated() {
      return created.get();
    }

    synchronized Set<String> joined(String roomId) {
      return new TreeSet<>(rooms.get(roomId).joined);
    }

    synchronized boolean hasRoom(String roomId) {
      return rooms.containsKey(roomId);
    }

    synchronized boolean isEncrypted(String roomId) {
      return rooms.get(roomId).encrypted;
    }

    synchronized Set<String> liveRooms() {
      return new LinkedHashSet<>(rooms.keySet());
    }

    synchronized void joinDirectly(String roomId, String matrixUserId) {
      rooms.get(roomId).joined.add(matrixUserId);
    }

    /** Matrix state a failed deletion leaves behind: the member is out of the room already. */
    synchronized void leaveDirectly(String roomId, String matrixUserId) {
      rooms.get(roomId).joined.remove(matrixUserId);
    }

    /** Matrix state a failed deletion leaves behind: the room is purged already. */
    synchronized void purgeDirectly(String roomId) {
      rooms.remove(roomId);
    }

    synchronized void refuseInvitationOf(String matrixUserId) {
      refusedInvitations.add(matrixUserId);
    }

    synchronized ClientHttpResponse answer(
        ClientHttpRequest request, HttpMethod method, String path, String body) throws IOException {
      if (path.equals("/_matrix/client/r0/login")) {
        var node = JSON.readTree(body);
        var who = node.path("identifier").path("user").asText(node.path("user").asText(""));
        var token =
            who.isBlank() || who.equals("synthetic-matrix-admin")
                ? "admin-token"
                : "as:@" + who.replaceFirst("^@", "").replaceFirst(":.*$", "") + ":" + SERVER;
        return withSuccess("{\"access_token\":\"" + token + "\"}", MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      if (path.startsWith("/_synapse/admin/v1/users/") && path.endsWith("/login")) {
        var user =
            URLDecoder.decode(
                path.substring(
                    "/_synapse/admin/v1/users/".length(), path.length() - "/login".length()),
                StandardCharsets.UTF_8);
        return withSuccess("{\"access_token\":\"as:" + user + "\"}", MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      var actor = actorOf(request);
      if (path.equals("/_matrix/client/r0/joined_rooms")) {
        var mine =
            rooms.entrySet().stream()
                .filter(room -> room.getValue().joined.contains(actor))
                .map(room -> "\"" + room.getKey() + "\"")
                .collect(Collectors.joining(","));
        return withSuccess("{\"joined_rooms\":[" + mine + "]}", MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      if (path.equals("/_matrix/client/r0/createRoom")) {
        pause(createDelayMillis);
        var node = JSON.readTree(body);
        var room = new Room();
        var roomId = "!room" + (++numbering) + ":" + SERVER;
        room.joined.add(actor);
        var initialState = node.path("initial_state");
        for (JsonNode event : initialState) {
          if ("m.room.encryption".equals(event.path("type").asText())) {
            room.encrypted = true;
          }
        }
        rooms.put(roomId, room);
        created.incrementAndGet();
        return withSuccess("{\"room_id\":\"" + roomId + "\"}", MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      if (path.startsWith("/_matrix/client/r0/rooms/") && path.endsWith("/invite")) {
        var room = rooms.get(roomOf(path, "/invite"));
        var invitee = JSON.readTree(body).path("user_id").asText();
        if (room == null || !room.joined.contains(actor) || refusedInvitations.contains(invitee)) {
          return withStatus(HttpStatus.FORBIDDEN)
              .body("{\"errcode\":\"M_FORBIDDEN\"}")
              .contentType(MediaType.APPLICATION_JSON)
              .createResponse(request);
        }
        room.invited.add(invitee);
        return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
      }
      if (path.startsWith("/_matrix/client/r0/rooms/") && path.endsWith("/join")) {
        var room = rooms.get(roomOf(path, "/join"));
        if (room == null || !(room.invited.contains(actor) || room.joined.contains(actor))) {
          return withStatus(HttpStatus.FORBIDDEN)
              .body("{\"errcode\":\"M_FORBIDDEN\"}")
              .contentType(MediaType.APPLICATION_JSON)
              .createResponse(request);
        }
        room.joined.add(actor);
        return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
      }
      if (path.startsWith("/_matrix/client/r0/rooms/") && path.endsWith("/leave")) {
        var room = rooms.get(roomOf(path, "/leave"));
        if (roomsRefusingLeave.contains(roomOf(path, "/leave"))) {
          return withStatus(HttpStatus.INTERNAL_SERVER_ERROR).createResponse(request);
        }
        if (room != null) {
          room.joined.remove(actor);
          room.invited.remove(actor);
        }
        return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
      }
      if (path.startsWith("/_synapse/admin/v1/rooms/")
          && path.endsWith("/members")
          && (failMemberReads || unreadableRooms.stream().anyMatch(room -> path.contains(room)))) {
        return withStatus(HttpStatus.INTERNAL_SERVER_ERROR).createResponse(request);
      }
      if (path.startsWith("/_synapse/admin/v1/rooms/") && path.endsWith("/members")) {
        var roomId =
            URLDecoder.decode(
                path.substring(
                    "/_synapse/admin/v1/rooms/".length(), path.length() - "/members".length()),
                StandardCharsets.UTF_8);
        var room = rooms.get(roomId);
        if (room == null) {
          return withStatus(HttpStatus.NOT_FOUND).createResponse(request);
        }
        var members =
            room.joined.stream().map(m -> "\"" + m + "\"").collect(Collectors.joining(","));
        return withSuccess(
                "{\"members\":[" + members + "],\"total\":" + room.joined.size() + "}",
                MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      if (path.startsWith("/_synapse/admin/v2/rooms/")
          && method == HttpMethod.DELETE
          && failPurge) {
        return withStatus(HttpStatus.INTERNAL_SERVER_ERROR).createResponse(request);
      }
      if (path.startsWith("/_synapse/admin/v2/rooms/") && method == HttpMethod.DELETE) {
        var roomId =
            URLDecoder.decode(
                path.substring("/_synapse/admin/v2/rooms/".length()), StandardCharsets.UTF_8);
        rooms.remove(roomId);
        return withSuccess("{\"delete_id\":\"x\"}", MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      if (method == HttpMethod.PUT
          && path.startsWith("/_matrix/client/r0/rooms/")
          && path.contains("/send/m.room.message/")) {
        return withSuccess("{\"event_id\":\"$synthetic\"}", MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      throw new AssertionError("Unexpected Matrix path: " + method + " " + path);
    }

    private static String roomOf(String path, String suffix) {
      return URLDecoder.decode(
          path.substring("/_matrix/client/r0/rooms/".length(), path.length() - suffix.length()),
          StandardCharsets.UTF_8);
    }

    private static String actorOf(ClientHttpRequest request) {
      var authorization = request.getHeaders().getFirst("Authorization");
      if (authorization != null && authorization.startsWith("Bearer as:")) {
        return authorization.substring("Bearer as:".length());
      }
      return "";
    }
  }
}
