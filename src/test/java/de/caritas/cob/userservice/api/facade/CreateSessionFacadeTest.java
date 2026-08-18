package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.ExceptionConstants.INTERNAL_SERVER_ERROR_EXCEPTION;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_DTO_U25;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_NAME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CITY;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_SUCHT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.MESSAGE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.POSTCODE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_LIST;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_WITHOUT_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_SUCHT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.model.NewSessionValidationConstraint;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.DirectSessionMatrixRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateSessionFacadeTest {

  @InjectMocks private CreateSessionFacade createSessionFacade;
  @Mock private SessionService sessionService;
  @Mock private AgencyVerifier agencyVerifier;
  @Mock private SessionDataService sessionDataService;
  @Mock private RollbackFacade rollbackFacade;
  @Mock private UserAccountService userAccountProvider;

  @Mock private ConsultantAgencyRepository consultantAgencyRepository;

  @Mock private AgencyPreAssignmentRoomService agencyPreAssignmentRoomService;

  @Mock private DirectSessionMatrixRoomService directSessionMatrixRoomService;

  private static final Long TOPIC_ID_SERVED_BY_AGENCY = 3L;
  private static final Long MAIN_TOPIC_ID_NOT_SERVED_BY_AGENCY = 1L;

  List<NewSessionValidationConstraint> validationConstraints =
      Lists.newArrayList(NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE);

  private LogbackCaptor logCaptor;

  @BeforeEach
  public void setup() {
    logCaptor = LogbackCaptor.attach(LogService.class);
  }

  @AfterEach
  public void tearDown() {
    logCaptor.detach();
  }

  /** Method: createUserSession */
  @Test
  public void createUserSession_Should_ReturnConflict_When_AlreadyRegisteredToConsultingType() {
    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> {
          when(sessionService.getSessionsForUserByConsultingTypeId(any(), anyInt()))
              .thenReturn(SESSION_LIST);
          createSessionFacade.createUserSession(
              USER_DTO_SUCHT, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);

          verify(sessionService, times(0)).saveSession(any());
        });
  }

  @Test
  public void
      createUserSession_Should_ReturnInternalServerErrorAndRollbackUserAccount_When_SessionCouldNotBeSaved() {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(AGENCY_DTO_U25);
          when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
              .thenThrow(new InternalServerErrorException(MESSAGE));

          createSessionFacade.createUserSession(
              USER_DTO_SUCHT, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);

          assertThat(logCaptor.hasErrorLog(), is(true));
          verify(rollbackFacade, times(1)).rollBackUserAccount(any());
        });
  }

  @Test
  public void
      createUserSession_Should_ReturnInternalServerErrorAndRollbackUserAccount_When_SessionDataCouldNotBeSaved() {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(AGENCY_DTO_U25);
          when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
              .thenThrow(new InternalServerErrorException(MESSAGE));
          doThrow(INTERNAL_SERVER_ERROR_EXCEPTION)
              .when(sessionDataService)
              .saveSessionData(any(Session.class), any());

          createSessionFacade.createUserSession(
              USER_DTO_SUCHT, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);

          assertThat(logCaptor.hasErrorLog(), is(true));
          verify(sessionService, times(1)).deleteSession(any());
          verify(rollbackFacade, times(1)).rollBackUserAccount(any());
        });
  }

  @Test
  public void
      createUserSession_Should_NotRollbackUserAccount_When_SessionWasPersistedBeforeSessionDataFailure() {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(AGENCY_DTO_U25);
          when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
              .thenReturn(SESSION_WITHOUT_CONSULTANT);
          doThrow(INTERNAL_SERVER_ERROR_EXCEPTION)
              .when(sessionDataService)
              .saveSessionData(any(Session.class), any());

          createSessionFacade.createUserSession(
              USER_DTO_SUCHT, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);

          verify(rollbackFacade, never()).rollBackUserAccount(any());
        });
  }

  @Test
  public void
      createUserSession_Should_ReturnBadRequest_When_AgencyForConsultingTypeCouldNotBeFound() {
    assertThrows(
        BadRequestException.class,
        () -> {
          when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(null);

          createSessionFacade.createUserSession(
              USER_DTO_SUCHT, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);
        });
  }

  @Test
  public void createUserSession_Should_ReturnSessionId_OnSuccess() {

    when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(AGENCY_DTO_U25);
    when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
        .thenReturn(SESSION_WITHOUT_CONSULTANT);

    Long result =
        createSessionFacade.createUserSession(
            USER_DTO_SUCHT, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);

    assertEquals(SESSION_WITHOUT_CONSULTANT.getId(), result);
  }

  @Test
  public void createUserSession_Should_CreateSessionData() {

    when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(AGENCY_DTO_U25);
    when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
        .thenReturn(SESSION_WITHOUT_CONSULTANT);

    Long result =
        createSessionFacade.createUserSession(
            USER_DTO_SUCHT, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);

    assertEquals(SESSION_WITHOUT_CONSULTANT.getId(), result);
    verify(sessionDataService, times(1)).saveSessionData(any(Session.class), any());
  }

  @Test
  public void
      createDirectUserSession_Should_returnConflictWithExistingSession_When_userHasAlreadyASessionWithConsultantInConsultingType() {
    var session = new EasyRandom().nextObject(Session.class);
    when(sessionService.findSessionByConsultantAndUserAndConsultingType(any(), any(), any()))
        .thenReturn(Optional.of(session));
    var consultingTypeResponseDTO = new ExtendedConsultingTypeResponseDTO();
    consultingTypeResponseDTO.id(session.getConsultingTypeId());

    var result =
        createSessionFacade.createDirectUserSession(null, null, null, consultingTypeResponseDTO);

    assertThat(result.getStatus(), is(HttpStatus.CONFLICT));
    assertThat(result.getSessionId(), is(session.getId()));
    assertThat(result.getMatrixRoomId(), is(session.getMatrixRoomId()));
  }

  @Test
  public void
      createDirectUserSession_Should_returnCreatedWithNewSession_When_userConsultantRelationIsNew() {
    var agencyDTO = new EasyRandom().nextObject(AgencyDTO.class);
    var session = new EasyRandom().nextObject(Session.class);
    when(agencyVerifier.getVerifiedAgency(anyLong(), anyInt())).thenReturn(agencyDTO);
    when(sessionService.findSessionByConsultantAndUserAndConsultingType(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(sessionService.initializeDirectSession(any(), any(), any(), anyBoolean()))
        .thenReturn(session);
    when(sessionService.saveSession(session)).thenReturn(session);

    var result =
        createSessionFacade.createDirectUserSession(
            null, mock(UserDTO.class), null, mock(ExtendedConsultingTypeResponseDTO.class));

    assertThat(result.getStatus(), is(HttpStatus.CREATED));
    assertThat(result.getSessionId(), is(session.getId()));
  }

  @Test
  public void
      createDirectUserSession_Should_returnCreatedWithNewSession_When_userConsultantRelationIsWithOtherConsultingType() {
    var agencyDTO = new EasyRandom().nextObject(AgencyDTO.class);
    var session = new EasyRandom().nextObject(Session.class);
    when(agencyVerifier.getVerifiedAgency(anyLong(), anyInt())).thenReturn(agencyDTO);
    when(sessionService.findSessionByConsultantAndUserAndConsultingType(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(sessionService.initializeDirectSession(any(), any(), any(), anyBoolean()))
        .thenReturn(session);
    when(sessionService.saveSession(session)).thenReturn(session);
    var consultingTypeResponseDTO = new ExtendedConsultingTypeResponseDTO();
    consultingTypeResponseDTO.id(session.getConsultingTypeId() + 1);

    var result =
        createSessionFacade.createDirectUserSession(
            null, mock(UserDTO.class), null, consultingTypeResponseDTO);

    assertThat(result.getStatus(), is(HttpStatus.CREATED));
    assertThat(result.getSessionId(), is(session.getId()));
  }

  /**
   * ORISO-Frontend#1143: an advice seeker must not be able to open an enquiry at a counselling
   * centre that does not serve the selected topic. The agency id and the main topic id arrive as
   * two independent request fields, so the pairing has to be verified server side.
   */
  @Test
  void
      createUserSession_Should_ThrowBadRequestAndNotPersistSession_When_AgencyDoesNotServeMainTopic() {
    var userDto = userDtoWithMainTopic(MAIN_TOPIC_ID_NOT_SERVED_BY_AGENCY);
    when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0))
        .thenReturn(agencyServingTopics(TOPIC_ID_SERVED_BY_AGENCY));

    assertThrows(
        BadRequestException.class,
        () ->
            createSessionFacade.createUserSession(
                userDto, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints));

    verify(sessionService, never()).initializeSession(any(), any(), any(Boolean.class));
    verify(sessionService, never()).saveSession(any());
  }

  @Test
  void createUserSession_Should_CreateSession_When_AgencyServesMainTopic() {
    var userDto = userDtoWithMainTopic(TOPIC_ID_SERVED_BY_AGENCY);
    when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0))
        .thenReturn(
            agencyServingTopics(TOPIC_ID_SERVED_BY_AGENCY, MAIN_TOPIC_ID_NOT_SERVED_BY_AGENCY));
    when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
        .thenReturn(SESSION_WITHOUT_CONSULTANT);

    var result =
        createSessionFacade.createUserSession(
            userDto, USER, CONSULTING_TYPE_SETTINGS_SUCHT, validationConstraints);

    assertEquals(SESSION_WITHOUT_CONSULTANT.getId(), result);
  }

  @Test
  void createUserSession_Should_CreateSession_When_RegistrationCarriesNoMainTopic() {
    when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0))
        .thenReturn(agencyServingTopics(TOPIC_ID_SERVED_BY_AGENCY));
    when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
        .thenReturn(SESSION_WITHOUT_CONSULTANT);

    var result =
        createSessionFacade.createUserSession(
            userDtoWithMainTopic(null),
            USER,
            CONSULTING_TYPE_SETTINGS_SUCHT,
            validationConstraints);

    assertEquals(SESSION_WITHOUT_CONSULTANT.getId(), result);
  }

  /**
   * The agency lookup can degrade to an unverified stub (see obtainVerifiedAgency); that stub knows
   * no topics. Registration must keep working there instead of failing closed on missing data.
   */
  @Test
  void createUserSession_Should_CreateSession_When_AgencyTopicsAreUnknown() {
    var agencyWithoutTopics = agencyServingTopics();
    agencyWithoutTopics.setTopicIds(null);
    when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(agencyWithoutTopics);
    when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
        .thenReturn(SESSION_WITHOUT_CONSULTANT);

    var result =
        createSessionFacade.createUserSession(
            userDtoWithMainTopic(MAIN_TOPIC_ID_NOT_SERVED_BY_AGENCY),
            USER,
            CONSULTING_TYPE_SETTINGS_SUCHT,
            validationConstraints);

    assertEquals(SESSION_WITHOUT_CONSULTANT.getId(), result);
  }

  /**
   * An empty topic list is the agency reporting nothing, not the agency declaring that it serves
   * nothing - a deployment without topics in registration and the degraded lookup both look like
   * this. Agency search inner-joins agency_topic, so an agency without topic rows can never be
   * offered to an advice seeker anyway; rejecting here would only break registration.
   */
  @Test
  void createUserSession_Should_CreateSession_When_AgencyReportsNoTopics() {
    when(agencyVerifier.getVerifiedAgency(AGENCY_ID, 0)).thenReturn(agencyServingTopics());
    when(sessionService.initializeSession(any(), any(), any(Boolean.class)))
        .thenReturn(SESSION_WITHOUT_CONSULTANT);

    var result =
        createSessionFacade.createUserSession(
            userDtoWithMainTopic(MAIN_TOPIC_ID_NOT_SERVED_BY_AGENCY),
            USER,
            CONSULTING_TYPE_SETTINGS_SUCHT,
            validationConstraints);

    assertEquals(SESSION_WITHOUT_CONSULTANT.getId(), result);
  }

  private static UserDTO userDtoWithMainTopic(Long mainTopicId) {
    var userDto = new UserDTO();
    userDto.setUsername(USER_DTO_SUCHT.getUsername());
    userDto.setPostcode(USER_DTO_SUCHT.getPostcode());
    userDto.setAgencyId(AGENCY_ID);
    userDto.setPassword(USER_DTO_SUCHT.getPassword());
    userDto.setTermsAccepted("true");
    userDto.setConsultingType("0");
    userDto.setMainTopicId(mainTopicId);
    return userDto;
  }

  private static AgencyDTO agencyServingTopics(Long... topicIds) {
    return new AgencyDTO()
        .id(AGENCY_ID)
        .name(AGENCY_NAME)
        .postcode(POSTCODE)
        .city(CITY)
        .teamAgency(false)
        .offline(false)
        .consultingType(0)
        .topicIds(Lists.newArrayList(topicIds));
  }
}
