package de.caritas.cob.userservice.api.service.erstantwort;

import de.caritas.cob.userservice.api.model.ErstantwortTextOverride;
import de.caritas.cob.userservice.api.port.out.ErstantwortTextOverrideRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform level of the Erstantwort chain (ADR-018 §6): Beratungsstelle ?? Träger ?? platform
 * admin ?? built-in Caritas default. A platform admin rewords the built-in default of {@link
 * ErstantwortTranslations#PLATFORM_EDITABLE}; nothing else, and never who reads along (#1565).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErstantwortPlatformTextService {

  static final int MAX_TEXT_LENGTH = 10_000;
  private static final String META_SUFFIX = "__meta";

  /** What a Beratungsstelle may author; emergency addition and deadline stay with the Träger. */
  public static final java.util.List<String> AGENCY_EDITABLE =
      java.util.List.of("greeting", "whoReadsAlong", "freeNotice", "closing");

  private static final Set<String> LANGUAGES =
      Set.of("de", "de@informal", "en", "fr", "ru", "tr", "ti");

  private final @NonNull ErstantwortTextOverrideRepository repository;

  /** The stored maps per Baustein, HTML and {@code __meta} markers included, for the editor. */
  @Transactional(readOnly = true)
  public Map<String, Map<String, String>> readPlatformTexts() {
    var result = new LinkedHashMap<String, Map<String, String>>();
    platformRows().forEach(row -> result.put(row.getBaustein(), row.textsAsMap()));
    return result;
  }

  /**
   * Each given map replaces the stored one of its Baustein; an empty language ("") is removed, and
   * a map without any text resets the Baustein to the built-in default. Bausteine not named stay.
   *
   * @throws IllegalArgumentException for a Baustein or language outside the catalogue, or an
   *     overlong text; nothing is stored then
   */
  @Transactional
  public void replacePlatformTexts(Map<String, Map<String, String>> changes, String actor) {
    var validated = new LinkedHashMap<String, Map<String, String>>();
    changes.forEach(
        (baustein, texts) ->
            validated.put(
                baustein, validated(baustein, texts, ErstantwortTranslations.PLATFORM_EDITABLE)));
    validated.forEach(
        (baustein, texts) -> {
          if (texts.keySet().stream().noneMatch(key -> !key.endsWith(META_SUFFIX))) {
            // Spring Data ignores a missing row, so a reset of an untouched Baustein is a no-op.
            repository.deleteById(ErstantwortTextOverride.platformKey(baustein));
            return;
          }
          repository.save(ErstantwortTextOverride.platform(baustein, texts, actor));
        });
  }

  /**
   * Plain texts for one reader, keyed by Baustein id. Only the reader's own language counts: a
   * missing translation means the built-in default in that language, never another language's text.
   * Never throws — the platform level must not cost anyone their enquiry.
   */
  public Map<String, String> resolvePlatformTexts(String locale, boolean informal) {
    var key = ErstantwortTranslations.validLocale(locale);
    if (key == null) key = "de";
    if (key.equals("de") && informal) key = "de@informal";
    try {
      var result = new LinkedHashMap<String, String>();
      for (var row : platformRows()) {
        var plain = ErstantwortTenantTextResolver.toPlainText(row.textsAsMap().get(key));
        if (!plain.isEmpty()) result.put(row.getBaustein(), plain);
      }
      return result;
    } catch (RuntimeException exception) {
      log.warn(
          "Erstantwort platform texts unavailable, the built-in defaults are sent ({})",
          exception.getClass().getSimpleName());
      return Map.of();
    }
  }

  /**
   * A Beratungsstelle's stored maps per Baustein, for its editor. Access is checked by the caller.
   */
  @Transactional(readOnly = true)
  public Map<String, Map<String, String>> readAgencyTexts(Long agencyId) {
    var result = new LinkedHashMap<String, Map<String, String>>();
    repository
        .findByScopeAndScopeId(ErstantwortTextOverride.AGENCY, agencyId)
        .forEach(row -> result.put(row.getBaustein(), row.textsAsMap()));
    return result;
  }

  /** Same semantics as {@link #replacePlatformTexts}, for one Beratungsstelle. */
  @Transactional
  public void replaceAgencyTexts(
      Long agencyId, Map<String, Map<String, String>> changes, String actor) {
    var validated = new LinkedHashMap<String, Map<String, String>>();
    changes.forEach(
        (baustein, texts) -> validated.put(baustein, validated(baustein, texts, AGENCY_EDITABLE)));
    validated.forEach(
        (baustein, texts) -> {
          if (texts.keySet().stream().allMatch(key -> key.endsWith(META_SUFFIX))) {
            repository.deleteById(ErstantwortTextOverride.agencyKey(agencyId, baustein));
            return;
          }
          repository.save(ErstantwortTextOverride.agency(agencyId, baustein, texts, actor));
        });
  }

  /**
   * A Beratungsstelle's texts for one reader, with the Träger's language fallback (reader's locale,
   * source language, English, German) — the centre writes in its own voice like a Träger. Never
   * throws.
   */
  public ErstantwortTenantTexts resolveAgencyTexts(Long agencyId, String locale, boolean informal) {
    if (agencyId == null) return ErstantwortTenantTexts.none();
    try {
      var maps = new LinkedHashMap<String, Map<String, String>>();
      repository
          .findByScopeAndScopeId(ErstantwortTextOverride.AGENCY, agencyId)
          .forEach(row -> maps.put(row.getBaustein(), row.textsAsMap()));
      return ErstantwortTenantTexts.builder()
          .greeting(ErstantwortTenantTextResolver.pick(maps.get("greeting"), locale, informal))
          .whoReadsAlong(
              ErstantwortTenantTextResolver.pickStated(maps.get("whoReadsAlong"), locale, informal))
          .freeNotice(ErstantwortTenantTextResolver.pick(maps.get("freeNotice"), locale, informal))
          .closing(ErstantwortTenantTextResolver.pick(maps.get("closing"), locale, informal))
          .build();
    } catch (RuntimeException exception) {
      log.warn(
          "Erstantwort texts of centre {} unavailable, the Träger's apply ({})",
          agencyId,
          exception.getClass().getSimpleName());
      return ErstantwortTenantTexts.none();
    }
  }

  private Iterable<ErstantwortTextOverride> platformRows() {
    return repository.findByScopeAndScopeId(
        ErstantwortTextOverride.PLATFORM, ErstantwortTextOverride.PLATFORM_SCOPE_ID);
  }

  private static Map<String, String> validated(
      String baustein, Map<String, String> texts, java.util.List<String> editable) {
    if (!editable.contains(baustein)) {
      throw new IllegalArgumentException("Baustein not editable at this level");
    }
    var result = new LinkedHashMap<String, String>();
    if (texts == null) return result;
    texts.forEach(
        (key, value) -> {
          var meta = key != null && key.endsWith(META_SUFFIX);
          var language = meta ? key.substring(0, key.length() - META_SUFFIX.length()) : key;
          if (!LANGUAGES.contains(language)) {
            throw new IllegalArgumentException("Unsupported Erstantwort language");
          }
          if (value != null && value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Erstantwort text too long");
          }
          if (StringUtils.isBlank(value)) return;
          if (meta) {
            result.put(key, value);
          } else {
            var clean = Jsoup.clean(value, Safelist.basic());
            if (!ErstantwortTenantTextResolver.toPlainText(clean).isEmpty()) {
              result.put(key, clean);
            }
          }
        });
    // A marker without its text would describe a translation that no longer exists.
    result
        .keySet()
        .removeIf(
            key ->
                key.endsWith(META_SUFFIX)
                    && !result.containsKey(key.substring(0, key.length() - META_SUFFIX.length())));
    return result;
  }
}
