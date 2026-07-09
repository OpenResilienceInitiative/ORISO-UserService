package de.caritas.cob.userservice.api.facade.userdata;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ABSENCE_DTO;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ABSENCE_DTO_WITH_EMPTY_MESSAGE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ABSENCE_DTO_WITH_HTML_AND_JS;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ABSENCE_DTO_WITH_NULL_MESSAGE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.GROUP_SESSION_RESPONSE_DTO;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.MESSAGE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_SESSION_RESPONSE_DTO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConsultantDataFacadeTest {

  @InjectMocks private ConsultantDataFacade consultantDataFacade;
  @Mock private ConsultantService consultantService;
  @Mock private AccountManager accountManager;
  @Mock private UserDtoMapper userDtoMapper;

  @Test
  public void updateConsultantAbsent_Should_UpdateAbsenceMessageAndIsAbsence() {
    when(consultantService.saveConsultant(Mockito.any(Consultant.class))).thenReturn(CONSULTANT);

    Consultant consultant = consultantDataFacade.updateConsultantAbsent(CONSULTANT, ABSENCE_DTO);

    assertEquals(consultant.getAbsenceMessage(), ABSENCE_DTO.getMessage());
    assertEquals(consultant.isAbsent(), ABSENCE_DTO.getAbsent());
  }

  @Test
  public void
      saveEnquiryMessageAndRocketChatGroupId_Should_RemoveHtmlCodeAndJsFromMessageForXssProtection() {
    when(consultantService.saveConsultant(Mockito.any(Consultant.class))).thenReturn(CONSULTANT);

    Consultant consultant =
        consultantDataFacade.updateConsultantAbsent(CONSULTANT, ABSENCE_DTO_WITH_HTML_AND_JS);

    assertEquals(consultant.isAbsent(), ABSENCE_DTO_WITH_HTML_AND_JS.getAbsent());
    assertNotEquals(consultant.getAbsenceMessage(), ABSENCE_DTO_WITH_HTML_AND_JS.getMessage());
    assertEquals(MESSAGE, consultant.getAbsenceMessage());
  }

  @Test
  public void
      updateConsultantAbsent_Should_SetAbsenceMessageToNull_WhenAbsenceMessageFromDtoIsEmpty() {
    consultantDataFacade.updateConsultantAbsent(CONSULTANT, ABSENCE_DTO_WITH_EMPTY_MESSAGE);

    ArgumentCaptor<Consultant> captor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(captor.capture());
    assertNull(captor.getValue().getAbsenceMessage());
  }

  @Test
  public void
      updateConsultantAbsent_Should_SetAbsenceMessageToNull_WhenAbsenceMessageFromDtoIsNull() {
    consultantDataFacade.updateConsultantAbsent(CONSULTANT, ABSENCE_DTO_WITH_NULL_MESSAGE);

    ArgumentCaptor<Consultant> captor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(captor.capture());
    assertNull(captor.getValue().getAbsenceMessage());
  }

  @Test
  public void addConsultantDisplayNameToSessionList_GroupSession_Should_AddConsultantDisplayName() {

    List<GroupSessionResponseDTO> sessions = new ArrayList<>();
    sessions.add(GROUP_SESSION_RESPONSE_DTO);

    GroupSessionListResponseDTO response = new GroupSessionListResponseDTO().sessions(sessions);

    var userName = RandomStringUtils.randomAlphanumeric(16);
    sessions.get(0).getConsultant().setUsername(userName);
    var displayName = RandomStringUtils.randomAlphanumeric(16);

    Map<String, Object> map = Map.of("displayName", displayName);
    when(userDtoMapper.displayNameOf(map)).thenReturn(displayName);
    when(accountManager.findConsultantByUsername(userName)).thenReturn(Optional.of(map));

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    assertEquals(displayName, response.getSessions().get(0).getConsultant().getDisplayName());
  }

  @Test
  public void addConsultantDisplayNameToSessionList_UserSession_Should_AddConsultantDisplayName() {

    List<UserSessionResponseDTO> sessions = new ArrayList<>();
    sessions.add(USER_SESSION_RESPONSE_DTO);

    UserSessionListResponseDTO response = new UserSessionListResponseDTO().sessions(sessions);

    var userName = RandomStringUtils.randomAlphanumeric(16);
    sessions.get(0).getConsultant().setUsername(userName);
    var displayName = RandomStringUtils.randomAlphanumeric(16);

    Map<String, Object> map = Map.of("displayName", displayName);
    when(userDtoMapper.displayNameOf(map)).thenReturn(displayName);
    when(accountManager.findConsultantByUsername(userName)).thenReturn(Optional.of(map));

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    assertEquals(displayName, response.getSessions().get(0).getConsultant().getDisplayName());
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-03
  // ---------------------------------------------------------------------------

  // --- addConsultantDisplayNameToSessionList(GroupSessionListResponseDTO) ---

  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_ReturnEarly_When_DtoIsNull() {
    consultantDataFacade.addConsultantDisplayNameToSessionList((GroupSessionListResponseDTO) null);

    Mockito.verifyNoInteractions(accountManager);
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_ReturnEarly_When_SessionsIsNull() {
    GroupSessionListResponseDTO response = new GroupSessionListResponseDTO();

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    Mockito.verifyNoInteractions(accountManager);
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_SkipSession_When_ConsultantIsNull() {
    de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    GroupSessionListResponseDTO response =
        new GroupSessionListResponseDTO().sessions(List.of(session));

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    Mockito.verifyNoInteractions(accountManager);
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_SkipSession_When_ConsultantUsernameIsNull() {
    de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO();
    session.setConsultant(consultant);
    GroupSessionListResponseDTO response =
        new GroupSessionListResponseDTO().sessions(List.of(session));

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    Mockito.verifyNoInteractions(accountManager);
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_NotSetDisplayName_When_AccountManagerReturnsEmpty() {
    de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO();
    consultant.setUsername("someuser");
    session.setConsultant(consultant);
    GroupSessionListResponseDTO response =
        new GroupSessionListResponseDTO().sessions(List.of(session));
    when(accountManager.findConsultantByUsername("someuser")).thenReturn(Optional.empty());

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    assertNull(response.getSessions().get(0).getConsultant().getDisplayName());
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_ContinueProcessing_When_AccountManagerThrows() {
    de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO();
    consultant.setUsername("someuser");
    session.setConsultant(consultant);
    GroupSessionListResponseDTO response =
        new GroupSessionListResponseDTO().sessions(List.of(session));
    when(accountManager.findConsultantByUsername("someuser"))
        .thenThrow(new RuntimeException("keycloak down"));

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    assertNull(response.getSessions().get(0).getConsultant().getDisplayName());
  }

  // --- addConsultantDisplayNameToSessionList(UserSessionListResponseDTO) ---

  @Test
  public void
      addConsultantDisplayNameToSessionList_UserSession_Should_ReturnEarly_When_DtoIsNull() {
    consultantDataFacade.addConsultantDisplayNameToSessionList((UserSessionListResponseDTO) null);

    Mockito.verifyNoInteractions(accountManager);
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_UserSession_Should_ReturnEarly_When_SessionsIsNull() {
    UserSessionListResponseDTO response = new UserSessionListResponseDTO();

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    Mockito.verifyNoInteractions(accountManager);
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_UserSession_Should_ContinueProcessing_When_AccountManagerThrows() {
    de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO();
    de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForUserDTO consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForUserDTO();
    consultant.setUsername("someuser");
    session.setConsultant(consultant);
    UserSessionListResponseDTO response =
        new UserSessionListResponseDTO().sessions(List.of(session));
    when(accountManager.findConsultantByUsername("someuser"))
        .thenThrow(new RuntimeException("keycloak down"));

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    assertNull(response.getSessions().get(0).getConsultant().getDisplayName());
  }

  // --- addConsultantDisplayNameToSessionList(List<ConsultantSessionResponseDTO>) ---

  @Test
  public void
      addConsultantDisplayNameToSessionList_ConsultantSession_Should_SkipSession_When_ConsultantIsNull() {
    de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO();

    consultantDataFacade.addConsultantDisplayNameToSessionList(List.of(session));

    Mockito.verifyNoInteractions(accountManager);
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_ConsultantSession_Should_NotSetDisplayName_When_AccountManagerReturnsEmpty() {
    de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO();
    de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO();
    consultant.setUsername("chatowner");
    session.setConsultant(consultant);
    when(accountManager.findConsultantByUsername("chatowner")).thenReturn(Optional.empty());

    consultantDataFacade.addConsultantDisplayNameToSessionList(List.of(session));

    assertNull(session.getConsultant().getDisplayName());
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_ConsultantSession_Should_ContinueProcessing_When_AccountManagerThrows() {
    de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO session =
        new de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO();
    de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO();
    consultant.setUsername("chatowner");
    session.setConsultant(consultant);
    when(accountManager.findConsultantByUsername("chatowner"))
        .thenThrow(new RuntimeException("service unavailable"));

    consultantDataFacade.addConsultantDisplayNameToSessionList(List.of(session));

    assertNull(session.getConsultant().getDisplayName());
  }
}
