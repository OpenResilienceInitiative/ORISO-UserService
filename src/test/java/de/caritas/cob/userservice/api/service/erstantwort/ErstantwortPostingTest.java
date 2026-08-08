package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * ORISO-UserService#926 acceptance: the Erstantwort reaches the room, and it does so <b>without a
 * Carimat account</b>.
 *
 * <p>That second half is the one worth a test rather than a comment. ADR-018 §3 rejected a bot
 * account because it would be an additional Megolm key holder in a room carrying §11 KDG
 * special-category data, and because a bot has no Schweigepflicht. Nothing in the type system stops
 * a later change from registering one, so the absence is asserted here: the event goes out on the
 * session's own credential resolution and no room membership is touched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ErstantwortPostingTest {

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private AgencyMatrixCredentialClient agencyMatrixCredentialClient;
  @Mock private SessionService sessionService;
  @Mock private ConsultantService consultantService;

  @InjectMocks private MatrixSessionSystemMessageService service;

  private final ErstantwortPayloadBuilder builder = new ErstantwortPayloadBuilder();

  private Session session;

  @BeforeEach
  void setUp() {
    var user = new User();
    user.setMatrixUserId("@katze-mika-1234:oriso.test");

    session = new Session();
    session.setId(4711L);
    session.setMatrixRoomId("!room:oriso.test");
    session.setUser(user);

    when(matrixSynapseService.loginAsUserAccessToken(anyString())).thenReturn("token");
    when(matrixSynapseService.sendMessage(anyString(), anyString(), anyString()))
        .thenReturn(Map.of("event_id", "$evt"));
  }

  private String post() {
    var body =
        builder.buildFirstResponseBody(
            ErstantwortContext.builder().modality(ErstantwortModality.AGENCY_COUNSELLING).build());
    service.postFirstResponseMessage(session, body);
    var captor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService).sendMessage(eq("!room:oriso.test"), captor.capture(), anyString());
    return captor.getValue();
  }

  @Test
  void postsTheErstantwortIntoTheSessionRoomAsOneEvent() {
    var posted = post();

    assertThat(posted).startsWith("[SYSTEM_NOTIFICATION]");
    assertThat(posted).contains("\"type\":\"FIRST_RESPONSE\"");
    assertThat(posted).contains("\"version\":1");
    // One event for the whole sequence, not one per Baustein.
    verify(matrixSynapseService, times(1)).sendMessage(anyString(), anyString(), anyString());
  }

  @Test
  void createsNoCarimatAccountAndAddsNoRoomMember() {
    post();

    // The sender is the session's own user; nothing registers or invites anybody.
    verify(matrixSynapseService).loginAsUserAccessToken("@katze-mika-1234:oriso.test");
    verify(matrixSynapseService, never()).loginAsUserAccessToken("@carimat:oriso.test");
    verify(agencyMatrixCredentialClient, never()).fetchMatrixCredentials(any());
  }

  @Test
  void postsNothingWhenTheSessionHasNoRoom() {
    session.setMatrixRoomId(null);
    when(sessionService.getSession(4711L)).thenReturn(java.util.Optional.empty());

    service.postFirstResponseMessage(session, "[SYSTEM_NOTIFICATION]{}");

    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
  }

  @Test
  void postsNothingWhenTheBuilderCouldNotSerialiseAPayload() {
    service.postFirstResponseMessage(session, null);

    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
  }
}
