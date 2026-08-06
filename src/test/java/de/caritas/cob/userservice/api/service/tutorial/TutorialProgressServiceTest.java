package de.caritas.cob.userservice.api.service.tutorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.TutorialProgress;
import de.caritas.cob.userservice.api.port.out.TutorialProgressRepository;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService.UpsertTutorialProgressRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TutorialProgressServiceTest {

  @Mock private TutorialProgressRepository tutorialProgressRepository;
  @Mock private TutorialProgressStore tutorialProgressStore;

  @InjectMocks private TutorialProgressService service;

  private UpsertTutorialProgressRequest request(String status) {
    var req = new UpsertTutorialProgressRequest();
    req.setSurface("frontend");
    req.setTourId("consultant-walkthrough");
    req.setTourVersion(1);
    req.setStatus(status);
    req.setCurrentStepId("enquiries");
    return req;
  }

  /* --- hardening (gate run e2e-20260720-1507): unknown tours and unbounded rows --- */

  @Test
  void upsertOwnProgress_rejectsATourThatIsNotEnabledOnThatSurface() {
    // A consultant could otherwise write surface=admin rows that surface in the
    // tenant admin's aggregate dashboard with attacker-chosen labels.
    var req = request("completed");
    req.setSurface("admin");

    assertThatThrownBy(() -> service.upsertOwnProgress("user-1", 1L, req))
        .isInstanceOf(BadRequestException.class);
    verify(tutorialProgressStore, never()).upsert(any(), anyInt());
  }

  @Test
  void upsertOwnProgress_rejectsAnUnknownTourId() {
    var req = request("completed");
    req.setTourId("zz-invented-tour");

    assertThatThrownBy(() -> service.upsertOwnProgress("user-1", 1L, req))
        .isInstanceOf(BadRequestException.class);
    verify(tutorialProgressStore, never()).upsert(any(), anyInt());
  }

  @Test
  void upsertOwnProgress_createsScopedRecordForTheAuthenticatedUser() {
    // Business reason: progress is keyed by user, surface, tour and version so
    // multiple tutorials and audiences can track state independently.
    when(tutorialProgressStore.upsert(any(), anyInt())).thenAnswer(inv -> inv.getArgument(0));

    var item = service.upsertOwnProgress("user-1", 1L, request("in_progress"));

    var captor = ArgumentCaptor.forClass(TutorialProgress.class);
    verify(tutorialProgressStore).upsert(captor.capture(), anyInt());
    assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
    assertThat(captor.getValue().getSurface()).isEqualTo("frontend");
    assertThat(captor.getValue().getTourId()).isEqualTo("consultant-walkthrough");
    assertThat(captor.getValue().getTourVersion()).isEqualTo(1);
    assertThat(captor.getValue().getStatus()).isEqualTo("in_progress");
    assertThat(item.getStatus()).isEqualTo("in_progress");
  }

  @Test
  void upsertOwnProgress_completedSetsCompletionTimestamp() {
    // Business reason: completion requires an explicit final-step write and is
    // observable through the completedAt timestamp.
    when(tutorialProgressStore.upsert(any(), anyInt())).thenAnswer(inv -> inv.getArgument(0));

    var item = service.upsertOwnProgress("user-1", 1L, request("completed"));

    assertThat(item.getCompletedAt()).isNotNull();
  }

  @Test
  void upsertOwnProgress_skippedIsStoredAsSkippedNotCompleted() {
    when(tutorialProgressStore.upsert(any(), anyInt())).thenAnswer(inv -> inv.getArgument(0));

    var item = service.upsertOwnProgress("user-1", 1L, request("skipped"));

    assertThat(item.getStatus()).isEqualTo("skipped");
    assertThat(item.getCompletedAt()).isNotNull();
  }

  @Test
  void upsertOwnProgress_rejectsUnknownStatus() {
    assertThatThrownBy(() -> service.upsertOwnProgress("user-1", 1L, request("finished")))
        .isInstanceOf(BadRequestException.class);
    verify(tutorialProgressStore, never()).upsert(any(), anyInt());
  }

  @Test
  void upsertOwnProgress_rejectsUnknownSurface() {
    var req = request("in_progress");
    req.setSurface("mobile-app");

    assertThatThrownBy(() -> service.upsertOwnProgress("user-1", 1L, req))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void upsertOwnProgress_rejectsFreeTextIdentifiers() {
    // Business reason: only identifiers, status and timestamps are persisted —
    // never rendered text, URLs or arbitrary UI payloads.
    var req = request("in_progress");
    req.setTourId("Hier finden Sie eine Übersicht über alle offenen Anfragen!");

    assertThatThrownBy(() -> service.upsertOwnProgress("user-1", 1L, req))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void getOwnProgress_returnsOnlyTheRequestedSurfaceOfTheOwnUser() {
    var row =
        TutorialProgress.builder()
            .userId("user-1")
            .surface("frontend")
            .tourId("consultant-walkthrough")
            .tourVersion(2)
            .status("in_progress")
            .currentStepId("archive")
            .build();
    when(tutorialProgressRepository.findByUserIdAndSurface("user-1", "frontend"))
        .thenReturn(List.of(row));

    var items = service.getOwnProgress("user-1", "frontend");

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getTourId()).isEqualTo("consultant-walkthrough");
    assertThat(items.get(0).getTourVersion()).isEqualTo(2);
    assertThat(items.get(0).getCurrentStepId()).isEqualTo("archive");
  }
}
