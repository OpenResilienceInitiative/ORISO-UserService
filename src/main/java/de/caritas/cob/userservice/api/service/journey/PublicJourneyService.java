package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.model.JourneyRecord.AGENCY;
import static de.caritas.cob.userservice.api.model.JourneyRecord.PLATFORM;
import static de.caritas.cob.userservice.api.model.JourneyRecord.PLATFORM_SCOPE_ID;
import static de.caritas.cob.userservice.api.model.JourneyRecord.TENANT;

import de.caritas.cob.userservice.api.model.JourneyRecord;
import de.caritas.cob.userservice.api.port.out.JourneyRecordRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reads the journey an advice seeker runs, without a login (ORISO-Frontend#2333): for a topic of
 * the registration, or for a link address. The answer is the effective journey (platform, then the
 * Träger's layer, then the centre's) without what only editors see. Nothing stored means no
 * journey: the Frontend keeps today's registration form.
 */
@Service
@RequiredArgsConstructor
public class PublicJourneyService {

  static final String REGISTRATION_TYPE = "AGENCY_COUNSELLING";

  private final @NonNull JourneyRecordRepository repository;
  private final @NonNull AgencyService agencyService;

  /** The journey and, for a link, the link the person came by (its topics, its preselection). */
  public record PublicJourney(
      String journeyId, Map<String, Object> journey, Map<String, Object> link) {}

  /** The journey whose registration runs for this topic, as the given centre's Träger sees it. */
  public Optional<PublicJourney> forRegistration(long topicId, Long agencyId) {
    var owners = owners(agencyId);
    return platformJourneys().stream()
        .filter(row -> REGISTRATION_TYPE.equals(row.getConversationType()))
        .filter(row -> JourneyView.registersTopic(JourneyService.bodyOf(row), topicId))
        .findFirst()
        .map(row -> view(row, owners, null));
  }

  /** The journey behind a link address ({@code /l/<slug>}). */
  public Optional<PublicJourney> forLink(String slug, Long agencyId) {
    if (slug == null || !JourneyRules.isSlug(slug)) {
      return Optional.empty();
    }
    var owners = owners(agencyId);
    for (var row : platformJourneys()) {
      var body = JourneyService.bodyOf(row);
      var link = JourneyView.linkWithSlug(body, slug);
      if (link.isPresent()) {
        return Optional.of(view(row, owners, link.get()));
      }
    }
    return Optional.empty();
  }

  private List<JourneyRecord> platformJourneys() {
    return repository.findByScopeAndScopeIdOrderByJourneyId(PLATFORM, PLATFORM_SCOPE_ID);
  }

  /** Whose layers apply: the centre's Träger, else the Träger of the domain; then the centre. */
  private Owners owners(Long agencyId) {
    if (agencyId == null) {
      var tenantId = TenantContext.getCurrentTenant();
      return new Owners(tenantId != null && tenantId > 0 ? tenantId : null, null);
    }
    var agency = agencyService.getAgency(agencyId);
    if (agency == null) {
      throw new NoSuchElementException("no agency " + agencyId);
    }
    return new Owners(agency.getTenantId(), agencyId);
  }

  private PublicJourney view(JourneyRecord row, Owners owners, Map<String, Object> link) {
    var layers = new ArrayList<Map<String, Object>>();
    layer(TENANT, owners.tenantId(), row.getJourneyId()).ifPresent(layers::add);
    layer(AGENCY, owners.agencyId(), row.getJourneyId()).ifPresent(layers::add);
    var journey =
        JourneyView.forAdviceSeekers(JourneyView.effective(JourneyService.bodyOf(row), layers));
    return new PublicJourney(row.getJourneyId(), journey, link == null ? null : publicLink(link));
  }

  private Optional<Map<String, Object>> layer(String scope, Long ownerId, String journeyId) {
    if (ownerId == null) {
      return Optional.empty();
    }
    return repository
        .findById(new JourneyRecord.Key(scope, ownerId, journeyId))
        .map(JourneyService::bodyOf);
  }

  /** The link as the person needs it: where it leads; not the editors' name for it. */
  private static Map<String, Object> publicLink(Map<String, Object> link) {
    var topics = link.get("topicIds") instanceof List<?> ids ? ids : List.of();
    return link.get("agencyName") instanceof String agency
        ? Map.of("slug", link.get("slug"), "topicIds", topics, "agencyName", agency)
        : Map.of("slug", link.get("slug"), "topicIds", topics);
  }

  private record Owners(Long tenantId, Long agencyId) {}
}
