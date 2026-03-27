package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Calculates consultant activity from consultant-scoped signals only. */
@Service
@RequiredArgsConstructor
public class ConsultantActivityCalculator {

  private final @NonNull SessionRepository sessionRepository;

  public Optional<LocalDateTime> lastActivity(Consultant consultant) {
    if (consultant == null) {
      return Optional.empty();
    }
    LocalDateTime consultantUpdate = consultant.getUpdateDate();
    LocalDateTime consultantCreate = consultant.getCreateDate();
    LocalDateTime sessionUpdate = sessionRepository.findMaxUpdateDateByConsultant(consultant);
    return Optional.ofNullable(maxOf(consultantUpdate, sessionUpdate, consultantCreate));
  }

  private LocalDateTime maxOf(LocalDateTime... values) {
    LocalDateTime result = null;
    for (LocalDateTime value : values) {
      if (value != null && (result == null || value.isAfter(result))) {
        result = value;
      }
    }
    return result;
  }
}
