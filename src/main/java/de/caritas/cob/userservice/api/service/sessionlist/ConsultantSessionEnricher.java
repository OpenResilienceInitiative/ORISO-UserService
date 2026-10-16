package de.caritas.cob.userservice.api.service.sessionlist;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Adds database-backed metadata to consultant session-list entries.
 *
 * <p>Encrypted message previews and read state are owned by the frontend Matrix client.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultantSessionEnricher {

  @Autowired(required = false)
  private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  @Value("${feature.topics.enabled}")
  private boolean topicsFeatureEnabled;

  public List<ConsultantSessionResponseDTO> updateRequiredConsultantSessionValues(
      List<ConsultantSessionResponseDTO> consultantSessionResponseDTOs) {
    consultantSessionResponseDTOs.forEach(
        consultantSessionResponseDTO -> {
          try {
            enrichConsultantSession(consultantSessionResponseDTO);
          } catch (Exception e) {
            var sessionId =
                consultantSessionResponseDTO.getSession() != null
                    ? consultantSessionResponseDTO.getSession().getId()
                    : null;
            log.error(
                "Failed to enrich session {} with database metadata — returning it un-enriched",
                sessionId,
                e);
          }
        });
    return consultantSessionResponseDTOs;
  }

  private void enrichConsultantSession(ConsultantSessionResponseDTO consultantSessionResponseDTO) {
    var session = consultantSessionResponseDTO.getSession();
    // messagesRead is deprecated in the API spec: always true, read state is derived
    // client-side from the Matrix room (ORISO-Frontend#1147). Kept for compatibility.
    session.setMessagesRead(true);
    enrichSessionWithTopic(consultantSessionResponseDTO);
  }

  private void enrichSessionWithTopic(ConsultantSessionResponseDTO consultantSessionResponseDTO) {
    if (topicsFeatureEnabled) {
      sessionTopicEnrichmentService.enrichSessionWithTopicData(
          consultantSessionResponseDTO.getSession());
    }
  }
}
