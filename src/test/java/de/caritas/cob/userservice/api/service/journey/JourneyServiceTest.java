package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.link;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.mail;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.JourneyRecord;
import de.caritas.cob.userservice.api.port.out.JourneyRecordRepository;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JourneyServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private JourneyRecordRepository repository;
  @InjectMocks private JourneyService service;

  @BeforeEach
  void saveEchoes() {
    when(repository.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              JourneyRecord row = call.getArgument(0);
              row.setVersion(row.getVersion() == null ? 0 : row.getVersion() + 1);
              return row;
            });
    when(repository.findByScopeAndScopeIdOrderByJourneyId(anyString(), any()))
        .thenReturn(List.of());
    when(repository.lockRow(any(), any(), any())).thenReturn(Optional.of(new JourneyRecord()));
  }

  @Test
  void storesANewPlatformJourneyAtVersionOne() {
    when(repository.findById(any())).thenReturn(Optional.empty());

    var stored = service.savePlatformJourney("j1", 0, mail("j1", link("a", "Sucht")), "admin");

    assertThat(stored.version()).isEqualTo(1);
    assertThat(stored.body()).containsKey("phases");
  }

  @Test
  void refusesAStaleVersion() {
    when(repository.findById(any()))
        .thenReturn(Optional.of(row("PLATFORM", 0L, "j1", mail("j1"), 2L)));

    assertThatThrownBy(() -> service.savePlatformJourney("j1", 1, mail("j1"), "admin"))
        .isInstanceOf(JourneyConflictException.class)
        .extracting("currentVersion")
        .isEqualTo(3L);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void refusesToCreateWhatAlreadyExists() {
    when(repository.findById(any()))
        .thenReturn(Optional.of(row("PLATFORM", 0L, "j1", mail("j1"), 0L)));

    assertThatThrownBy(() -> service.savePlatformJourney("j1", 0, mail("j1"), "admin"))
        .isInstanceOf(JourneyConflictException.class);
  }

  @Test
  void refusesStepIdsOfAnotherJourney() {
    when(repository.findById(any())).thenReturn(Optional.empty());
    when(repository.findByScopeAndScopeIdOrderByJourneyId("PLATFORM", 0L))
        .thenReturn(List.of(row("PLATFORM", 0L, "j2", mail("j1"), 0L)));

    assertThatThrownBy(() -> service.savePlatformJourney("j1", 0, mail("j1"), "admin"))
        .hasMessageContaining("another journey");
  }

  @Test
  void storesALayerOnlyOnAnExistingPlatformJourney() {
    when(repository.findById(any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.saveLayer(
                    "TENANT",
                    7L,
                    "j1",
                    0,
                    Map.of("flight", List.of(step("q", "question", null))),
                    "t"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void storesTheLayerOfATraegerWithoutTouchingThePlatformJourney() {
    var platform = row("PLATFORM", 0L, "j1", mail("j1"), 0L);
    when(repository.findById(new JourneyRecord.Key("PLATFORM", 0L, "j1")))
        .thenReturn(Optional.of(platform));
    when(repository.findById(new JourneyRecord.Key("TENANT", 7L, "j1")))
        .thenReturn(Optional.empty());

    var stored =
        service.saveLayer(
            "TENANT", 7L, "j1", 0, Map.of("flight", List.of(step("q", "question", null))), "t");

    assertThat(stored).isPresent();
    assertThat(stored.get().version()).isEqualTo(1);
    verify(repository)
        .saveAndFlush(org.mockito.ArgumentMatchers.argThat(r -> "TENANT".equals(r.getScope())));
    verify(repository, never())
        .saveAndFlush(org.mockito.ArgumentMatchers.argThat(r -> "PLATFORM".equals(r.getScope())));
  }

  @Test
  void removesALayerThatNoLongerChangesAnything() {
    var layer = row("AGENCY", 3L, "j1", Map.of("phases", Map.of()), 0L);
    when(repository.findById(new JourneyRecord.Key("PLATFORM", 0L, "j1")))
        .thenReturn(Optional.of(row("PLATFORM", 0L, "j1", mail("j1"), 0L)));
    when(repository.findById(new JourneyRecord.Key("AGENCY", 3L, "j1")))
        .thenReturn(Optional.of(layer));

    assertThat(service.saveLayer("AGENCY", 3L, "j1", 1, Map.of(), "a")).isEmpty();
    verify(repository).delete(layer);
  }

  @Test
  void refusesAnUnknownLevel() {
    assertThatThrownBy(() -> service.layers("PLATFORM", 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static JourneyRecord row(
      String scope, Long scopeId, String journeyId, Map<String, Object> body, Long version) {
    try {
      return new JourneyRecord(
          scope,
          scopeId,
          journeyId,
          "AGENCY_COUNSELLING",
          MAPPER.writeValueAsString(body),
          version,
          null,
          null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void issuesTheIdsOfANewJourneyAndAnswersWithThem() {
    var journey = mail("new:journey-7");
    var saved = service.savePlatformJourney("new:journey-7", 0, journey, "admin");
    assertThat(saved.journeyId()).startsWith("journey-");
    assertThat(saved.body().get("id")).isEqualTo(saved.journeyId());
    assertThat(JourneyRules.stepIds(saved.body())).noneMatch(JourneyIds::isDraft);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aJourneyWithoutLinksIsSaved() {
    var journey = mail("j9");
    ((Map<String, Object>) journey.get("routes")).remove("links");
    assertThat(service.savePlatformJourney("j9", 0, journey, "admin").journeyId()).isEqualTo("j9");
  }

  @Test
  void aTopicRegistersOneJourneyOnly() throws Exception {
    var other = new JourneyRecord();
    other.setScope(JourneyRecord.PLATFORM);
    other.setScopeId(0L);
    other.setJourneyId("j1");
    other.setVersion(0L);
    other.setBody(MAPPER.writeValueAsString(mail("j1")));
    when(repository.findByScopeAndScopeIdOrderByJourneyId(anyString(), any()))
        .thenReturn(List.of(other));
    assertThatThrownBy(() -> service.savePlatformJourney("j2", 0, mail("j2"), "admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("topics registered by another journey");
  }

  @Test
  void everyWriteTakesTheJourneysLockBeforeReadingWhatOthersStored() {
    service.savePlatformJourney("j1", 0, mail("j1"), "admin");
    var order = org.mockito.Mockito.inOrder(repository);
    order.verify(repository).lockRow(JourneyRecord.LOCK, 0L, JourneyRecord.LOCK_ID);
    order.verify(repository).findByScopeAndScopeIdOrderByJourneyId(JourneyRecord.PLATFORM, 0L);
  }

  @Test
  void makesTheLockRowWhereTheDatabaseHasNone() {
    when(repository.lockRow(any(), any(), any())).thenReturn(Optional.empty());
    service.savePlatformJourney("j1", 0, mail("j1"), "admin");
    verify(repository)
        .saveAndFlush(
            org.mockito.ArgumentMatchers.argThat(
                row ->
                    JourneyRecord.LOCK.equals(row.getScope())
                        && JourneyRecord.LOCK_ID.equals(row.getJourneyId())));
    verify(repository, org.mockito.Mockito.times(2))
        .lockRow(JourneyRecord.LOCK, 0L, JourneyRecord.LOCK_ID);
  }
}
