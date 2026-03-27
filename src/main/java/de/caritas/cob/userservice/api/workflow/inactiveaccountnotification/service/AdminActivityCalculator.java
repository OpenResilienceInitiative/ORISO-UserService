package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import de.caritas.cob.userservice.api.model.Admin;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Calculates admin activity from admin-scoped profile updates only. */
@Service
public class AdminActivityCalculator {

  public Optional<LocalDateTime> lastActivity(Admin admin) {
    if (admin == null) {
      return Optional.empty();
    }
    LocalDateTime update = admin.getUpdateDate();
    LocalDateTime create = admin.getCreateDate();
    if (update == null) {
      return Optional.ofNullable(create);
    }
    if (create == null || update.isAfter(create)) {
      return Optional.of(update);
    }
    return Optional.of(create);
  }
}
