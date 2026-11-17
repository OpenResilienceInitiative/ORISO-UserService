package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * #1662 (Frank, 17 Nov): a guest who comes in through a group call's share link registers and
 * consents under the Beratungsstelle of the counsellor who started the call. Derived on the server
 * from the binding alone, never from the client.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupCallCreatorAgency {
  private final ConsultantRepository consultants;
  private final ChatAgencyRepository chatAgencies;
  private final AgencyService agencies;

  /**
   * The one Beratungsstelle of the group that the call's creator serves. Empty for a 1:1 session
   * call, a call an advice seeker started, a creator whose Matrix id is not theirs alone in the
   * Träger (SoleCounsellor), and a creator who serves none or several of the group's
   * Beratungsstellen: which consent text applies would be a guess, so such a link takes existing
   * accounts only (fail closed). Read again on every use, so a creator who leaves the
   * Beratungsstelle ends the link.
   */
  public Optional<Long> of(MatrixCallBinding binding) {
    if (binding == null
        || binding.getChatId() == null
        || binding.getSessionId() != null
        || binding.getTenantId() == null) {
      return Optional.empty();
    }
    var creator =
        SoleCounsellor.of(consultants, binding.getCallerMatrixId(), binding.getTenantId())
            .orElse(null);
    if (creator == null || creator.getConsultantAgencies() == null) return Optional.empty();
    Set<Long> groupAgencies =
        chatAgencies.findByChat_Id(binding.getChatId()).stream()
            .map(ChatAgency::getAgencyId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    var served =
        creator.getConsultantAgencies().stream()
            .filter(Objects::nonNull)
            .filter(membership -> membership.getDeleteDate() == null)
            .map(ConsultantAgency::getAgencyId)
            .filter(groupAgencies::contains)
            .collect(Collectors.toSet());
    return served.size() == 1 ? served.stream().findFirst() : Optional.empty();
  }

  /** The consulting type the Beratungsstelle registers under; empty when it cannot be read. */
  public Optional<Integer> consultingTypeOf(Long agencyId) {
    if (agencyId == null) return Optional.empty();
    try {
      return Optional.ofNullable(agencies.getAgency(agencyId)).map(AgencyDTO::getConsultingType);
    } catch (RuntimeException unavailable) {
      log.warn(
          "Agency lookup for a group call link failed ({})",
          unavailable.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
