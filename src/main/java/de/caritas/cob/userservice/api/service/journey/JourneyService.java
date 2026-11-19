package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.model.JourneyRecord.AGENCY;
import static de.caritas.cob.userservice.api.model.JourneyRecord.PLATFORM;
import static de.caritas.cob.userservice.api.model.JourneyRecord.PLATFORM_SCOPE_ID;
import static de.caritas.cob.userservice.api.model.JourneyRecord.TENANT;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.JourneyRecord;
import de.caritas.cob.userservice.api.port.out.JourneyRecordRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores journeys per level (ADR-036 part E, ORISO-UserService#1689). The platform stores whole
 * journeys; a Träger ({@code TENANT}) or Beratungsstelle ({@code AGENCY}) stores only its layer on
 * a platform journey, so the platform journey never changes below the platform (decision 37).
 *
 * <p>Every write names the version it read: {@code 0} for something new. Another version means
 * someone saved meanwhile, answered with {@link JourneyConflictException}.
 */
@Service
@RequiredArgsConstructor
public class JourneyService {

  /** What the Admin may send; journeys are small, so a large body is a mistake or an attack. */
  static final int MAX_BODY_CHARS = 512 * 1024;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<LinkedHashMap<String, Object>> AS_MAP =
      new TypeReference<>() {};

  private final @NonNull JourneyRecordRepository repository;

  /** A stored journey or layer with the version a writer has to send back. */
  public record Stored(String journeyId, long version, Map<String, Object> body) {}

  public List<Stored> platformJourneys() {
    return repository.findByScopeAndScopeIdOrderByJourneyId(PLATFORM, PLATFORM_SCOPE_ID).stream()
        .map(JourneyService::stored)
        .toList();
  }

  @Transactional
  public Stored savePlatformJourney(
      String journeyId, long expectedVersion, Map<String, Object> journey, String actor) {
    checkId(journeyId);
    JourneyRules.checkJourney(journeyId, journey);
    lockJourneys();
    var stored = repository.findByScopeAndScopeIdOrderByJourneyId(PLATFORM, PLATFORM_SCOPE_ID);
    // Draft ids ("new:…") become ids of the server, the journey's own included (a new journey).
    var taken = new HashSet<String>();
    stored.forEach(
        row -> {
          taken.add(row.getJourneyId());
          taken.addAll(JourneyRules.stepIds(bodyOf(row)));
        });
    JourneyIds.issueAll(journey, taken);
    var id = (String) journey.get("id");
    var key = new JourneyRecord.Key(PLATFORM, PLATFORM_SCOPE_ID, id);
    var existing =
        JourneyIds.isDraft(journeyId) ? Optional.<JourneyRecord>empty() : repository.findById(key);
    checkVersion(existing, expectedVersion);

    var others =
        stored.stream()
            .filter(row -> !row.getJourneyId().equals(id))
            .map(JourneyService::bodyOf)
            .toList();
    var takenSlugs = new HashSet<String>();
    var takenStepIds = new HashSet<String>();
    var takenTopics = new HashSet<Long>();
    others.forEach(
        other -> {
          takenSlugs.addAll(JourneyRules.slugsById(other).values());
          takenStepIds.addAll(JourneyRules.stepIds(other));
          takenTopics.addAll(JourneyRules.registrationTopics(other));
        });
    // One topic, one registration journey: otherwise which one a person runs would be a guess.
    var sharedTopics = new HashSet<>(JourneyRules.registrationTopics(journey));
    sharedTopics.retainAll(takenTopics);
    if (!sharedTopics.isEmpty()) {
      throw new IllegalArgumentException("topics registered by another journey: " + sharedTopics);
    }
    var shared = new HashSet<>(JourneyRules.stepIds(journey));
    shared.retainAll(takenStepIds);
    if (!shared.isEmpty()) {
      throw new IllegalArgumentException("step ids used by another journey: " + shared);
    }
    JourneyRules.fixSlugs(journey, existing.map(JourneyService::bodyOf).orElse(null), takenSlugs);

    var row = existing.orElseGet(JourneyRecord::new);
    row.setScope(PLATFORM);
    row.setScopeId(PLATFORM_SCOPE_ID);
    row.setJourneyId(id);
    row.setConversationType((String) journey.get("conversationType"));
    row.setBody(write(journey));
    row.setUpdatedBy(actor);
    return stored(repository.saveAndFlush(row));
  }

  /** Deletes a platform journey and every layer put on it. */
  @Transactional
  public void deletePlatformJourney(String journeyId, long expectedVersion) {
    lockJourneys();
    var existing =
        repository.findById(new JourneyRecord.Key(PLATFORM, PLATFORM_SCOPE_ID, journeyId));
    if (existing.isEmpty()) {
      throw new NoSuchElementException("no journey " + journeyId);
    }
    checkVersion(existing, expectedVersion);
    repository.deleteAll(repository.findByJourneyId(journeyId));
  }

  public List<Stored> layers(String level, long ownerId) {
    checkLevel(level);
    return repository.findByScopeAndScopeIdOrderByJourneyId(level, ownerId).stream()
        .map(JourneyService::stored)
        .toList();
  }

  /**
   * Stores the layer of a Träger or Beratungsstelle on a platform journey. An empty layer (no phase
   * changed any more) removes the row.
   */
  @Transactional
  public Optional<Stored> saveLayer(
      String level,
      long ownerId,
      String journeyId,
      long expectedVersion,
      Map<String, Object> phases,
      String actor) {
    checkLevel(level);
    checkId(journeyId);
    JourneyRules.checkLayer(phases);
    // A delete of the platform journey running at the same time cannot leave this layer behind.
    lockJourneys();
    var platform =
        repository
            .findById(new JourneyRecord.Key(PLATFORM, PLATFORM_SCOPE_ID, journeyId))
            .orElseThrow(() -> new NoSuchElementException("no platform journey " + journeyId));
    var key = new JourneyRecord.Key(level, ownerId, journeyId);
    var existing = repository.findById(key);
    checkVersion(existing, expectedVersion);
    var taken = new HashSet<>(JourneyRules.stepIds(bodyOf(platform)));
    existing.ifPresent(row -> taken.addAll(JourneyRules.stepIds(bodyOf(row))));
    JourneyIds.issueAll(phases, taken);
    if (phases.isEmpty()) {
      existing.ifPresent(repository::delete);
      return Optional.empty();
    }
    var row = existing.orElseGet(JourneyRecord::new);
    row.setScope(level);
    row.setScopeId(ownerId);
    row.setJourneyId(journeyId);
    row.setConversationType(platform.getConversationType());
    row.setBody(write(Map.of("phases", phases)));
    row.setUpdatedBy(actor);
    return Optional.of(stored(repository.saveAndFlush(row)));
  }

  /**
   * Waits for and takes the journeys' write lock until the transaction ends. The lock row is made
   * by the changeset; where it is missing (a database built without Liquibase) it is made here, and
   * a concurrent writer making it as well only means locking the row it made.
   */
  private void lockJourneys() {
    if (repository
        .lockRow(JourneyRecord.LOCK, PLATFORM_SCOPE_ID, JourneyRecord.LOCK_ID)
        .isPresent()) {
      return;
    }
    try {
      var row = new JourneyRecord();
      row.setScope(JourneyRecord.LOCK);
      row.setScopeId(PLATFORM_SCOPE_ID);
      row.setJourneyId(JourneyRecord.LOCK_ID);
      row.setConversationType("-");
      row.setBody("{}");
      repository.saveAndFlush(row);
    } catch (DataIntegrityViolationException alreadyMade) {
      // Someone else made it first; locking it below waits for them.
    }
    repository.lockRow(JourneyRecord.LOCK, PLATFORM_SCOPE_ID, JourneyRecord.LOCK_ID);
  }

  /**
   * The version a client sees: 0 for nothing stored, 1 after the first save, then one more per
   * save. JPA counts from 0 for a new row, so the client's number is the row's plus one.
   */
  private static long clientVersion(JourneyRecord row) {
    return row.getVersion() == null ? 1 : row.getVersion() + 1;
  }

  private static void checkVersion(Optional<JourneyRecord> existing, long expectedVersion) {
    long current = existing.map(JourneyService::clientVersion).orElse(0L);
    if (current != expectedVersion) {
      throw new JourneyConflictException(current);
    }
  }

  private static void checkLevel(String level) {
    if (!TENANT.equals(level) && !AGENCY.equals(level)) {
      throw new IllegalArgumentException("unknown level " + level);
    }
  }

  private static void checkId(String journeyId) {
    if (journeyId == null || !JourneyRules.ID.matcher(journeyId).matches()) {
      throw new IllegalArgumentException("journey id invalid");
    }
  }

  private static Stored stored(JourneyRecord row) {
    return new Stored(row.getJourneyId(), clientVersion(row), bodyOf(row));
  }

  static Map<String, Object> bodyOf(JourneyRecord row) {
    try {
      return MAPPER.readValue(row.getBody(), AS_MAP);
    } catch (Exception unreadable) {
      throw new IllegalStateException("Unreadable journey " + row.getJourneyId(), unreadable);
    }
  }

  private static String write(Map<String, Object> body) {
    try {
      var json = MAPPER.writeValueAsString(body);
      if (json.length() > MAX_BODY_CHARS) {
        throw new IllegalArgumentException("journey too large");
      }
      return json;
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalArgumentException("journey is not valid JSON", invalid);
    }
  }
}
