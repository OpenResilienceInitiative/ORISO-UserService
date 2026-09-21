package de.caritas.cob.userservice.api.facade.assignsession;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the department (agency x topic, ADR-003) an anonymous enquiry belongs to once a
 * counsellor accepts it.
 *
 * <p>Topic-based Live Chat invite links carry no agency, so the redeemed anonymous session starts
 * without one. ADR-022 decision 1 requires gate 2 to show the accepting Beratungsstelle /
 * Fachbereich document, which the entry room can only resolve from agency x topic. The department
 * is therefore the accepting counsellor's agency that offers the session's main topic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnonymousEnquiryDepartmentResolver {

  private final @NonNull AgencyService agencyService;

  /**
   * Returns the agency to bind to the given unbound anonymous session, or empty when the session is
   * already bound, has no main topic, or none of the consultant's agencies offers that topic —
   * acceptance then succeeds without a department and the client shows an explicit non-blocking
   * warning. Throws {@link ServiceUnavailableException} when the agency lookup itself fails, so a
   * transient outage leaves the enquiry retryable instead of accepted without its department.
   */
  public Optional<Long> resolveAgencyId(Session session, Consultant consultant) {
    if (nonNull(session.getAgencyId())) {
      return Optional.empty();
    }
    var topicId = session.getMainTopicId();
    if (isNull(topicId)) {
      log.warn("Anonymous session {} has no main topic, no department to bind", session.getId());
      return Optional.empty();
    }
    var agencyIds = activeAgencyIdsOf(consultant);
    if (agencyIds.isEmpty()) {
      log.warn(
          "Accepting consultant has no agency, anonymous session {} stays without department",
          session.getId());
      return Optional.empty();
    }

    List<AgencyDTO> agencies;
    try {
      // Uncached on purpose: an admin may just have changed the agency's topics, and this runs
      // once per acceptance.
      agencies = agencyService.getAgenciesWithoutCaching(agencyIds);
    } catch (RuntimeException e) {
      /* An outage is not "no department". Answering empty would persist the enquiry IN_PROGRESS
      without one, and the in-progress check would then refuse every retry: a passing hiccup would
      cost this help-seeker the centre's consent text for good. Fail the accept instead; nothing is
      saved yet, so the enquiry stays in the queue and the counsellor can accept it again. */
      log.warn(
          "AgencyService unavailable, accept of anonymous session {} left retryable: {}",
          session.getId(),
          e.getClass().getSimpleName());
      throw new ServiceUnavailableException(
          "Counselling centre lookup is temporarily unavailable; accept the enquiry again");
    }

    /* Several of the counsellor's agencies may offer the topic. The choice must be deterministic so
    the same accept always yields the same consent text: prefer an agency in the session's own
    tenant (its legal texts belong to the platform the help-seeker came through), then the lowest
    id (the oldest, stable pairing). */
    Optional<Long> agencyId =
        isNull(agencies)
            ? Optional.empty()
            : agencies.stream()
                .filter(Objects::nonNull)
                .filter(agency -> nonNull(agency.getId()))
                .filter(agency -> offersTopic(agency, topicId))
                .min(
                    Comparator.comparing(
                            (AgencyDTO agency) ->
                                !Objects.equals(agency.getTenantId(), session.getTenantId()))
                        .thenComparing(AgencyDTO::getId))
                .map(AgencyDTO::getId);

    if (agencyId.isEmpty()) {
      log.warn(
          "No agency of the accepting consultant offers topic {}, anonymous session {} stays"
              + " without department",
          topicId,
          session.getId());
    }
    return agencyId;
  }

  private static List<Long> activeAgencyIdsOf(Consultant consultant) {
    if (isNull(consultant.getConsultantAgencies())) {
      return List.of();
    }
    return consultant.getConsultantAgencies().stream()
        .filter(Objects::nonNull)
        .filter(consultantAgency -> isNull(consultantAgency.getDeleteDate()))
        .map(ConsultantAgency::getAgencyId)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
  }

  private static boolean offersTopic(AgencyDTO agency, Long topicId) {
    return nonNull(agency.getTopicIds()) && agency.getTopicIds().contains(topicId);
  }
}
