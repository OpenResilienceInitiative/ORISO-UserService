package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.USER_CONTENT;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.model.UserMobileToken;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
public class DeleteDatabaseAskerActionTest {

  @InjectMocks private DeleteDatabaseAskerAction deleteDatabaseAskerAction;

  @Mock private UserRepository userRepository;

  @Mock private UserChatRepository userChatRepository;

  @Mock private UserMobileTokenRepository userMobileTokenRepository;

  @Mock private IdentityTombstoneService identityTombstoneService;

  private final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeleteDatabaseAskerAction.class);
  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  public void setup() {
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  public void tearDown() {
    logger.detachAppender(logAppender);
  }

  @Test
  public void execute_Should_returnEmptyList_When_deletionOfUserIsSuccessful() {
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(new User(), emptyList());

    this.deleteDatabaseAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    verify(this.userRepository, times(1)).delete(any());
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(false));
  }

  @Test
  public void execute_Should_returnExpectedWorkflowErrorAndLogError_When_deletionOfUserFails() {
    doThrow(new RuntimeException()).when(this.userRepository).delete(any());
    User user = new User();
    user.setUserId("user id");
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    this.deleteDatabaseAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is("user id"));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete user"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(true));
  }

  @Test
  public void execute_Should_deleteChatMembershipsAndMobileTokensBeforeUser_When_askerIsDeleted() {
    var user = new User();
    user.setUserId("user id");
    var userChat = new UserChat();
    var mobileToken = new UserMobileToken();
    when(this.userChatRepository.findByUser(user)).thenReturn(List.of(userChat));
    when(this.userMobileTokenRepository.findByUser(user)).thenReturn(List.of(mobileToken));
    var workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    this.deleteDatabaseAskerAction.execute(workflowDTO);

    var inOrder =
        inOrder(this.userChatRepository, this.userMobileTokenRepository, this.userRepository);
    inOrder.verify(this.userChatRepository).deleteAll(List.of(userChat));
    inOrder.verify(this.userMobileTokenRepository).deleteAll(List.of(mobileToken));
    inOrder.verify(this.userRepository).delete(user);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  public void execute_Should_reportWorkflowError_When_chatMembershipDeletionFails() {
    var user = new User();
    user.setUserId("user id");
    doThrow(new RuntimeException()).when(this.userChatRepository).findByUser(user);
    var workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    this.deleteDatabaseAskerAction.execute(workflowDTO);

    var workflowErrors = workflowDTO.getDeletionWorkflowErrors();
    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getReason(), is("Could not delete user chat memberships"));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
  }

  /**
   * Drafts and the notification feed are keyed by the user and hold counselling content that is not
   * end-to-end encrypted. Deleting the account row while they are still there would orphan them
   * beyond any retry, so the row has to survive a failed cleanup (#983, KDG epic #1010).
   */
  @Test
  public void execute_Should_keepUserRow_When_unencryptedContentCleanupFailed() {
    var user = new User();
    user.setUserId("user id");
    var workflowDTO =
        new AskerDeletionWorkflowDTO(
            user,
            new ArrayList<>(
                List.of(
                    DeletionWorkflowError.builder()
                        .deletionSourceType(ASKER)
                        .deletionTargetType(USER_CONTENT)
                        .identifier("user id")
                        .reason("Could not delete draft messages")
                        .build())));

    this.deleteDatabaseAskerAction.execute(workflowDTO);

    verify(this.userRepository, never()).delete(any());
    verify(this.identityTombstoneService, never()).recordDeletedUser(any());
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
  }
}
