package de.caritas.cob.userservice.api.service.session;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionTopicDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Focused authorized query boundary for one consultant-facing session detail response. */
@Service
@RequiredArgsConstructor
public class ConsultantSessionDetailService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull SessionAccessService sessionAccessService;
  private final @Nullable ConsultantSessionTopicEnrichmentService sessionTopicEnrichmentService;

  @Value("${feature.topics.enabled:true}")
  private boolean topicsFeatureEnabled;

  @Transactional(readOnly = true)
  public ConsultantSessionDTO fetchSessionForConsultant(
      @NonNull Long sessionId, @NonNull Consultant consultant) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session with id %s not found.", sessionId));

    sessionAccessService.checkPermissionForConsultantSession(session, consultant);
    return toConsultantSessionDTO(session);
  }

  private ConsultantSessionDTO toConsultantSessionDTO(Session session) {
    var consultantSessionDTO =
        new ConsultantSessionDTO()
            .isTeamSession(session.isTeamSession())
            .agencyId(session.getAgencyId())
            .consultingType(session.getConsultingTypeId())
            .id(session.getId())
            .status(session.getStatus().getValue())
            .askerId(session.getUser().getUserId())
            .askerMatrixUserId(session.getUser().getMatrixUserId())
            .askerUserName(session.getUser().getUsername())
            .matrixRoomId(session.getMatrixRoomId())
            .postcode(session.getPostcode())
            .consultantId(nonNull(session.getConsultant()) ? session.getConsultant().getId() : null)
            .consultantMatrixUserId(
                nonNull(session.getConsultant()) ? session.getConsultant().getMatrixUserId() : null)
            .age(session.getUserAge())
            .gender(session.getUserGender())
            .counsellingRelation(session.getCounsellingRelation())
            .referer(session.getReferer());

    if (topicsFeatureEnabled) {
      consultantSessionDTO
          .mainTopic(new SessionTopicDTO().id(session.getMainTopicId()))
          .topics(
              session.getSessionTopics().stream()
                  .map(topic -> new SessionTopicDTO().id(topic.getTopicId()))
                  .collect(Collectors.toList()));
      sessionTopicEnrichmentService.enrichSessionWithTopicData(consultantSessionDTO);
    } else {
      consultantSessionDTO.topics(null);
    }

    return consultantSessionDTO;
  }
}
