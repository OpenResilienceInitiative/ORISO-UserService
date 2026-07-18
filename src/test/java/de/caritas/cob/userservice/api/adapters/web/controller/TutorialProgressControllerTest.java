package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService.TutorialProgressItem;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService.UpsertTutorialProgressRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TutorialProgressControllerTest {

  @Mock private TutorialProgressService tutorialProgressService;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private TutorialProgressController controller;

  @Test
  void getOwnProgress_delegatesWithTheAuthenticatedUserId() {
    // Business reason: clients can only ever read their own tutorial history —
    // the user id comes from the token, never from the request.
    var item = TutorialProgressItem.builder().tourId("consultant-walkthrough").build();
    when(authenticatedUser.getUserId()).thenReturn("user-1");
    when(tutorialProgressService.getOwnProgress("user-1", "frontend")).thenReturn(List.of(item));

    var response = controller.getOwnProgress("frontend");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsExactly(item);
  }

  @Test
  void upsertOwnProgress_delegatesWithTheAuthenticatedUserId() {
    var request = new UpsertTutorialProgressRequest();
    request.setSurface("frontend");
    var item = TutorialProgressItem.builder().status("in_progress").build();
    when(authenticatedUser.getUserId()).thenReturn("user-1");
    when(tutorialProgressService.upsertOwnProgress("user-1", null, request)).thenReturn(item);

    var response = controller.upsertOwnProgress(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(item);
    verify(tutorialProgressService).upsertOwnProgress("user-1", null, request);
  }
}
