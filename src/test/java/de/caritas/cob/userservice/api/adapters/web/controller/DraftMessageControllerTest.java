package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.DraftFeedResponse;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.DraftMessageItem;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.MigrateDraftRequest;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.UpsertDraftRequest;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

  @Test
  void migrationAndRetirementUseTheExactOpaqueWireContract() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
    when(authenticatedUser.getUserId()).thenReturn("owned-user");
    String legacyRevision = "a".repeat(43);
    String targetRevision = "b".repeat(43);
    String scope = "draft.v1." + "c".repeat(43);
    String text = "draft.v1." + "d".repeat(16) + "." + "e".repeat(32);
    var request = new MigrateDraftRequest(71L, legacyRevision, scope, text);
    TenantContext.setCurrentTenant(9L);
    try {
      mvc.perform(
              post("/users/drafts/migrate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"legacyId":71,"legacyRevision":"%s","targetScopeKey":"%s","text":"%s"}
                      """
                          .formatted(legacyRevision, scope, text)))
          .andExpect(status().isNoContent());
      verify(draftMessageService).migrateDraft("owned-user", 9L, request);

      mvc.perform(
              delete("/users/drafts/legacy/{id}", 71)
                  .param("revision", legacyRevision)
                  .param("targetScopeKey", scope)
                  .param("targetRevision", targetRevision))
          .andExpect(status().isNoContent());
      verify(draftMessageService)
          .retireLegacyDraft("owned-user", 9L, 71L, legacyRevision, scope, targetRevision);

      doThrow(new DataIntegrityViolationException("concurrent insert"))
          .when(draftMessageService)
          .migrateDraft("owned-user", 9L, request);
      mvc.perform(
              post("/users/drafts/migrate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"legacyId":71,"legacyRevision":"%s","targetScopeKey":"%s","text":"%s"}
                      """
                          .formatted(legacyRevision, scope, text)))
          .andExpect(status().isConflict());

      doThrow(new NotFoundException("Legacy draft not found"))
          .when(draftMessageService)
          .retireLegacyDraft("owned-user", 9L, 71L, legacyRevision, scope, targetRevision);
      mvc.perform(
              delete("/users/drafts/legacy/{id}", 71)
                  .param("revision", legacyRevision)
                  .param("targetScopeKey", scope)
                  .param("targetRevision", targetRevision))
          .andExpect(status().isNotFound());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void protectedRoutesDelegateOnlyToTheStrictDraftService() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
    when(authenticatedUser.getUserId()).thenReturn("owned-user");
    String path = "/users/drafts/protected";
    String scope = "draft.v1." + "a".repeat(43);
    String ciphertext = "draft.v1." + "b".repeat(16) + "." + "c".repeat(32);
    String legacyScope = "scope:room|thread:main";
    String body = "{\"text\":\"" + ciphertext + "\"}";
    TenantContext.setCurrentTenant(9L);
    try {
      mvc.perform(
              patch(path)
                  .param("scopeKey", scope)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isNoContent());
      verify(draftMessageService)
          .upsertProtectedDraft(
              eq("owned-user"), eq(scope),
              argThat(request -> ciphertext.equals(request.getText())), eq(9L));

      mvc.perform(delete(path).param("scopeKey", scope)).andExpect(status().isNoContent());
      verify(draftMessageService).deleteProtectedDraft("owned-user", scope, 9L);

      doThrow(new BadRequestException("Invalid protected draft scope"))
          .when(draftMessageService)
          .upsertProtectedDraft(eq("owned-user"), eq(legacyScope), any(), eq(9L));
      doThrow(new BadRequestException("Invalid protected draft scope"))
          .when(draftMessageService)
          .deleteProtectedDraft("owned-user", legacyScope, 9L);

      mvc.perform(
              patch(path)
                  .param("scopeKey", legacyScope)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
      mvc.perform(delete(path).param("scopeKey", legacyScope)).andExpect(status().isBadRequest());
      verify(draftMessageService, never()).upsertDraft(any(), any(), any(), any());
      verify(draftMessageService, never()).deleteDraft(any(), any());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void protectedSingleAndFeedUseNoStoreAndKeepReadsScoped() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
    when(authenticatedUser.getUserId()).thenReturn("owned-user");
    var scope = "draft.v1." + "a".repeat(43);
    var item = DraftMessageItem.builder().scopeKey(scope).text("ciphertext").build();
    when(draftMessageService.getProtectedDraft("owned-user", scope, 9L))
        .thenReturn(item, (DraftMessageItem) null);
    when(draftMessageService.getDrafts("owned-user", 0, 200))
        .thenReturn(DraftFeedResponse.builder().page(0).perPage(200).build());

    TenantContext.setCurrentTenant(9L);
    try {
      mvc.perform(get("/users/drafts/single").param("scopeKey", scope))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      mvc.perform(get("/users/drafts/single").param("scopeKey", scope))
          .andExpect(status().isNoContent())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      mvc.perform(get("/users/drafts"))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      verify(draftMessageService, never()).getDraft(any(), any());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void protectedFeedPassesOwnerTenantAndPaginationAndReportsTheAppliedLimit() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
    when(authenticatedUser.getUserId()).thenReturn("owned-user");
    when(draftMessageService.getProtectedDrafts("owned-user", 9L, 1, 200, false))
        .thenReturn(DraftFeedResponse.builder().page(1).perPage(12).build());
    when(draftMessageService.getProtectedDrafts("owned-user", 9L, 1, 200, true))
        .thenReturn(DraftFeedResponse.builder().page(1).perPage(50).build());

    TenantContext.setCurrentTenant(9L);
    try {
      mvc.perform(get("/users/drafts/protected").param("page", "1").param("perPage", "200"))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
          .andExpect(jsonPath("$.page").value(1))
          .andExpect(jsonPath("$.perPage").value(12));
      mvc.perform(
              get("/users/drafts/protected")
                  .param("page", "1")
                  .param("perPage", "200")
                  .param("legacyOnly", "true"))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
          .andExpect(jsonPath("$.page").value(1))
          .andExpect(jsonPath("$.perPage").value(50));
      verify(draftMessageService, never()).getDrafts(any(), eq(1), eq(200));
    } finally {
      TenantContext.clear();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/users/drafts", "/users/drafts/protected"})
  void protectedWriteContentionReturnsConflictWithoutLegacyFallback(String path) throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
    when(authenticatedUser.getUserId()).thenReturn("owned-user");
    var scope = "draft.v1." + "a".repeat(43);
    doThrow(new DataIntegrityViolationException("concurrent protected insert"))
        .when(draftMessageService)
        .upsertProtectedDraft(eq("owned-user"), eq(scope), any(), eq(9L));
    doThrow(new PessimisticLockingFailureException("protected row locked"))
        .when(draftMessageService)
        .deleteProtectedDraft("owned-user", scope, 9L);

    TenantContext.setCurrentTenant(9L);
    try {
      mvc.perform(
              patch(path)
                  .param("scopeKey", scope)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isConflict());
      mvc.perform(delete(path).param("scopeKey", scope)).andExpect(status().isConflict());
      verify(draftMessageService, never()).upsertDraft(any(), any(), any(), any());
      verify(draftMessageService, never()).deleteDraft(any(), any());
    } finally {
      TenantContext.clear();
    }
  }
}
