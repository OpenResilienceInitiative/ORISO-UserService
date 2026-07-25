package de.caritas.cob.userservice.api.adapters.rocketchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.MessageClientException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.facade.RocketChatFacade;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RocketChatSessionAssignmentGatewayTest {

  @Mock private RocketChatFacade rocketChatFacade;
  @Mock private RocketChatCredentialsProvider credentialsProvider;
  @Mock private IdentityClient identityClient;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @InjectMocks private RocketChatSessionAssignmentGateway gateway;

  @Test
  void shouldExposeOnlyStableMemberIds() {
    when(rocketChatFacade.retrieveRocketChatMemberIds("room"))
        .thenReturn(List.of("user-1", "user-2"));

    assertThat(gateway.findMemberIds("room")).containsExactly("user-1", "user-2");
  }

  @Test
  void shouldDelegateGroupMutations() {
    gateway.addUserToGroup("user", "room");
    gateway.removeSystemMessages("room");

    verify(rocketChatFacade).addUserToRocketChatGroup("user", "room");
    verify(rocketChatFacade).removeSystemMessagesFromRocketChatGroup("room");
  }

  @Test
  void shouldExposeTechnicalUserAsStableId() throws Exception {
    var credentials = RocketChatCredentials.builder().rocketChatUserId("technical-user").build();
    when(credentialsProvider.getTechnicalUser()).thenReturn(credentials);

    assertThat(gateway.technicalUserId()).isEqualTo("technical-user");
  }

  @Test
  void shouldTranslateMissingTechnicalUserToNeutralFailure() throws Exception {
    when(credentialsProvider.getTechnicalUser())
        .thenThrow(new RocketChatUserNotInitializedException("missing"));

    assertThatThrownBy(gateway::technicalUserId).isInstanceOf(MessageClientException.class);
  }

  @Test
  void shouldKeepLegacyRemovalPolicyBehindTheGateway() {
    var session = new Session();
    session.setGroupId("room");
    var consultant = new Consultant();
    consultant.setRocketChatId("consultant");
    when(rocketChatFacade.retrieveRocketChatMemberIds("room")).thenReturn(List.of("consultant"));

    gateway.removeConsultantsIgnoringMissingGroup(session, List.of(consultant));

    verify(rocketChatFacade).addTechnicalUserToGroup("room");
    verify(rocketChatFacade).removeUserFromGroupIgnoreGroupNotFound("consultant", "room");
    verify(rocketChatFacade).leaveFromGroupAsTechnicalUser("room");
  }
}
