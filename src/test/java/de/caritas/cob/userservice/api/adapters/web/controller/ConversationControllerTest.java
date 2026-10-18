package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AnonymousEnquiry;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConversationDtoMapper;
import de.caritas.cob.userservice.api.conversation.facade.AcceptAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.conversation.facade.FinishAnonymousConversationFacade;
import de.caritas.cob.userservice.api.conversation.model.ConversationListType;
import de.caritas.cob.userservice.api.conversation.service.ConversationListResolver;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

  @Mock private ConversationListResolver conversationListResolver;
  @Mock private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  @Mock private AcceptAnonymousEnquiryFacade acceptAnonymousEnquiryFacade;
  @Mock private FinishAnonymousConversationFacade finishAnonymousConversationFacade;
  @Mock private ConversationDtoMapper mapper;
  @Mock private Messaging messenger;
  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;
  @Mock private AuthenticatedUser authenticatedUser;

  private ConversationController controller;

  @BeforeEach
  void setUp() {
    controller =
        new ConversationController(
            conversationListResolver,
            createAnonymousEnquiryFacade,
            acceptAnonymousEnquiryFacade,
            finishAnonymousConversationFacade,
            mapper,
            messenger,
            topicConsultantRoutingService,
            authenticatedUser);
  }

  @Test
  void getArchivedSessions_paginationVariants_delegateWithArchivedType() {
    // Business reason: archived sessions list must respect pagination values from UI requests.
    var result = new ConsultantSessionListResponseDTO();
    when(conversationListResolver.resolveConversations(
            0, 10, ConversationListType.ARCHIVED_SESSION))
        .thenReturn(result);

    var response = controller.getArchivedSessions(0, 10);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(result, response.getBody());
    verify(conversationListResolver)
        .resolveConversations(0, 10, ConversationListType.ARCHIVED_SESSION);
  }

  @Test
  void getArchivedSessions_emptyResult_returnsNonNullBody() {
    // Business reason: clients expect stable object payloads even when no archived sessions exist.
    var empty = new ConsultantSessionListResponseDTO();
    when(conversationListResolver.resolveConversations(1, 5, ConversationListType.ARCHIVED_SESSION))
        .thenReturn(empty);

    var response = controller.getArchivedSessions(1, 5);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
  }

  @Test
  void getArchivedTeamSessions_parityWithArchivedSessions_delegatesCorrectType() {
    // Business reason: archived team-session query must route to team-specific resolver path.
    var result = new ConsultantSessionListResponseDTO();
    when(conversationListResolver.resolveConversations(
            0, 10, ConversationListType.ARCHIVED_TEAM_SESSION))
        .thenReturn(result);

    var response = controller.getArchivedTeamSessions(0, 10);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(conversationListResolver)
        .resolveConversations(0, 10, ConversationListType.ARCHIVED_TEAM_SESSION);
  }

  @Test
  void getAnonymousEnquiryDetails_topicAvailabilityThrows_fallsBackToZero() {
    // Business reason: availability outages must not block anonymous enquiry detail polling.
    Map<String, Object> sessionMap = Map.of("status", "NEW");
    var enquiry = new AnonymousEnquiry();
    when(messenger.findSession(99L)).thenReturn(Optional.of(sessionMap));
    when(authenticatedUser.getUserId()).thenReturn("asker-1");
    when(mapper.adviceSeekerIdOf(sessionMap)).thenReturn("asker-1");
    when(mapper.consultingTypeIdOf(sessionMap)).thenReturn(3);
    when(mapper.mainTopicIdOf(sessionMap)).thenReturn(7L);
    when(mapper.agencyIdOf(sessionMap)).thenReturn(11L);
    when(mapper.createDateOf(sessionMap)).thenReturn(LocalDateTime.now());
    when(topicConsultantRoutingService.findAvailableConsultantIds(7L))
        .thenThrow(new RuntimeException("presence down"));
    when(messenger.countPendingEnquiriesAheadOf(11L, 3, 7L, mapper.createDateOf(sessionMap)))
        .thenReturn(2L);
    when(mapper.anonymousEnquiryOf(sessionMap, 0, 2L)).thenReturn(enquiry);

    var response = controller.getAnonymousEnquiryDetails(99L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(enquiry, response.getBody());
    verify(mapper).anonymousEnquiryOf(sessionMap, 0, 2L);
  }

  @Test
  void getAnonymousEnquiryDetails_nullTopicAndNullConsultingType_handlesGracefully() {
    // Business reason: sessions without topic/type metadata should still return details safely.
    Map<String, Object> sessionMap = Map.of("status", "NEW");
    var enquiry = new AnonymousEnquiry();
    LocalDateTime createdAt = LocalDateTime.now();
    when(messenger.findSession(100L)).thenReturn(Optional.of(sessionMap));
    when(authenticatedUser.getUserId()).thenReturn("asker-2");
    when(mapper.adviceSeekerIdOf(sessionMap)).thenReturn("asker-2");
    when(mapper.consultingTypeIdOf(sessionMap)).thenReturn(null);
    when(mapper.mainTopicIdOf(sessionMap)).thenReturn(null);
    when(mapper.agencyIdOf(sessionMap)).thenReturn(12L);
    when(mapper.createDateOf(sessionMap)).thenReturn(createdAt);
    when(messenger.countPendingEnquiriesAheadOf(12L, null, null, createdAt)).thenReturn(0L);
    when(mapper.anonymousEnquiryOf(sessionMap, 0, 0L)).thenReturn(enquiry);

    var response = controller.getAnonymousEnquiryDetails(100L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(enquiry, response.getBody());
    verify(topicConsultantRoutingService, never()).findAvailableConsultantIds(any());
  }

  @Test
  void getAnonymousEnquiryDetails_keepsTheQueueEntryAlive() {
    // Business reason: this poll is the only sign of life a waiting live-chat guest gives. Without
    // it the entry ages out of the queue after live.chat.queue.activePeriodMinutes (#1404).
    Map<String, Object> sessionMap = Map.of("status", "NEW");
    when(messenger.findSession(101L)).thenReturn(Optional.of(sessionMap));
    when(authenticatedUser.getUserId()).thenReturn("asker-3");
    when(mapper.adviceSeekerIdOf(sessionMap)).thenReturn("asker-3");
    when(mapper.createDateOf(sessionMap)).thenReturn(LocalDateTime.now());

    controller.getAnonymousEnquiryDetails(101L);

    verify(messenger).touchLiveChatQueueHeartbeat(101L);
  }

  @Test
  void getAnonymousEnquiryDetails_doesNotKeepTheQueueEntryAliveForSomebodyElse() {
    // Business reason: the heartbeat must sit behind the ownership check, so a foreign caller can
    // neither read the enquiry nor hold its queue entry open.
    Map<String, Object> sessionMap = Map.of("status", "NEW");
    when(messenger.findSession(102L)).thenReturn(Optional.of(sessionMap));
    when(authenticatedUser.getUserId()).thenReturn("somebody-else");
    when(mapper.adviceSeekerIdOf(sessionMap)).thenReturn("asker-3");

    assertThrows(ForbiddenException.class, () -> controller.getAnonymousEnquiryDetails(102L));

    verify(messenger, never()).touchLiveChatQueueHeartbeat(any());
  }
}
