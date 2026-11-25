package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ErstantwortTextOverride;
import de.caritas.cob.userservice.api.port.out.ErstantwortTextOverrideRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ErstantwortPlatformTextServiceTest {

  @Mock private ErstantwortTextOverrideRepository repository;
  private ErstantwortPlatformTextService service;

  @BeforeEach
  void setUp() {
    service = new ErstantwortPlatformTextService(repository);
  }

  private static ErstantwortTextOverride row(String baustein, Map<String, String> texts) {
    return ErstantwortTextOverride.platform(baustein, texts, "admin");
  }

  @Test
  void resolvesTheReadersLanguageAsPlainText() {
    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.PLATFORM, 0L))
        .thenReturn(
            List.of(
                row("greeting", Map.of("de", "<p>Hallo <b>Sie</b></p>", "en", "<p>Hello</p>")),
                row("closing", Map.of("de", "<p>Tschüss</p>"))));

    assertThat(service.resolvePlatformTexts("en", false))
        .containsExactly(Map.entry("greeting", "Hello"));
    assertThat(service.resolvePlatformTexts("de", false))
        .containsEntry("greeting", "Hallo Sie")
        .containsEntry("closing", "Tschüss");
  }

  @Test
  void informalGermanReadsOnlyTheInformalVariant() {
    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.PLATFORM, 0L))
        .thenReturn(List.of(row("greeting", Map.of("de", "Hallo Sie", "de@informal", "Hallo Du"))));

    assertThat(service.resolvePlatformTexts("de", true)).containsEntry("greeting", "Hallo Du");
    assertThat(service.resolvePlatformTexts("de@informal", false))
        .containsEntry("greeting", "Hallo Du");
  }

  /** Another language's text would address the reader in a language they did not choose. */
  @Test
  void aMissingLanguageFallsBackToTheBuiltInDefaultNotToAnotherLanguage() {
    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.PLATFORM, 0L))
        .thenReturn(List.of(row("greeting", Map.of("de", "Hallo"))));

    assertThat(service.resolvePlatformTexts("fr", false)).isEmpty();
  }

  /** Informal German is the same language: the admin's German text applies to "du" Träger too. */
  @Test
  void informalGermanFallsBackToThePlatformsGermanText() {
    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.PLATFORM, 0L))
        .thenReturn(List.of(row("greeting", Map.of("de", "Hallo"))));

    assertThat(service.resolvePlatformTexts("de", true)).containsEntry("greeting", "Hallo");
  }

  @Test
  void unknownOrMissingLocaleReadsGerman() {
    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.PLATFORM, 0L))
        .thenReturn(List.of(row("greeting", Map.of("de", "Hallo"))));

    assertThat(service.resolvePlatformTexts(null, false)).containsEntry("greeting", "Hallo");
    assertThat(service.resolvePlatformTexts("xx", false)).containsEntry("greeting", "Hallo");
  }

  @Test
  void aStorageFailureNeverCostsTheEnquiry() {
    when(repository.findByScopeAndScopeId(any(), any())).thenThrow(new IllegalStateException("db"));

    assertThat(service.resolvePlatformTexts("de", false)).isEmpty();
  }

  @Test
  void replaceStoresSanitisedHtmlAndDropsEmptiedLanguages() {
    var texts = new HashMap<String, String>();
    texts.put("de", "<p>Hallo<script>alert(1)</script></p>");
    texts.put("en", "");
    texts.put("en__meta", "");
    texts.put("fr", "<p>Bonjour</p>");
    texts.put("fr__meta", "{\"src\":\"de\",\"at\":\"2026-11-16\"}");

    service.replacePlatformTexts(Map.of("greeting", texts), "platform.admin");

    var captor = ArgumentCaptor.forClass(ErstantwortTextOverride.class);
    verify(repository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getBaustein()).isEqualTo("greeting");
    assertThat(saved.getUpdatedBy()).isEqualTo("platform.admin");
    assertThat(saved.textsAsMap())
        .containsOnlyKeys("de", "fr", "fr__meta")
        .containsEntry("de", "<p>Hallo</p>");
  }

  @Test
  void anAllEmptyMapResetsTheBausteinToTheBuiltInDefault() {
    service.replacePlatformTexts(Map.of("closing", Map.of("de", "", "en", " ")), "admin");

    verify(repository).deleteById(ErstantwortTextOverride.platformKey("closing"));
    verify(repository, never()).save(any());
  }

  @Test
  void onlyTheEditableBausteineAreAccepted() {
    assertThatThrownBy(
            () -> service.replacePlatformTexts(Map.of("whoReadsAlong", Map.of("de", "x")), "a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service.replacePlatformTexts(Map.of("noPersonalData", Map.of("de", "x")), "a"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void onlyTheSevenLanguageVersionsAreAccepted() {
    assertThatThrownBy(
            () -> service.replacePlatformTexts(Map.of("greeting", Map.of("es", "Hola")), "a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service.replacePlatformTexts(Map.of("greeting", Map.of("../de", "x")), "a"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aValidEntryFollowedByAnInvalidOneStoresNothing() {
    var platform = new LinkedHashMap<String, Map<String, String>>();
    platform.put("greeting", Map.of("de", "Hallo"));
    platform.put("whoReadsAlong", Map.of("de", "x"));
    assertThatThrownBy(() -> service.replacePlatformTexts(platform, "a"))
        .isInstanceOf(IllegalArgumentException.class);

    var centre = new LinkedHashMap<String, Map<String, String>>();
    centre.put("greeting", Map.of("de", "Hallo"));
    centre.put("emergencyNumbers", Map.of("de", "112"));
    assertThatThrownBy(() -> service.replaceAgencyTexts(5L, centre, "a"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(repository, never()).save(any());
    verify(repository, never()).deleteById(any());
  }

  @Test
  void anOverlongTextIsRejected() {
    var tooLong = "a".repeat(ErstantwortPlatformTextService.MAX_TEXT_LENGTH + 1);
    assertThatThrownBy(
            () -> service.replacePlatformTexts(Map.of("greeting", Map.of("de", tooLong)), "a"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readReturnsTheStoredMapsPerBaustein() {
    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.PLATFORM, 0L))
        .thenReturn(List.of(row("greeting", Map.of("de", "<p>Hallo</p>"))));

    assertThat(service.readPlatformTexts())
        .containsExactly(Map.entry("greeting", Map.of("de", "<p>Hallo</p>")));
  }

  // ---- Beratungsstelle level (ADR-018 §6) ----

  private static ErstantwortTextOverride agencyRow(String baustein, Map<String, String> texts) {
    return ErstantwortTextOverride.agency(5L, baustein, texts, "admin");
  }

  @Test
  void aCentreStoresItsOwnBausteineUnderItsAgencyId() {
    service.replaceAgencyTexts(5L, Map.of("whoReadsAlong", Map.of("de", "<p>Team A</p>")), "a");

    var captor = ArgumentCaptor.forClass(ErstantwortTextOverride.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getScope()).isEqualTo(ErstantwortTextOverride.AGENCY);
    assertThat(captor.getValue().getScopeId()).isEqualTo(5L);
    assertThat(captor.getValue().textsAsMap()).containsEntry("de", "<p>Team A</p>");
  }

  @Test
  void aCentreWhoReadsAlongWithoutASourceLanguageIsRejected() {
    assertThatThrownBy(
            () ->
                service.replaceAgencyTexts(
                    5L, Map.of("whoReadsAlong", Map.of("en", "Team A")), "a"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).save(any());
    verify(repository, never()).deleteById(any());
  }

  @Test
  void aCentreWhoReadsAlongInEnglishIsSentWhenEnglishIsTheSource() {
    service.replaceAgencyTexts(
        5L,
        Map.of("whoReadsAlong", Map.of("en", "<p>Team A</p>", "en__meta", "{\"src\":\"en\"}")),
        "a");
    verify(repository).save(any());

    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.AGENCY, 5L))
        .thenReturn(
            List.of(
                agencyRow(
                    "whoReadsAlong",
                    Map.of("en", "<p>Team A</p>", "en__meta", "{\"src\":\"en\"}"))));

    assertThat(service.resolveAgencyTexts(5L, "de", false).getWhoReadsAlong()).isEqualTo("Team A");
  }

  @Test
  void aCentreMayNotRewordTheEmergencyNumbersOrTheDeadline() {
    assertThatThrownBy(
            () ->
                service.replaceAgencyTexts(5L, Map.of("emergencyNumbers", Map.of("de", "x")), "a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.replaceAgencyTexts(5L, Map.of("emergencyAddition", Map.of("de", "x")), "a"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCentresTextsResolveLikeTheTragersWithTheSameLanguageFallback() {
    when(repository.findByScopeAndScopeId(ErstantwortTextOverride.AGENCY, 5L))
        .thenReturn(
            List.of(
                agencyRow("greeting", Map.of("de", "<p>Hallo Zentrum</p>")),
                agencyRow(
                    "whoReadsAlong", Map.of("de", "<p>Team A</p>", "en", "<p>Team A en</p>"))));

    var texts = service.resolveAgencyTexts(5L, "en", false);

    assertThat(texts.getWhoReadsAlong()).isEqualTo("Team A en");
    // No English greeting: falls back to the centre's own German text, as for a Träger.
    assertThat(texts.getGreeting()).isEqualTo("Hallo Zentrum");
  }

  @Test
  void noCentreOrAFailingStoreMeansNoCentreTexts() {
    assertThat(service.resolveAgencyTexts(null, "de", false).getGreeting()).isNull();
    when(repository.findByScopeAndScopeId(any(), any())).thenThrow(new IllegalStateException("db"));
    assertThat(service.resolveAgencyTexts(5L, "de", false).getGreeting()).isNull();
  }
}
