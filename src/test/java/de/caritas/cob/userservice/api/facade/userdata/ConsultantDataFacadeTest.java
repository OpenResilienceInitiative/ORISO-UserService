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
import de.caritas.cob.userservice.api.adapters.web.dto.AbsenceDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.time.LocalDate;
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

  @Mock
  private de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess membersOnlyChatAccess;

  @org.junit.jupiter.api.BeforeEach
  void readsRunDirectly() {
    org.mockito.Mockito.lenient()
        .when(
            membersOnlyChatAccess.readingAcrossTraegerOfChat(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<Object>>any()))
        .thenAnswer(
            invocation -> invocation.<java.util.function.Supplier<Object>>getArgument(1).get());
  }

  @Test
  public void updateConsultantAbsent_Should_UpdateAbsenceMessageAndIsAbsence() {
    when(consultantService.saveConsultant(Mockito.any(Consultant.class))).thenReturn(CONSULTANT);

    Consultant consultant = consultantDataFacade.updateConsultantAbsent(CONSULTANT, ABSENCE_DTO);

    assertEquals(consultant.getAbsenceMessage(), ABSENCE_DTO.getMessage());
    assertEquals(consultant.isAbsent(), ABSENCE_DTO.getAbsent());
  }

  @Test
  public void updateConsultantAbsent_Should_RemoveHtmlCodeAndJsFromMessageForXssProtection() {
    when(consultantService.saveConsultant(Mockito.any(Consultant.class))).thenReturn(CONSULTANT);

    Consultant consultant =
        consultantDataFacade.updateConsultantAbsent(CONSULTANT, ABSENCE_DTO_WITH_HTML_AND_JS);

    assertEquals(consultant.isAbsent(), ABSENCE_DTO_WITH_HTML_AND_JS.getAbsent());
    assertNotEquals(consultant.getAbsenceMessage(), ABSENCE_DTO_WITH_HTML_AND_JS.getMessage());
    assertEquals(MESSAGE, consultant.getAbsenceMessage());
  }

  @Test
  public void updateConsultantAbsent_Should_StoreThePlannedRange() {
    var consultant = new Consultant();
    var absence =
        new AbsenceDTO()
            .absent(true)
            .message("Im Urlaub")
            .start(LocalDate.of(2026, 12, 21))
            .end(LocalDate.of(2027, 1, 3));

    consultantDataFacade.updateConsultantAbsent(consultant, absence);

    ArgumentCaptor<Consultant> captor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(captor.capture());
    assertEquals(LocalDate.of(2026, 12, 21), captor.getValue().getAbsenceStart());
    assertEquals(LocalDate.of(2027, 1, 3), captor.getValue().getAbsenceEnd());
  }

  @Test
  public void updateConsultantAbsent_Should_ClearTheRange_WhenSwitchedOff() {
    var consultant = new Consultant();
    consultant.setAbsent(true);
    consultant.setAbsenceStart(LocalDate.of(2026, 12, 21));
    consultant.setAbsenceEnd(LocalDate.of(2027, 1, 3));

    consultantDataFacade.updateConsultantAbsent(
        consultant,
        new AbsenceDTO()
            .absent(false)
            .start(LocalDate.of(2026, 12, 21))
            .end(LocalDate.of(2027, 1, 3)));

    ArgumentCaptor<Consultant> captor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(captor.capture());
    assertNull(captor.getValue().getAbsenceStart());
    assertNull(captor.getValue().getAbsenceEnd());
  }

  @Test
  public void updateConsultantAbsent_Should_Reject_AnEndBeforeTheStart() {
    var absence =
        new AbsenceDTO()
            .absent(true)
            .start(LocalDate.of(2027, 1, 3))
            .end(LocalDate.of(2026, 12, 21));

    assertThrows(
        BadRequestException.class,
        () -> consultantDataFacade.updateConsultantAbsent(new Consultant(), absence));
    Mockito.verifyNoInteractions(consultantService);
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

  /**
   * FE#2494 *4: the counsellor view of a session carries only the id; the team reader's banner
   * needs the public name, so the username is resolved by id and the display name follows.
   */
  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_ResolveUsernameById_When_OnlyIdIsKnown() {
    var session = new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    var consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO();
    consultant.setId("owner-1");
    consultant.setFirstName("Real");
    consultant.setLastName("Name");
    session.setConsultant(consultant);
    var response = new GroupSessionListResponseDTO().sessions(List.of(session));
    var owner = new Consultant();
    owner.setId("owner-1");
    owner.setUsername("marge.public");
    when(consultantService.getConsultant("owner-1")).thenReturn(Optional.of(owner));
    Map<String, Object> map = Map.of("displayName", "Marge S.");
    when(accountManager.findConsultantByUsername("marge.public")).thenReturn(Optional.of(map));
    when(userDtoMapper.displayNameOf(map)).thenReturn("Marge S.");

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    assertEquals("marge.public", consultant.getUsername());
    assertEquals("Marge S.", consultant.getDisplayName());
  }

  /** CodeRabbit: rows of the same counsellor share one lookup. */
  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_ResolveEachCounsellorOnce() {
    var first = new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO();
    first.setId("owner-1");
    var second = new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO();
    second.setId("owner-1");
    var one = new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    one.setConsultant(first);
    var two = new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    two.setConsultant(second);
    var owner = new Consultant();
    owner.setUsername("marge.public");
    when(consultantService.getConsultant("owner-1")).thenReturn(Optional.of(owner));
    when(accountManager.findConsultantByUsername("marge.public")).thenReturn(Optional.empty());

    consultantDataFacade.addConsultantDisplayNameToSessionList(
        new GroupSessionListResponseDTO().sessions(List.of(one, two)));

    verify(consultantService, Mockito.times(1)).getConsultant("owner-1");
    assertEquals("marge.public", second.getUsername());
  }

  @Test
  public void
      addConsultantDisplayNameToSessionList_GroupSession_Should_LeaveNamesEmpty_When_IdIsUnknown() {
    var session = new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO();
    var consultant =
        new de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO();
    consultant.setId("gone");
    consultant.setFirstName("Real");
    consultant.setLastName("Name");
    session.setConsultant(consultant);
    var response = new GroupSessionListResponseDTO().sessions(List.of(session));
    when(consultantService.getConsultant("gone")).thenReturn(Optional.empty());

    consultantDataFacade.addConsultantDisplayNameToSessionList(response);

    assertNull(consultant.getUsername(), "never the real name");
    assertNull(consultant.getDisplayName(), "never the real name");
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
