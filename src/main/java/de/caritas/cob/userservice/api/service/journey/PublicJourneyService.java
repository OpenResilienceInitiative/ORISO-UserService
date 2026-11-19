package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.model.JourneyRecord.AGENCY;
import static de.caritas.cob.userservice.api.model.JourneyRecord.PLATFORM;
import static de.caritas.cob.userservice.api.model.JourneyRecord.PLATFORM_SCOPE_ID;
import static de.caritas.cob.userservice.api.model.JourneyRecord.TENANT;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.JourneyRecord;
import de.caritas.cob.userservice.api.port.out.JourneyRecordRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
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

  /** How long anonymous reads share one parse of the platform journeys. */
  static final Duration CACHE_FOR = Duration.ofSeconds(30);

  private final @NonNull JourneyRecordRepository repository;
  private final @NonNull AgencyService agencyService;

  /**
   * The platform journeys, parsed once for all anonymous readers for {@link #CACHE_FOR}: a
   * registration page must not make the server parse every journey on every request.
   */
  private volatile Parsed parsed;

  private record Parsed(Instant at, List<Platform> journeys) {}

  private record Platform(JourneyRecord row, Map<String, Object> body) {}

  /** The journey and, for a link, the link the person came by (its topics, its preselection). */
  public record PublicJourney(
      String journeyId, Map<String, Object> journey, Map<String, Object> link) {}

  /** The journey whose registration runs for this topic, as the given centre's Träger sees it. */
  public Optional<PublicJourney> forRegistration(long topicId, Long agencyId) {
    var owners = owners(agencyId);
    return platformJourneys().stream()
        .filter(entry -> REGISTRATION_TYPE.equals(entry.row().getConversationType()))
        .filter(entry -> JourneyView.registersTopic(entry.body(), topicId))
        .findFirst()
        .map(entry -> view(entry, owners, null));
  }

  /** The journey behind a link address ({@code /l/<slug>}). */
  public Optional<PublicJourney> forLink(String slug, Long agencyId) {
    if (slug == null || !JourneyRules.isSlug(slug)) {
      return Optional.empty();
    }
    var owners = owners(agencyId);
    for (var entry : platformJourneys()) {
      var link = JourneyView.linkWithSlug(entry.body(), slug);
      if (link.isPresent()) {
        return Optional.of(view(entry, owners, link.get()));
      }
    }
    return Optional.empty();
  }

  private List<Platform> platformJourneys() {
    var current = parsed;
    var now = Instant.now();
    if (current == null || current.at().plus(CACHE_FOR).isBefore(now)) {
      current =
          new Parsed(
              now,
              repository.findByScopeAndScopeIdOrderByJourneyId(PLATFORM, PLATFORM_SCOPE_ID).stream()
                  .map(row -> new Platform(row, JourneyService.bodyOf(row)))
                  .toList());
      parsed = current;
    }
    return current.journeys();
  }

  /** Whose layers apply: the centre's Träger, else the Träger of the domain; then the centre. */
  private Owners owners(Long agencyId) {
    if (agencyId == null) {
      var tenantId = TenantContext.getCurrentTenant();
      return new Owners(tenantId != null && tenantId > 0 ? tenantId : null, null);
    }
    AgencyDTO agency;
    try {
      agency = agencyService.getAgency(agencyId);
    } catch (RuntimeException unavailable) {
      // An unknown id or an AgencyService outage: either way no centre to build on.
      throw new NoSuchElementException("agency " + agencyId + " not readable");
    }
    if (agency == null) {
      throw new NoSuchElementException("no agency " + agencyId);
    }
    // A centre of another Träger than the one whose domain asks is not this registration's.
    var tenantId = TenantContext.getCurrentTenant();
    if (tenantId != null && tenantId > 0 && !tenantId.equals(agency.getTenantId())) {
      throw new NoSuchElementException("agency " + agencyId + " not in tenant");
    }
    return new Owners(agency.getTenantId(), agencyId);
  }

  private PublicJourney view(Platform entry, Owners owners, Map<String, Object> link) {
    var journeyId = entry.row().getJourneyId();
    var layers = new ArrayList<Map<String, Object>>();
    layer(TENANT, owners.tenantId(), journeyId).ifPresent(layers::add);
    layer(AGENCY, owners.agencyId(), journeyId).ifPresent(layers::add);
    var journey = JourneyView.forAdviceSeekers(JourneyView.effective(entry.body(), layers));
    return new PublicJourney(journeyId, journey, link == null ? null : publicLink(link));
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
