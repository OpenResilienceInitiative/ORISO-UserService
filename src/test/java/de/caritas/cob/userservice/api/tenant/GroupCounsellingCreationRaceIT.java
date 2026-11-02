package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/** Public creation with real independent native callback transactions at the external boundary. */
class GroupCounsellingCreationRaceIT extends GroupCounsellingMatrixHistoryHttpFixture {
  @org.springframework.beans.factory.annotation.Autowired
  de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants;

  private String boundary;
  private boolean provisionUnavailable;
  private Boolean visibleNativeClassification;

  @Override
  protected boolean matrixImpersonationAvailable(String path) {
    return !provisionUnavailable || path.contains(consultant.getMatrixUserId());
  }

  @Test
  void aMissingMatrixAccountPrerequisiteFailureCannotLeaveAnUnrecoverableSuccessfulCreation()
      throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var selected = fixtures.consultant(OWNER, AGENCY);
    selected.setMatrixUserId(null);
    Tenants.in(OWNER, () -> consultants.save(selected));
    provisionUnavailable = true;
    var response = createWithSelected(selected.getId());
    assertEquals(502, response.statusCode(), response.body());
    assertEquals(0, nativeJoinWrites);
    assertTrue(chats.findByChatOwner(consultant).isEmpty());
    assertTrue(copiedActors.isEmpty());
  }

  @Override
  protected void onGroupRoomCreated() {
    if ("EXPIRED".equals(boundary)) gate = EXPIRED;
    if ("UNAVAILABLE".equals(boundary)) ownerStatus = 503;
  }

  @ParameterizedTest
  @ValueSource(strings = {"EXPIRED", "UNAVAILABLE"})
  void aDeadlineChangeDuringRoomCreationCannotSilentlyStartSelectedCounselling(String mode)
      throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    boundary = mode;
    var selected = fixtures.consultant(OWNER, AGENCY);
    var response = createWithSelected(selected.getId());
    assertEquals(mode.equals("EXPIRED") ? 403 : 502, response.statusCode(), response.body());
    assertFalse(copiedActors.contains(selected.getMatrixUserId()));
  }

  @Test
  void nativeAdmissionSeesCanonicalClassificationBeforeTheFirstSelectedInvitation()
      throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    boundary = "PROBE_NATIVE";
    var selected = fixtures.consultant(OWNER, AGENCY);
    var response = createWithSelected(selected.getId());
    assertEquals(201, response.statusCode(), response.body());
    assertEquals(Boolean.TRUE, visibleNativeClassification);
  }

  @Test
  void healthyCreationOnlyAssignsAndInvitesAndTheNormalPublicJoinCommencesParticipation()
      throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    participation = "NOT_COMMENCED";
    var selected = fixtures.consultant(OWNER, AGENCY);
    var response = createWithSelected(selected.getId());
    assertEquals(201, response.statusCode(), response.body());
    assertTrue(copiedActors.contains(selected.getMatrixUserId()));
    assertEquals(0, nativeJoinWrites);
    var chat = chats.findByMatrixRoomId("!synthetic-created:synthetic.oriso.test").orElseThrow();
    assertEquals(200, start(chat, consultant).statusCode());
    var joined =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/join",
            selected.getId(),
            selected.getUsername(),
            "consultant",
            OWNER,
            "");
    assertEquals(200, joined.statusCode(), joined.body());
    assertEquals(1, nativeJoinWrites);
  }

  @Test
  void anInvitationFailureIsRecoverableThroughTheOrdinaryAuthorizedJoinPath() throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    participation = "NOT_COMMENCED";
    var selected = fixtures.consultant(OWNER, AGENCY);
    matrixInviteDenied = true;
    var response = createWithSelected(selected.getId());
    assertEquals(201, response.statusCode(), response.body());
    assertEquals(0, nativeJoinWrites);
    var chat = chats.findByMatrixRoomId("!synthetic-created:synthetic.oriso.test").orElseThrow();
    matrixInviteDenied = false;
    assertEquals(200, start(chat, consultant).statusCode());
    var joined =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/join",
            selected.getId(),
            selected.getUsername(),
            "consultant",
            OWNER,
            "");
    assertEquals(200, joined.statusCode(), joined.body());
    assertEquals(1, nativeJoinWrites);
  }

  @Test
  void selectionAndInvitationDoNotExemptTheFirstActualJoinAfterExpiry() throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    participation = "NOT_COMMENCED";
    var selected = fixtures.consultant(OWNER, AGENCY);
    var created = createWithSelected(selected.getId());
    assertEquals(201, created.statusCode(), created.body());
    var chat = chats.findByMatrixRoomId("!synthetic-created:synthetic.oriso.test").orElseThrow();
    assertEquals(200, start(chat, consultant).statusCode());
    gate = EXPIRED;
    var joined =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/join",
            selected.getId(),
            selected.getUsername(),
            "consultant",
            OWNER,
            "");
    assertEquals(403, joined.statusCode(), joined.body());
    assertEquals(0, nativeJoinWrites);
  }

  @Test
  void creationCommitsTheSelectedActorsMatrixMappingWithoutCommencingParticipation()
      throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var selected = fixtures.consultant(OWNER, AGENCY);
    selected.setMatrixUserId(null);
    Tenants.in(OWNER, () -> consultants.save(selected));
    var response = createWithSelected(selected.getId());
    assertEquals(201, response.statusCode(), response.body());
    var fresh = Tenants.in(OWNER, () -> consultants.findById(selected.getId()).orElseThrow());
    assertEquals("@created-moderator:synthetic.oriso.test", fresh.getMatrixUserId());
    assertTrue(copiedActors.contains(fresh.getMatrixUserId()));
    assertEquals(0, nativeJoinWrites);
  }

  private java.net.http.HttpResponse<String> createWithSelected(String selected) throws Exception {
    return request(
        "POST",
        "/users/chat/v2/new",
        consultant.getId(),
        consultant.getUsername(),
        "consultant",
        OWNER,
        "{\"topic\":\"Synthetic creation race\",\"agencyId\":410,\"startDate\":\"2999-01-01\",\"startTime\":\"12:00\",\"duration\":60,\"repeatCount\":1,\"timezone\":\"UTC\",\"modality\":\"TEXT\",\"consultantIds\":[\""
            + selected
            + "\"]}");
  }

  @Override
  protected ClientHttpResponse additionalExternalResponse(ClientHttpRequest request)
      throws IOException {
    if (request.getURI().getPath().equals("/_synapse/admin/v1/register")) {
      if (provisionUnavailable)
        return org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
            .createResponse(request);
      return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
              request.getMethod() == org.springframework.http.HttpMethod.GET
                  ? "{\"nonce\":\"synthetic-nonce\"}"
                  : "{\"user_id\":\"@created-moderator:synthetic.oriso.test\"}",
              org.springframework.http.MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    if ("PROBE_NATIVE".equals(boundary) && request.getURI().getPath().endsWith("/invite")) {
      try {
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
                        "!synthetic-created:synthetic.oriso.test",
                        "matrixUserId",
                        "@selected:synthetic.oriso.test")));
        assertEquals(200, response.statusCode(), response.body());
        visibleNativeClassification =
            json.readTree(response.body()).path("applicable").booleanValue();
      } catch (Exception failure) {
        throw new IOException(failure);
      }
    }
    return super.additionalExternalResponse(request);
  }
}
