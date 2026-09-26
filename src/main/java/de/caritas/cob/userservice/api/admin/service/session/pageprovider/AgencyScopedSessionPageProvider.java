package de.caritas.cob.userservice.api.admin.service.session.pageprovider;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionFilter;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Page provider for a caller whose reach ends at their own agencies (Beratungsstellen admin). It
 * honours the same filters, in the same order of precedence, as the unrestricted providers (agency,
 * asker, consultant, consulting type, none), but only ever returns sessions of the given agencies.
 */
@RequiredArgsConstructor
public class AgencyScopedSessionPageProvider implements SessionPageProvider {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull SessionFilter sessionFilter;
  private final @NonNull Set<Long> agencyIds;

  @Override
  public Page<Session> executeQuery(Pageable pageable) {
    if (agencyIds.isEmpty()) {
      return Page.empty(pageable);
    }
    if (nonNull(sessionFilter.getAgency())) {
      var agencyId = sessionFilter.getAgency().longValue();
      return agencyIds.contains(agencyId)
          ? sessionRepository.findByAgencyId(agencyId, pageable)
          : Page.empty(pageable);
    }
    if (isNotBlank(sessionFilter.getAsker())) {
      return sessionRepository.findByUserUserIdAndAgencyIdIn(
          sessionFilter.getAsker(), agencyIds, pageable);
    }
    if (isNotBlank(sessionFilter.getConsultant())) {
      return sessionRepository.findByConsultantIdAndAgencyIdIn(
          sessionFilter.getConsultant(), agencyIds, pageable);
    }
    if (nonNull(sessionFilter.getConsultingType())) {
      return sessionRepository.findByConsultingTypeIdAndAgencyIdIn(
          sessionFilter.getConsultingType(), agencyIds, pageable);
    }
    return sessionRepository.findByAgencyIdIn(agencyIds, pageable);
  }
}
