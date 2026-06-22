package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAvailabilityDTO;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint reporting whether any consultant is currently available for a given topic, so the
 * anonymous live-chat screen can show the "no counsellor available" alert before an enquiry is
 * created.
 *
 * <p>It reuses {@link TopicConsultantRoutingService} — the exact same routing logic the backend
 * applies when an anonymous enquiry is actually created — so the alert reflects real routing
 * behaviour (topic-assigned, not absent, intersected with online presence when known).
 */
@RestController
@RequiredArgsConstructor
public class TopicConsultantAvailabilityController {

  private final @NonNull TopicConsultantRoutingService topicConsultantRoutingService;

  @GetMapping({
    "/conversations/anonymous/availability",
    "/service/conversations/anonymous/availability"
  })
  public ResponseEntity<ConsultantAvailabilityDTO> getTopicConsultantAvailability(
      @RequestParam Long topicId, @RequestParam(required = false) Integer consultingTypeId) {

    List<String> availableConsultantIds;
    try {
      availableConsultantIds = topicConsultantRoutingService.findAvailableConsultantIds(topicId);
    } catch (Exception ex) {
      availableConsultantIds = List.of();
    }

    return ResponseEntity.ok(
        new ConsultantAvailabilityDTO(
            !availableConsultantIds.isEmpty(), availableConsultantIds.size()));
  }
}
