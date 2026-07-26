package de.caritas.cob.userservice.api.port.in;

import de.caritas.cob.userservice.api.model.Chat;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

public interface Messaging {

  boolean banUserFromChat(String adviceSeekerId, long chatId);

  boolean existsChat(long id);

  Optional<Chat> findChat(long id);

  boolean removeConsultantFromSession(Long sessionId, String consultantId);

  Optional<Map<String, Object>> findSession(Long sessionId);

  boolean markAsDirectConsultant(Long sessionId);

  void setAvailability(String consultantId, boolean available);

  boolean getAvailability(String consultantId);

  long countPendingEnquiriesAheadOf(
      Long agencyId, Integer consultingTypeId, Long mainTopicId, LocalDateTime beforeDate);
}
