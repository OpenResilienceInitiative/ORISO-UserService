package de.caritas.cob.userservice.api.service.sessionlist;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_SESSION_RESPONSE_DTO_LIST;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_SESSION_RESPONSE_DTO_LIST_WITH_ENCRYPTED_CHAT_MESSAGE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_SESSION_RESPONSE_DTO_WITH_ENCRYPTED_CHAT_MESSAGE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.COUNT_10;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.OFFSET_0;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_STATUS_IN_PROGRESS;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_STATUS_NEW;
import static java.util.Objects.nonNull;
import static org.jsoup.helper.Validate.fail;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.container.SessionListQueryParameter;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.session.SessionFilter;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.session.SessionSupervisionMarkerService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantSessionListServiceTest {

  @InjectMocks private ConsultantSessionListService consultantSessionListService;
  @Mock private SessionService sessionService;
  @Mock private ChatService chatService;
  @Mock private ConsultantSessionEnricher consultantSessionEnricher;
  @Mock private ConsultantChatEnricher consultantChatEnricher;
  @Mock private SessionSupervisionMarkerService supervisionMarkerService;

  @Test
  void
      retrieveSessionsForAuthenticatedConsultant_Should_ReturnOnlySessions_WhenQueryParameterSessionStatusIsNew() {

    when(sessionService.getRegisteredEnquiriesForConsultant(Mockito.any()))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);
    when(this.consultantSessionEnricher.updateRequiredConsultantSessionValues(
            eq(CONSULTANT_SESSION_RESPONSE_DTO_LIST)))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);

    List<ConsultantSessionResponseDTO> result =
        consultantSessionListService.retrieveSessionsForAuthenticatedConsultant(
            CONSULTANT, createStandardSessionListQueryParameterObject(SESSION_STATUS_NEW));

    assertFalse(result.isEmpty());
    assertEquals(CONSULTANT_SESSION_RESPONSE_DTO_LIST.size(), result.size());
    for (ConsultantSessionResponseDTO consultantSessionResponseDTO : result) {
      assertNull(consultantSessionResponseDTO.getChat());
      assertNotNull(consultantSessionResponseDTO.getSession());
    }
    verify(chatService, never()).getChatsForConsultant(CONSULTANT);
  }

  @Test
  void retrieveSessionsForAuthenticatedConsultant_Should_AddTheSupervisionMarkerForTheRequester() {
    when(sessionService.getRegisteredEnquiriesForConsultant(Mockito.any()))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);
    when(this.consultantSessionEnricher.updateRequiredConsultantSessionValues(
            eq(CONSULTANT_SESSION_RESPONSE_DTO_LIST)))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);

    consultantSessionListService.retrieveSessionsForAuthenticatedConsultant(
        CONSULTANT, createStandardSessionListQueryParameterObject(SESSION_STATUS_NEW));

    verify(supervisionMarkerService).enrich(CONSULTANT_SESSION_RESPONSE_DTO_LIST, CONSULTANT);
  }

  @Test
  void retrieveSessionsForAuthenticatedConsultant_ShouldNot_SendChatsInEnquiryList() {
    when(sessionService.getRegisteredEnquiriesForConsultant(Mockito.any()))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);
    when(this.consultantSessionEnricher.updateRequiredConsultantSessionValues(
            eq(CONSULTANT_SESSION_RESPONSE_DTO_LIST)))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);

    List<ConsultantSessionResponseDTO> result =
        consultantSessionListService.retrieveSessionsForAuthenticatedConsultant(
            CONSULTANT, createStandardSessionListQueryParameterObject(SESSION_STATUS_NEW));

    assertNull(result.get(0).getChat());
    verify(chatService, never()).getChatsForConsultant(Mockito.any());
  }

  @Test
  void retrieveSessionsForAuthenticatedConsultant_Should_MergeSessionsAndChats() {

    when(this.consultantSessionEnricher.updateRequiredConsultantSessionValues(
            eq(CONSULTANT_SESSION_RESPONSE_DTO_LIST)))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);
    when(this.consultantChatEnricher.updateRequiredConsultantChatValues(
            eq(List.of(CONSULTANT_SESSION_RESPONSE_DTO_WITH_ENCRYPTED_CHAT_MESSAGE)),
            eq(CONSULTANT)))
        .thenReturn(List.of(CONSULTANT_SESSION_RESPONSE_DTO_WITH_ENCRYPTED_CHAT_MESSAGE));
    when(chatService.getChatsForConsultant(Mockito.any()))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST_WITH_ENCRYPTED_CHAT_MESSAGE);
    when(sessionService.getActiveAndDoneSessionsForConsultant(Mockito.any()))
        .thenReturn(CONSULTANT_SESSION_RESPONSE_DTO_LIST);

    List<ConsultantSessionResponseDTO> result =
        consultantSessionListService.retrieveSessionsForAuthenticatedConsultant(
            CONSULTANT, createStandardSessionListQueryParameterObject(SESSION_STATUS_IN_PROGRESS));

    assertNotNull(result);
    assertEquals(
        result.size(),
        CONSULTANT_SESSION_RESPONSE_DTO_LIST_WITH_ENCRYPTED_CHAT_MESSAGE.size()
            + CONSULTANT_SESSION_RESPONSE_DTO_LIST.size());

    for (ConsultantSessionResponseDTO dto :
        CONSULTANT_SESSION_RESPONSE_DTO_LIST_WITH_ENCRYPTED_CHAT_MESSAGE) {
      boolean containsChat = false;
      for (ConsultantSessionResponseDTO chat : result) {
        if (nonNull(dto.getChat()) && dto.getChat().equals(chat.getChat())) {
          containsChat = true;
          break;
        }
      }
      if (!containsChat) {
        fail("ResponseList does not contain all expected chats");
      }
    }

    for (ConsultantSessionResponseDTO dto : CONSULTANT_SESSION_RESPONSE_DTO_LIST) {
      boolean containsSession = false;
      for (ConsultantSessionResponseDTO session : result) {
        if (nonNull(dto.getSession()) && dto.getSession().equals(session.getSession())) {
          containsSession = true;
          break;
        }
      }
      if (!containsSession) {
        fail("ResponseList does not contain all expected sessions");
      }
    }
  }

  @Test
  void
      retrieveSessionsForAuthenticatedConsultant_Should_returnEmptyList_When_SessionStatusIsInitial() {
    SessionListQueryParameter sessionListQueryParameter =
        createStandardSessionListQueryParameterObject(0);

    List<ConsultantSessionResponseDTO> result =
        consultantSessionListService.retrieveTeamSessionsForAuthenticatedConsultant(
            CONSULTANT, sessionListQueryParameter);

    assertEquals(0, result.size());
  }

  private SessionListQueryParameter createStandardSessionListQueryParameterObject(
      int sessionStatus) {
    return SessionListQueryParameter.builder()
        .sessionStatus(sessionStatus)
        .offset(OFFSET_0)
        .count(COUNT_10)
        .sessionFilter(SessionFilter.ALL)
        .build();
  }
}
