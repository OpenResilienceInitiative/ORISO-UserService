package de.caritas.cob.userservice.api.actions.session;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.DONE;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_ARCHIVE;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.messageservice.generated.web.model.MessageType.CONSULTANT_DISPLAY_NAME_CHANGED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.config.apiclient.MessageServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.messageservice.generated.web.MessageControllerApi;
import de.caritas.cob.userservice.messageservice.generated.web.model.AliasOnlyMessageDTO;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
class PostConsultantDisplayNameChangedAliasMessageCommandTest {

  @InjectMocks private PostConsultantDisplayNameChangedAliasMessageCommand command;

  @Mock private MessageControllerApi messageControllerApi;
  @Mock private MessageServiceApiControllerFactory messageServiceApiControllerFactory;
  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private TenantHeaderSupplier tenantHeaderSupplier;
  @Mock private IdentityClient identityClient;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private SessionRepository sessionRepository;

  private final EasyRandom easyRandom = new EasyRandom();

  @Test
  void execute_Should_doNothingWhenConsultantIsNull() {
    command.execute(null);

    verifyNoInteractions(sessionRepository);
    verifyNoInteractions(messageControllerApi);
  }

  @Test
  void execute_Should_doNothingWhenConsultantHasNoSessions() {
    var consultant = easyRandom.nextObject(Consultant.class);
    when(sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of());

    command.execute(consultant);

    verifyNoInteractions(messageServiceApiControllerFactory);
    verifyNoInteractions(messageControllerApi);
    verifyNoInteractions(identityClient);
  }

  @Test
  void execute_Should_postAliasMessageForEachSessionOfAllRelevantStatuses() {
    var keycloakLoginResponseDTO = new KeycloakLoginResponseDTO();
    keycloakLoginResponseDTO.setAccessToken("token");
    when(identityClient.loginUser(any(), any())).thenReturn(keycloakLoginResponseDTO);
    when(identityClientConfig.getTechnicalUser()).thenReturn(new TechnicalUserConfig());
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(any())).thenReturn(new HttpHeaders());
    when(messageServiceApiControllerFactory.createControllerApi()).thenReturn(messageControllerApi);

    var sessionInProgress = givenSessionWithGroupId("group-in-progress");
    var sessionInArchive = givenSessionWithGroupId("group-in-archive");
    var consultant = easyRandom.nextObject(Consultant.class);

    when(sessionRepository.findByConsultantAndStatusIn(
            consultant, List.of(IN_PROGRESS, IN_ARCHIVE)))
        .thenReturn(List.of(sessionInProgress, sessionInArchive));

    command.execute(consultant);

    var expectedMessageType =
        new AliasOnlyMessageDTO().messageType(CONSULTANT_DISPLAY_NAME_CHANGED);
    verify(messageControllerApi).saveAliasOnlyMessage("group-in-progress", expectedMessageType);
    verify(messageControllerApi).saveAliasOnlyMessage("group-in-archive", expectedMessageType);
    verify(identityClient).loginUser(any(), any());
    verify(messageServiceApiControllerFactory).createControllerApi();
  }

  @Test
  void execute_Should_skipSessionsWithBlankGroupId() {
    var keycloakLoginResponseDTO = new KeycloakLoginResponseDTO();
    keycloakLoginResponseDTO.setAccessToken("token");
    when(identityClient.loginUser(any(), any())).thenReturn(keycloakLoginResponseDTO);
    when(identityClientConfig.getTechnicalUser()).thenReturn(new TechnicalUserConfig());
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(any())).thenReturn(new HttpHeaders());
    when(messageServiceApiControllerFactory.createControllerApi()).thenReturn(messageControllerApi);

    var sessionWithGroup = givenSessionWithGroupId("group-in-progress");
    var sessionWithoutGroup = givenSessionWithGroupId(null);
    var consultant = easyRandom.nextObject(Consultant.class);

    when(sessionRepository.findByConsultantAndStatusIn(
            consultant, List.of(IN_PROGRESS, IN_ARCHIVE)))
        .thenReturn(List.of(sessionWithGroup, sessionWithoutGroup));

    command.execute(consultant);

    var expectedMessageType =
        new AliasOnlyMessageDTO().messageType(CONSULTANT_DISPLAY_NAME_CHANGED);
    verify(messageControllerApi).saveAliasOnlyMessage("group-in-progress", expectedMessageType);
    verify(messageControllerApi).saveAliasOnlyMessage(any(), any());
  }

  @Test
  void execute_Should_queryOnlyRelevantStatuses() {
    var consultant = easyRandom.nextObject(Consultant.class);
    when(sessionRepository.findByConsultantAndStatusIn(any(), any())).thenReturn(List.of());

    command.execute(consultant);

    verify(sessionRepository)
        .findByConsultantAndStatusIn(
            eq(consultant),
            argThat(
                statuses ->
                    statuses.contains(IN_PROGRESS)
                        && statuses.contains(IN_ARCHIVE)
                        && !statuses.contains(DONE)
                        && !statuses.contains(NEW)
                        && !statuses.contains(INITIAL)));
  }

  private Session givenSessionWithGroupId(String groupId) {
    var session = new Session();
    session.setGroupId(groupId);
    return session;
  }
}
