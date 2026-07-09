package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.DraftFeedResponse;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.DraftMessageItem;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.UpsertDraftRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class DraftMessageControllerTest {

  @Mock private DraftMessageService draftMessageService;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private DraftMessageController controller;

  @Test
  void getDrafts_defaultPagination_delegatesWithDefaultsAndUserId() {
    // Business reason: clients rely on deterministic default paging to render draft lists quickly.
    var feed = DraftFeedResponse.builder().page(0).perPage(200).build();
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(draftMessageService.getDrafts("u-1", 0, 200)).thenReturn(feed);

    var response = controller.getDrafts(0, 200);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(feed, response.getBody());
    verify(draftMessageService).getDrafts("u-1", 0, 200);
  }

  @Test
  void getDrafts_customPagination_delegatesWithProvidedValues() {
    // Business reason: UI paging controls must map exactly to backend draft query inputs.
    var feed = DraftFeedResponse.builder().page(2).perPage(50).build();
    when(authenticatedUser.getUserId()).thenReturn("u-2");
    when(draftMessageService.getDrafts("u-2", 2, 50)).thenReturn(feed);

    var response = controller.getDrafts(2, 50);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(feed, response.getBody());
    verify(draftMessageService).getDrafts("u-2", 2, 50);
  }

  @Test
  void getDrafts_parameters_haveMinValidationAnnotations() throws Exception {
    // Business reason: pagination lower bounds prevent invalid or costly draft queries.
    Method method = DraftMessageController.class.getMethod("getDrafts", int.class, int.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(Min.class));
    assertTrue(method.getParameters()[1].isAnnotationPresent(Min.class));
  }

  @Test
  void getDraft_existingDraft_returnsOkWithBody() {
    // Business reason: existing autosave must round-trip so users recover unfinished messages.
    var item = DraftMessageItem.builder().id(1L).scopeKey("room-1").text("draft").build();
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(draftMessageService.getDraft("u-1", "room-1")).thenReturn(item);

    var response = controller.getDraft("room-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(item, response.getBody());
  }

  @Test
  void getDraft_nullResult_returnsNoContent() {
    // Business reason: absent drafts should be represented as 204, not hard errors.
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(draftMessageService.getDraft("u-1", "room-404")).thenReturn(null);

    var response = controller.getDraft("room-404");

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
  }

  @Test
  void getDraft_scopeKeyParameter_hasNotBlankAnnotation() throws Exception {
    // Business reason: scope keys must never be empty to avoid cross-thread draft ambiguity.
    Method method = DraftMessageController.class.getMethod("getDraft", String.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(NotBlank.class));
  }

  @Test
  void upsertDraft_happyPath_returnsNoContentAndDelegates() {
    // Business reason: chat autosave writes must be non-blocking and acknowledge quickly.
    var request = new UpsertDraftRequest();
    request.setText("hello");
    when(authenticatedUser.getUserId()).thenReturn("u-1");

    var response = controller.upsertDraft("room-1", request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(draftMessageService).upsertDraft(eq("u-1"), eq("room-1"), eq(request), any());
  }

  @Test
  void upsertDraft_dataAccessExceptionStillReturnsNoContent() {
    // Business reason: concurrent autosave conflicts must not break typing flows for users.
    var request = new UpsertDraftRequest();
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    doThrow(new DataIntegrityViolationException("duplicate"))
        .when(draftMessageService)
        .upsertDraft(eq("u-1"), eq("room-1"), eq(request), any());

    var response = controller.upsertDraft("room-1", request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
  }

  @Test
  void deleteDraft_happyPath_returnsNoContentAndDelegatesWithCapturedArguments() {
    // Business reason: deleting stale drafts must target exactly the active user and scope.
    when(authenticatedUser.getUserId()).thenReturn("u-3");
    ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> scopeCaptor = ArgumentCaptor.forClass(String.class);

    var response = controller.deleteDraft("room-9");

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(draftMessageService).deleteDraft(userCaptor.capture(), scopeCaptor.capture());
    assertEquals("u-3", userCaptor.getValue());
    assertEquals("room-9", scopeCaptor.getValue());
  }

  @Test
  void deleteDraft_scopeKeyParameter_hasNotBlankAnnotation() throws Exception {
    // Business reason: deletion endpoint must reject empty identifiers to avoid accidental wipes.
    Method method = DraftMessageController.class.getMethod("deleteDraft", String.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(NotBlank.class));
  }
}
