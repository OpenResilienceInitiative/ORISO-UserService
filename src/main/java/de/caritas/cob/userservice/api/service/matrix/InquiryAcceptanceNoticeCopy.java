package de.caritas.cob.userservice.api.service.matrix;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Session;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/**
 * Existing plain notification catalogue copy, snapshotted in the same conversation locale as
 * handover messages.
 */
final class InquiryAcceptanceNoticeCopy {
  record Copy(String title, String description) {}

  private static final Map<String, Copy> COPY = load();

  private InquiryAcceptanceNoticeCopy() {}

  static Copy forSession(Session session) {
    String locale = session.getLanguageCode() == null ? "de" : session.getLanguageCode().name();
    return COPY.getOrDefault(locale, COPY.get("de"));
  }

  private static Map<String, Copy> load() {
    try (var input = new ClassPathResource("inquiry-acceptance-copy.json").getInputStream()) {
      return Map.copyOf(
          new ObjectMapper().readValue(input, new TypeReference<Map<String, Copy>>() {}));
    } catch (java.io.IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
