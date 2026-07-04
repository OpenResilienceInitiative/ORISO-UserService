package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.MissingConsultingTypeException;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.NewSessionValidationConstraint;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class CreateNewSessionFacadeTest {

  private static final String CONSULTANT_ID = "consultant-1";
  private static final String CONSULTING_TYPE = "0";
  private static final Long AGENCY_ID = 15L;
  private static final String POSTCODE = "79098";

  private final EasyRandom easyRandom = new EasyRandom();

  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private CreateUserChatRelationFacade createUserChatRelationFacade;
  @Mock private CreateSessionFacade createSessionFacade;
  @Mock private StatisticsService statisticsService;
  @Mock private User user;
  @Mock private RocketChatCredentials rocketChatCredentials;

  @InjectMocks private CreateNewSessionFacade createNewSessionFacade;

  private UserDTO userDto(String consultantId) {
    var userDTO = new UserDTO();
    userDTO.setConsultingType(CONSULTING_TYPE);
    userDTO.setAgencyId(AGENCY_ID);
    userDTO.setPostcode(POSTCODE);
    userDTO.setConsultantId(consultantId);
    return userDTO;
  }

  private ExtendedConsultingTypeResponseDTO consultingTypeWithGroupChat(boolean isGroupChat) {
    var dto = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    dto.getGroupChat().setIsGroupChat(isGroupChat);
    return dto;
  }

  private ExtendedConsultingTypeResponseDTO consultingTypeWithoutGroupChat() {
    var dto = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    dto.setGroupChat(null);
    return dto;
  }

  @Test
  void
      initializeNewSession_Should_createDirectSessionAndFireStatistics_When_consultantIdIsPresent() {
    var extended = consultingTypeWithoutGroupChat();
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(extended);
    var directResponse = new NewRegistrationResponseDto().sessionId(99L).status(HttpStatus.CREATED);
    when(createSessionFacade.createDirectUserSession(
            eq(CONSULTANT_ID), any(UserDTO.class), eq(user), eq(extended)))
        .thenReturn(directResponse);

    var result =
        createNewSessionFacade.initializeNewSession(
            userDto(CONSULTANT_ID), user, rocketChatCredentials);

    assertThat(result.getSessionId()).isEqualTo(99L);
    verify(statisticsService).fireEvent(any(AssignSessionStatisticsEvent.class));
    verify(createSessionFacade, never()).createUserSession(any(), any(), any(), anyList());
    verifyNoInteractions(createUserChatRelationFacade);
  }

  @Test
  void
      initializeNewSession_Should_initializeChatRelationAndCreateSession_When_consultingTypeIsGroupChat() {
    var extended = consultingTypeWithGroupChat(true);
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(extended);
    when(createSessionFacade.createUserSession(
            any(UserDTO.class), eq(user), eq(extended), anyList()))
        .thenReturn(77L);

    var result =
        createNewSessionFacade.initializeNewSession(userDto(null), user, rocketChatCredentials);

    assertThat(result.getSessionId()).isEqualTo(77L);
    assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);
    verify(createUserChatRelationFacade)
        .initializeUserChatAgencyRelation(any(UserDTO.class), eq(user), eq(rocketChatCredentials));
    verify(createSessionFacade)
        .createUserSession(any(UserDTO.class), eq(user), eq(extended), anyList());
    verify(statisticsService, never()).fireEvent(any());
    verify(createSessionFacade, never()).createDirectUserSession(anyString(), any(), any(), any());
  }

  @Test
  void initializeNewSession_Should_createUserSessionOnly_When_consultingTypeHasNoGroupChat() {
    var extended = consultingTypeWithoutGroupChat();
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(extended);
    when(createSessionFacade.createUserSession(
            any(UserDTO.class), eq(user), eq(extended), anyList()))
        .thenReturn(42L);

    var result =
        createNewSessionFacade.initializeNewSession(userDto(null), user, rocketChatCredentials);

    assertThat(result.getSessionId()).isEqualTo(42L);
    assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);
    verifyNoInteractions(createUserChatRelationFacade);
    verify(statisticsService, never()).fireEvent(any());
  }

  @Test
  void initializeNewSession_Should_createUserSessionOnly_When_groupChatFlagIsFalse() {
    var extended = consultingTypeWithGroupChat(false);
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(extended);
    when(createSessionFacade.createUserSession(
            any(UserDTO.class), eq(user), eq(extended), anyList()))
        .thenReturn(43L);

    var result =
        createNewSessionFacade.initializeNewSession(userDto(null), user, rocketChatCredentials);

    assertThat(result.getSessionId()).isEqualTo(43L);
    verifyNoInteractions(createUserChatRelationFacade);
  }

  @Test
  void initializeNewSession_Should_throwBadRequest_When_consultingTypeIsMissing() {
    when(consultingTypeManager.getConsultingTypeSettings(anyString()))
        .thenThrow(new MissingConsultingTypeException("missing"));

    assertThatThrownBy(
            () ->
                createNewSessionFacade.initializeNewSession(
                    userDto(null), user, rocketChatCredentials))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(createSessionFacade);
    verifyNoInteractions(createUserChatRelationFacade);
  }

  @Test
  void initializeNewSession_Should_throwBadRequest_When_consultingTypeIsIllegal() {
    when(consultingTypeManager.getConsultingTypeSettings(anyString()))
        .thenThrow(new IllegalArgumentException("not a number"));

    assertThatThrownBy(
            () ->
                createNewSessionFacade.initializeNewSession(
                    userDto(null), user, rocketChatCredentials))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(createSessionFacade);
  }

  @Test
  void initializeNewSession_Should_notLookUpConsultingType_When_extendedDtoOverloadIsUsed() {
    var extended = consultingTypeWithoutGroupChat();
    when(createSessionFacade.createUserSession(
            any(UserDTO.class), eq(user), eq(extended), anyList()))
        .thenReturn(51L);

    var result = createNewSessionFacade.initializeNewSession(userDto(null), user, extended);

    assertThat(result.getSessionId()).isEqualTo(51L);
    verifyNoInteractions(consultingTypeManager);
  }

  @Test
  void
      initializeNewSession_Should_defaultToOneSessionPerConsultingTypeConstraint_When_defaultOverloadUsed() {
    var extended = consultingTypeWithoutGroupChat();
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(extended);
    var constraintCaptor = ArgumentCaptor.forClass(List.class);
    when(createSessionFacade.createUserSession(
            any(UserDTO.class), eq(user), eq(extended), constraintCaptor.capture()))
        .thenReturn(1L);

    createNewSessionFacade.initializeNewSession(userDto(null), user, rocketChatCredentials);

    assertThat(constraintCaptor.getValue())
        .containsExactly(NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE);
  }

  @Test
  void initializeNewSession_Should_passThroughProvidedValidationConstraints() {
    var extended = consultingTypeWithoutGroupChat();
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(extended);
    var constraintCaptor = ArgumentCaptor.forClass(List.class);
    when(createSessionFacade.createUserSession(
            any(UserDTO.class), eq(user), eq(extended), constraintCaptor.capture()))
        .thenReturn(2L);
    List<NewSessionValidationConstraint> noConstraints = Lists.newArrayList();

    createNewSessionFacade.initializeNewSession(
        userDto(null), user, rocketChatCredentials, noConstraints);

    assertThat(constraintCaptor.getValue()).isEmpty();
  }

  @Test
  void
      initializeNewSession_Should_mapNewRegistrationDtoFieldsToUserDto_When_registrationIsNewRegistrationDto() {
    var extended = consultingTypeWithoutGroupChat();
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(extended);
    var userDtoCaptor = ArgumentCaptor.forClass(UserDTO.class);
    when(createSessionFacade.createUserSession(
            userDtoCaptor.capture(), eq(user), eq(extended), anyList()))
        .thenReturn(5L);

    var registration = new NewRegistrationDto();
    registration.setAgencyId(AGENCY_ID);
    registration.setPostcode(POSTCODE);
    registration.setConsultingType(CONSULTING_TYPE);
    registration.setMainTopicId(3L);
    registration.setAge("27");

    createNewSessionFacade.initializeNewSession(registration, user, rocketChatCredentials);

    var mapped = userDtoCaptor.getValue();
    assertThat(mapped.getAgencyId()).isEqualTo(AGENCY_ID);
    assertThat(mapped.getPostcode()).isEqualTo(POSTCODE);
    assertThat(mapped.getConsultingType()).isEqualTo(CONSULTING_TYPE);
    assertThat(mapped.getMainTopicId()).isEqualTo(3L);
    assertThat(mapped.getAge()).isEqualTo("27");
  }
}
