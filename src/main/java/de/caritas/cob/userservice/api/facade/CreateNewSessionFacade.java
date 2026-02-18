package de.caritas.cob.userservice.api.facade;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserRegistrationDTO;
import de.caritas.cob.userservice.api.exception.MissingConsultingTypeException;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.NewSessionValidationConstraint;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.statisticsservice.generated.web.model.UserRole;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Facade to encapsulate the steps to initialize a new consulting type. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateNewSessionFacade {

  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull CreateUserChatRelationFacade createUserChatRelationFacade;
  private final @NonNull CreateSessionFacade createSessionFacade;
  private final @NonNull StatisticsService statisticsService;

  public NewRegistrationResponseDto initializeNewSession(
      UserRegistrationDTO userRegistrationDTO,
      User user,
      RocketChatCredentials rocketChatCredentials) {
    return initializeNewSession(
        userRegistrationDTO,
        user,
        rocketChatCredentials,
        Lists.newArrayList(NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE));
  }
  /**
   * Initializes the new consulting type settings and creates a session or a chat-agency relation
   * depending on its type. This method should be used for new consulting type registrations.
   *
   * @param userRegistrationDTO {@link UserRegistrationDTO}
   * @param user {@link User}
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return session ID of created session (if not consulting id refers to a group only consulting
   *     type)
   */
  public NewRegistrationResponseDto initializeNewSession(
      UserRegistrationDTO userRegistrationDTO,
      User user,
      RocketChatCredentials rocketChatCredentials,
      List<NewSessionValidationConstraint> validationConstraints) {
    try {
      var extendedConsultingTypeResponseDTO =
          consultingTypeManager.getConsultingTypeSettings(userRegistrationDTO.getConsultingType());

      return createSessionOrChat(
          userRegistrationDTO,
          user,
          extendedConsultingTypeResponseDTO,
          rocketChatCredentials,
          validationConstraints);
    } catch (MissingConsultingTypeException | IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage(), e);
    }
  }

  /**
   * Initializes the new consulting type settings and creates a session or a chat-agency relation
   * depending on its type. This method should be used for new user account registrations.
   *
   * @param userRegistrationDTO {@link UserRegistrationDTO}
   * @param user {@link User}
   * @param extendedConsultingTypeResponseDTO {@link ExtendedConsultingTypeResponseDTO}
   */
  public NewRegistrationResponseDto initializeNewSession(
      UserRegistrationDTO userRegistrationDTO,
      User user,
      ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO) {

    return createSessionOrChat(
        userRegistrationDTO,
        user,
        extendedConsultingTypeResponseDTO,
        null,
        Lists.newArrayList(NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE));
  }

  private NewRegistrationResponseDto createSessionOrChat(
      UserRegistrationDTO userRegistrationDTO,
      User user,
      ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO,
      RocketChatCredentials rocketChatCredentials,
      List<NewSessionValidationConstraint> validationConstraints) {

    if (isNotBlank(userRegistrationDTO.getConsultantId())) {
      NewRegistrationResponseDto newRegistrationResponseDto =
          createSessionFacade.createDirectUserSession(
              userRegistrationDTO.getConsultantId(),
              convertToUserDTO(userRegistrationDTO),
              user,
              extendedConsultingTypeResponseDTO);
      statisticsService.fireEvent(
          new AssignSessionStatisticsEvent(
              userRegistrationDTO.getConsultantId(),
              UserRole.CONSULTANT,
              newRegistrationResponseDto.getSessionId()));
      return newRegistrationResponseDto;
    }

    Long sessionId = null;

    var groupChat = extendedConsultingTypeResponseDTO.getGroupChat();
    if (nonNull(groupChat) && isTrue(groupChat.getIsGroupChat())) {
      createUserChatRelationFacade.initializeUserChatAgencyRelation(
          convertToUserDTO(userRegistrationDTO), user, rocketChatCredentials);
      // Also create a session for group chat users
      sessionId =
          createSessionFacade.createUserSession(
              convertToUserDTO(userRegistrationDTO),
              user,
              extendedConsultingTypeResponseDTO,
              validationConstraints);
    } else {
      sessionId =
          createSessionFacade.createUserSession(
              convertToUserDTO(userRegistrationDTO),
              user,
              extendedConsultingTypeResponseDTO,
              validationConstraints);
    }

    return new NewRegistrationResponseDto().sessionId(sessionId).status(HttpStatus.CREATED);
  }

  private UserDTO convertToUserDTO(UserRegistrationDTO userRegistrationDTO) {
    if (userRegistrationDTO instanceof NewRegistrationDto) {
      var userDTO = new UserDTO();
      userDTO.setAgencyId(userRegistrationDTO.getAgencyId());
      userDTO.setPostcode(userRegistrationDTO.getPostcode());
      userDTO.setConsultingType(userRegistrationDTO.getConsultingType());
      userDTO.setMainTopicId(userRegistrationDTO.getMainTopicId());
      userDTO.setUserGender(userRegistrationDTO.getUserGender());
      userDTO.setAge(String.valueOf(userRegistrationDTO.getUserAge()));
      userDTO.setCounsellingRelation(userRegistrationDTO.getCounsellingRelation());
      userDTO.setTopicIds(userRegistrationDTO.getTopicIds());
      userDTO.setMainTopicId(userRegistrationDTO.getMainTopicId());
      return userDTO;
    }

    return (UserDTO) userRegistrationDTO;
  }
}
