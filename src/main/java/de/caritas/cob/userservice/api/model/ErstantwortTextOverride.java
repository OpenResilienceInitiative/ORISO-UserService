package de.caritas.cob.userservice.api.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One authored Erstantwort Baustein above or beside the Träger level (ADR-018 §6). The texts are
 * one map per Baustein, language → HTML, with {@code <lang>__meta} markers for machine translations
 * — the same shape TenantService stores for a Träger, so both editors share one publish path.
 * {@code scope} names the level: today {@link #PLATFORM} (scope id 0); a Beratungsstelle
 * (ORISO-Admin#601) is meant to use {@code AGENCY} with its agency id.
 */
@Entity
@Table(name = "erstantwort_text_override")
@IdClass(ErstantwortTextOverride.Key.class)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class ErstantwortTextOverride {

  public static final String PLATFORM = "PLATFORM";
  public static final String AGENCY = "AGENCY";
  public static final long PLATFORM_SCOPE_ID = 0L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Id
  @Column(name = "scope", length = 16, nullable = false)
  private String scope;

  @Id
  @Column(name = "scope_id", nullable = false)
  private Long scopeId;

  @Id
  @Column(name = "baustein", length = 40, nullable = false)
  private String baustein;

  @Column(name = "texts", columnDefinition = "longtext", nullable = false)
  private String texts;

  @Column(name = "updated_by", length = 255)
  private String updatedBy;

  @LastModifiedDate
  @Column(name = "update_date", columnDefinition = "datetime")
  private LocalDateTime updateDate;

  public static ErstantwortTextOverride platform(
      String baustein, Map<String, String> texts, String updatedBy) {
    var row = new ErstantwortTextOverride();
    row.setScope(PLATFORM);
    row.setScopeId(PLATFORM_SCOPE_ID);
    row.setBaustein(baustein);
    row.setTextsFromMap(texts);
    row.setUpdatedBy(updatedBy);
    return row;
  }

  public static ErstantwortTextOverride agency(
      Long agencyId, String baustein, Map<String, String> texts, String updatedBy) {
    var row = platform(baustein, texts, updatedBy);
    row.setScope(AGENCY);
    row.setScopeId(agencyId);
    return row;
  }

  public static Key agencyKey(Long agencyId, String baustein) {
    return new Key(AGENCY, agencyId, baustein);
  }

  public static Key platformKey(String baustein) {
    return new Key(PLATFORM, PLATFORM_SCOPE_ID, baustein);
  }

  public Map<String, String> textsAsMap() {
    if (texts == null || texts.isBlank()) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(texts, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("Unreadable Erstantwort texts for " + baustein, exception);
    }
  }

  public void setTextsFromMap(Map<String, String> map) {
    try {
      this.texts = MAPPER.writeValueAsString(new LinkedHashMap<>(map));
    } catch (Exception exception) {
      throw new IllegalArgumentException("Cannot store Erstantwort texts", exception);
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {
    private String scope;
    private Long scopeId;
    private String baustein;
  }
}
