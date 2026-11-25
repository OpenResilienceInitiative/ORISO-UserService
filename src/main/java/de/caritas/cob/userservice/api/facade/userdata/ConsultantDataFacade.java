package de.caritas.cob.userservice.api.facade.userdata;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.AbsenceDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.Helper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantDataFacade {

  private final @NonNull ConsultantService consultantService;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull UserDtoMapper userDtoMapper;
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;

  /**
   * Updates a {@link Consultant} with the absence data from a (@Link AbsenceDTO).
   *
   * @param absence {@link AbsenceDTO}
   */
  public Consultant updateConsultantAbsent(Consultant consultant, AbsenceDTO absence) {
    var switchedOn = isTrue(absence.getAbsent());
    var start = switchedOn ? absence.getStart() : null;
    var end = switchedOn ? absence.getEnd() : null;
    if (start != null && end != null && end.isBefore(start)) {
      throw new BadRequestException("The absence must not end before it starts.");
    }
    consultant.setAbsent(switchedOn);
    // ORISO-Frontend#2303: switching off ends the absence together with its planned range.
    consultant.setAbsenceStart(start);
    consultant.setAbsenceEnd(end);

    if (isNotBlank(absence.getMessage())) {
      consultant.setAbsenceMessage(Helper.removeHTMLFromText(absence.getMessage()));
    } else {
      consultant.setAbsenceMessage(null);
    }
    return this.consultantService.saveConsultant(consultant);
  }

  public void addConsultantDisplayNameToSessionList(GroupSessionListResponseDTO groupSessionList) {
    if (groupSessionList == null || groupSessionList.getSessions() == null) {
      return;
    }
    groupSessionList
        .getSessions()
        .forEach(
            session -> {
              var consultant = session.getConsultant();
              if (nonNull(consultant) && consultant.getUsername() == null) {
                // FE#2494 *4: the counsellor view of a session carries only the counsellor's id and
                // real name. A team reader's banner needs the public name, so resolve the username.
                addUsernameById(consultant);
              }
              if (nonNull(consultant) && nonNull(consultant.getUsername())) {
                try {
                  // The owner of a members-only chat may work for another Traeger than the reader.
                  var chatId = session.getChat() == null ? null : session.getChat().getId();
                  membersOnlyChatAccess
                      .readingAcrossTraegerOfChat(
                          chatId,
                          () -> accountManager.findConsultantByUsername(consultant.getUsername()))
                      .ifPresent(
                          consultantMap ->
                              consultant.setDisplayName(
                                  userDtoMapper.displayNameOf(consultantMap)));
                } catch (Exception e) {
                  log.error("Could not enrich consultant display name: {}", e.getMessage());
                }
              }
            });
  }

  private void addUsernameById(
      de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO consultant) {
    if (consultant.getId() == null) {
      return;
    }
    try {
      consultantService
          .getConsultant(consultant.getId())
          .map(Consultant::getUsername)
          .ifPresent(consultant::setUsername);
    } catch (Exception e) {
      log.error("Could not resolve consultant username: {}", e.getMessage());
    }
  }

  public void addConsultantDisplayNameToSessionList(UserSessionListResponseDTO userSessionsDTO) {
    if (userSessionsDTO == null || userSessionsDTO.getSessions() == null) {
      return;
    }
    userSessionsDTO
        .getSessions()
        .forEach(
            session -> {
              var consultant = session.getConsultant();
              if (nonNull(consultant) && nonNull(consultant.getUsername())) {
                try {
                  accountManager
                      .findConsultantByUsername(consultant.getUsername())
                      .ifPresent(
                          consultantMap ->
                              consultant.setDisplayName(
                                  userDtoMapper.displayNameOf(consultantMap)));
                } catch (Exception e) {
                  log.error("Could not enrich consultant display name: {}", e.getMessage());
                }
              }
            });
  }

  public void addConsultantDisplayNameToSessionList(
      List<ConsultantSessionResponseDTO> consultantSessionResponseDTOs) {
    consultantSessionResponseDTOs.stream()
        .forEach(
            session -> {
              var chatOwner = session.getConsultant();
              if (nonNull(chatOwner) && nonNull(chatOwner.getUsername())) {
                try {
                  Optional<Map<String, Object>> consultantByUsername =
                      accountManager.findConsultantByUsername(chatOwner.getUsername());
                  if (consultantByUsername.isPresent()) {
                    chatOwner.setDisplayName(
                        userDtoMapper.displayNameOf(consultantByUsername.get()));
                  }
                } catch (Exception e) {
                  log.error("Error while fetching consultant by username: {}", e.getMessage());
                }
              }
            });
  }
}
