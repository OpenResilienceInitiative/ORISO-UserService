package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Calculates asker activity from asker-owned signals only. */
@Service
@RequiredArgsConstructor
public class AskerActivityCalculator {

  private final @NonNull SessionRepository sessionRepository;

  public Optional<LocalDateTime> lastActivity(User user) {
    if (user == null) {
      return Optional.empty();
    }
    LocalDateTime lastSessionMessage = sessionRepository.findMaxEnquiryMessageDateByUser(user);
    LocalDateTime userUpdate = user.getUpdateDate();
    LocalDateTime userCreate = user.getCreateDate();
    return Optional.ofNullable(maxOf(userUpdate, lastSessionMessage, userCreate));
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
