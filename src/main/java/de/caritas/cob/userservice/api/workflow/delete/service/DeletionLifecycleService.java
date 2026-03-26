package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Security-05 lifecycle state machine and pause governance. */
@Service
@Slf4j
public class DeletionLifecycleService {

  @Value("${deletion.readOnlyWindow.hours:48}")
  private long globalReadOnlyHours;

  @Value("${deletion.readOnlyWindow.tenantOverrides:}")
  private String tenantOverrideConfig;

  @Value("${deletion.pause.defaultMonths:3}")
  private int defaultPauseMonths;

  @Value("${deletion.pause.maxMonths:12}")
  private int maxPauseMonths;

  public void beginUserDeletion(User user, String actorId) {
    if (user == null) {
      return;
    }
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (user.getDeleteDate() == null) {
      user.setDeleteDate(now);
    }
    user.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);
    transitionToReadOnlySafeguard(user, actorId);
  }

  public void beginConsultantDeletion(Consultant consultant, String actorId) {
    if (consultant == null) {
      return;
    }
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (consultant.getDeleteDate() == null) {
      consultant.setDeleteDate(now);
    }
    consultant.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);
    transitionToReadOnlySafeguard(consultant, actorId);
  }

  public User normalizeUserLifecycle(User user) {
    if (user == null || user.getDeleteDate() == null) {
      return user;
    }
    if (user.getDeletionLifecycleState() == null || user.getDeletionLifecycleState() == DeletionLifecycleState.ACTIVE) {
      user.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);
    }
    if (user.getDeletionLifecycleState() == DeletionLifecycleState.PENDING_DELETION) {
      transitionToReadOnlySafeguard(user, user.getDeletionPausedBy());
    }
    clearExpiredPauseIfNecessary(user);
    return user;
  }

  public Consultant normalizeConsultantLifecycle(Consultant consultant) {
    if (consultant == null || consultant.getDeleteDate() == null) {
      return consultant;
    }
    if (consultant.getDeletionLifecycleState() == null
        || consultant.getDeletionLifecycleState() == DeletionLifecycleState.ACTIVE) {
      consultant.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);
    }
    if (consultant.getDeletionLifecycleState() == DeletionLifecycleState.PENDING_DELETION) {
      transitionToReadOnlySafeguard(consultant, consultant.getDeletionPausedBy());
    }
    clearExpiredPauseIfNecessary(consultant);
    return consultant;
  }

  public boolean isReadyForHardDelete(User user) {
    return isReadyForHardDelete(
        user != null ? user.getDeletionLifecycleState() : null,
        user != null ? user.getDeletionReadOnlyUntil() : null,
        user != null ? user.getDeletionPausedUntil() : null);
  }

  public boolean isReadyForHardDelete(Consultant consultant) {
    return isReadyForHardDelete(
        consultant != null ? consultant.getDeletionLifecycleState() : null,
        consultant != null ? consultant.getDeletionReadOnlyUntil() : null,
        consultant != null ? consultant.getDeletionPausedUntil() : null);
  }

  public void pauseUserDeletion(User user, String reason, Integer requestedMonths, String pausedBy) {
    if (user == null) {
      return;
    }
    ensurePauseReason(reason);
    int months = normalizedPauseMonths(requestedMonths);
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    user.setDeletionPauseReason(reason.trim());
    user.setDeletionPausedBy(pausedBy);
    user.setDeletionPauseCreatedAt(now);
    user.setDeletionPausedUntil(now.plusMonths(months));
    if (user.getDeletionLifecycleState() == null || user.getDeletionLifecycleState() == DeletionLifecycleState.ACTIVE) {
      user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    }
  }

  public void pauseConsultantDeletion(
      Consultant consultant, String reason, Integer requestedMonths, String pausedBy) {
    if (consultant == null) {
      return;
    }
    ensurePauseReason(reason);
    int months = normalizedPauseMonths(requestedMonths);
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    consultant.setDeletionPauseReason(reason.trim());
    consultant.setDeletionPausedBy(pausedBy);
    consultant.setDeletionPauseCreatedAt(now);
    consultant.setDeletionPausedUntil(now.plusMonths(months));
    if (consultant.getDeletionLifecycleState() == null
        || consultant.getDeletionLifecycleState() == DeletionLifecycleState.ACTIVE) {
      consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    }
  }

  private void transitionToReadOnlySafeguard(User user, String actorId) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (user.getDeletionReadOnlyUntil() == null) {
      user.setDeletionReadOnlyUntil(now.plusHours(resolveReadOnlyHours(user.getTenantId())));
    }
    user.setDeletionPausedBy(actorId);
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  private void transitionToReadOnlySafeguard(Consultant consultant, String actorId) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (consultant.getDeletionReadOnlyUntil() == null) {
      consultant.setDeletionReadOnlyUntil(now.plusHours(resolveReadOnlyHours(consultant.getTenantId())));
    }
    consultant.setDeletionPausedBy(actorId);
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  private boolean isReadyForHardDelete(
      DeletionLifecycleState state, LocalDateTime readOnlyUntil, LocalDateTime pausedUntil) {
    if (state != DeletionLifecycleState.READ_ONLY_SAFEGUARD) {
      return false;
    }
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (readOnlyUntil == null || readOnlyUntil.isAfter(now)) {
      return false;
    }
    return pausedUntil == null || !pausedUntil.isAfter(now);
  }

  private void clearExpiredPauseIfNecessary(User user) {
    if (user.getDeletionPausedUntil() == null) {
      return;
    }
    if (user.getDeletionPausedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
      return;
    }
    log.info(
        "Deletion pause expired for userId={} tenantId={}; account is eligible for final decision.",
        user.getUserId(),
        user.getTenantId());
  }

  private void clearExpiredPauseIfNecessary(Consultant consultant) {
    if (consultant.getDeletionPausedUntil() == null) {
      return;
    }
    if (consultant.getDeletionPausedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
      return;
    }
    log.info(
        "Deletion pause expired for consultantId={} tenantId={}; account is eligible for final decision.",
        consultant.getId(),
        consultant.getTenantId());
  }

  private int normalizedPauseMonths(Integer requestedMonths) {
    int months = requestedMonths == null ? defaultPauseMonths : requestedMonths;
    if (months < 1 || months > maxPauseMonths) {
      throw new BadRequestException(
          String.format("Pause duration must be between 1 and %d month(s).", maxPauseMonths));
    }
    return months;
  }

  private void ensurePauseReason(String reason) {
    if (reason == null || reason.trim().isEmpty()) {
      throw new BadRequestException("A pause reason is required.");
    }
  }

  private long resolveReadOnlyHours(Long tenantId) {
    if (tenantId == null || tenantOverrideConfig == null || tenantOverrideConfig.isBlank()) {
      return globalReadOnlyHours;
    }
    Map<Long, Long> overrides =
        Arrays.stream(tenantOverrideConfig.split(","))
            .map(String::trim)
            .filter(value -> value.contains(":"))
            .map(value -> value.split(":"))
            .filter(parts -> parts.length == 2)
            .collect(
                Collectors.toMap(
                    parts -> parseLong(parts[0], -1L),
                    parts -> parseLong(parts[1], globalReadOnlyHours),
                    (first, second) -> second));
    return overrides.getOrDefault(tenantId, globalReadOnlyHours);
  }

  private long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}
