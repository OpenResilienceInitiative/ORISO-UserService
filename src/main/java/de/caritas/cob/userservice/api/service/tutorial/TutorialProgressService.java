package de.caritas.cob.userservice.api.service.tutorial;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.TutorialProgress;
import de.caritas.cob.userservice.api.port.out.TutorialProgressRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned tutorial progress for the authenticated user (epic TOUR-03). Writes are always scoped
 * to the caller's own user id — no client can write another user's progress. Identifiers are
 * strictly validated so only ids, status and timestamps are ever persisted.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TutorialProgressService {

  private static final Set<String> SURFACES = Set.of("frontend", "admin");
  private static final Set<String> STATUSES =
      Set.of("not_started", "in_progress", "completed", "skipped");
  private static final Pattern IDENTIFIER = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

  private final @NonNull TutorialProgressRepository tutorialProgressRepository;

  @Transactional
  public TutorialProgressItem upsertOwnProgress(
      String userId, Long tenantId, UpsertTutorialProgressRequest request) {
    validate(request);

    var now = LocalDateTime.now();
    var progress =
        tutorialProgressRepository
            .findByUserIdAndSurfaceAndTourIdAndTourVersion(
                userId, request.getSurface(), request.getTourId(), request.getTourVersion())
            .orElseGet(
                () ->
                    TutorialProgress.builder()
                        .userId(userId)
                        .surface(request.getSurface())
                        .tourId(request.getTourId())
                        .tourVersion(request.getTourVersion())
                        .createDate(now)
                        .startedAt(now)
                        .tenantId(tenantId)
                        .build());

    progress.setStatus(request.getStatus());
    progress.setCurrentStepId(request.getCurrentStepId());
    progress.setUpdateDate(now);
    if (isTerminal(request.getStatus())) {
      progress.setCompletedAt(now);
    } else {
      progress.setCompletedAt(null);
    }

    return toItem(tutorialProgressRepository.save(progress));
  }

  @Transactional(readOnly = true)
  public List<TutorialProgressItem> getOwnProgress(String userId, String surface) {
    if (!SURFACES.contains(surface)) {
      throw new BadRequestException("unknown tutorial surface");
    }
    return tutorialProgressRepository.findByUserIdAndSurface(userId, surface).stream()
        .map(this::toItem)
        .toList();
  }

  private static boolean isTerminal(String status) {
    return "completed".equals(status) || "skipped".equals(status);
  }

  private void validate(UpsertTutorialProgressRequest request) {
    if (request == null) {
      throw new BadRequestException("missing tutorial progress payload");
    }
    if (request.getSurface() == null || !SURFACES.contains(request.getSurface())) {
      throw new BadRequestException("unknown tutorial surface");
    }
    if (request.getStatus() == null || !STATUSES.contains(request.getStatus())) {
      throw new BadRequestException("unknown tutorial status");
    }
    if (request.getTourId() == null || !IDENTIFIER.matcher(request.getTourId()).matches()) {
      throw new BadRequestException("tourId must be a short lowercase identifier");
    }
    if (request.getCurrentStepId() != null
        && !IDENTIFIER.matcher(request.getCurrentStepId()).matches()) {
      throw new BadRequestException("currentStepId must be a short lowercase identifier");
    }
    if (request.getTourVersion() == null || request.getTourVersion() < 1) {
      throw new BadRequestException("tourVersion must be a positive integer");
    }
  }

  private TutorialProgressItem toItem(TutorialProgress progress) {
    return TutorialProgressItem.builder()
        .tourId(progress.getTourId())
        .tourVersion(progress.getTourVersion())
        .surface(progress.getSurface())
        .status(progress.getStatus())
        .currentStepId(progress.getCurrentStepId())
        .startedAt(progress.getStartedAt())
        .completedAt(progress.getCompletedAt())
        .build();
  }

  @Getter
  @Setter
  public static class UpsertTutorialProgressRequest {
    private String surface;
    private String tourId;
    private Integer tourVersion;
    private String status;
    private String currentStepId;
  }

  @Getter
  @Builder
  public static class TutorialProgressItem {
    private final String tourId;
    private final Integer tourVersion;
    private final String surface;
    private final String status;
    private final String currentStepId;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;
  }
}
