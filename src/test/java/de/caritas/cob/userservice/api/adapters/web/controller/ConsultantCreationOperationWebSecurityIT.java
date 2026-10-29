package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.dto.*;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.consultant.create.AccountCreationAttemptJournal;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupIssuer;
import de.caritas.cob.userservice.api.service.creationoutcome.*;
import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.profiles.active=testing")
class ConsultantCreationOperationWebSecurityIT {
  static final String KEY = "75a491ce-2936-4a65-bb1e-d2a360af19b1";
  static final String HEADER = "X-ORISO-Creation-Operation-Key";
  static final String RECEIPT = "X-ORISO-Creation-Operation";
  static final String BODY =
      "{\"username\":\"PrivacyUserCanary\",\"password\":\"PrivacyPasswordCanary!\",\"firstname\":\"Synthetic\",\"lastname\":\"Person\",\"email\":\"PrivacyEmailCanary@example.org\",\"formalLanguage\":true,\"absent\":false,\"tenantId\":7}";
  @Autowired MockMvc mvc;
  @MockitoBean ConsultantAdminFacade facade;
  @MockitoBean ExistingAccountSetupIssuer issuer;
  @MockitoBean AuthenticatedUser caller;
  @MockitoBean AdminScope scope;
  @MockitoBean AccountCreationAttemptJournal journal;
  @MockitoBean CreationOperationWriter writer;
  static final UUID OPERATION = UUID.fromString("c56886a8-7afe-46de-b9c6-9b6c7271c051");

  void platformCaller() {
    when(caller.isPlatformAdmin()).thenReturn(true);
    when(caller.getRoles()).thenReturn(Set.of("tenant-admin", "agency-admin"));
    when(caller.getUserId()).thenReturn("actual-owner");
  }

  Receipt receipt(String owner) {
    return new Receipt(
        OPERATION,
        UUID.fromString(KEY),
        owner,
        new Scope(7, List.of(2L), "COUNSELLOR"),
        (short) 1,
        "a".repeat(64),
        3,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        "native-synthetic",
        DispatchState.NATIVE_ID_CAPTURED,
        WorkflowState.RETURNED,
        RowState.COMMITTED,
        OnboardingState.NOT_ATTEMPTED,
        CompensationState.NOT_STARTED,
        Reason.NONE);
  }

  void trackedResult() {
    platformCaller();
    when(facade.createNewConsultant(any(), any()))
        .thenAnswer(
            call -> {
              CreationOperationContext context = call.getArgument(1);
              context.bind(receipt("actual-owner"));
              return new TrackedConsultantCreation(
                  new ConsultantAdminResponseDTO()
                      .embedded(
                          new ConsultantDTO()
                              .id("native-synthetic")
                              .username("PrivacyUserCanary")
                              .email("PrivacyEmailCanary@example.org")),
                  OPERATION);
            });
    when(writer.transition(any(), anyLong(), any())).thenReturn(receipt("actual-owner"));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void trackedSuccessUsesActualPersistedIdAndPreservesHal() throws Exception {
    trackedResult();
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().isOk())
        .andExpect(header().string(RECEIPT, CreationOperationAccess.receiptUri(OPERATION)))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$._embedded.id").value("native-synthetic"));
    var ordered = inOrder(writer, issuer);
    ordered
        .verify(writer)
        .transition(
            eq(OPERATION),
            anyLong(),
            eq(new CreationTransition.Onboarding(OnboardingState.OUTCOME_UNKNOWN, Reason.NONE)));
    ordered.verify(issuer).issueAfterCreation(any(), eq("native-synthetic"), any());
    ordered
        .verify(writer)
        .transition(
            eq(OPERATION),
            anyLong(),
            eq(new CreationTransition.Onboarding(OnboardingState.ISSUER_RETURNED, Reason.NONE)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"technical", AuthorityValue.TECHNICAL_DEFAULT})
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void mixedTechnicalRolesAreDeniedEvenWhenPlatformFlagIsTrue(String technicalRole)
      throws Exception {
    platformCaller();
    when(caller.getRoles()).thenReturn(Set.of("tenant-admin", technicalRole));
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(facade, issuer, journal);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_CREATE, AuthorityValue.TECHNICAL_DEFAULT})
  void technicalAuthorityMixedWithHumanIsDenied() throws Exception {
    platformCaller();
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(facade, issuer, journal);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void lostResponseCanBeRecoveredByIdAndByKeyWithOnlySafeDimensions() throws Exception {
    platformCaller();
    when(journal.findById(OPERATION)).thenReturn(Optional.of(receipt("actual-owner")));
    when(journal.findByKey(UUID.fromString(KEY))).thenReturn(Optional.of(receipt("actual-owner")));
    for (String path :
        List.of(
            "/useradmin/consultant-creation-operations/" + OPERATION,
            "/useradmin/consultant-creation-operations/by-key/" + KEY)) {
      mvc.perform(get(path))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.operationId").value(OPERATION.toString()))
          .andExpect(jsonPath("$.nativeId").value("native-synthetic"))
          .andExpect(jsonPath("$.ownerId").doesNotExist())
          .andExpect(jsonPath("$.operationKey").doesNotExist())
          .andExpect(jsonPath("$.requestFingerprint").doesNotExist())
          .andExpect(jsonPath("$.ready").doesNotExist());
    }
    verifyNoInteractions(facade, issuer);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void crossOwnerAndChangedScopeNeverExposeReceiptEvenFromTypedFailure() throws Exception {
    platformCaller();
    when(journal.findById(OPERATION)).thenReturn(Optional.of(receipt("different-owner")));
    mvc.perform(get("/useradmin/consultant-creation-operations/" + OPERATION))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(OPERATION.toString()))));
    when(journal.findByKey(UUID.fromString(KEY)))
        .thenReturn(Optional.of(receipt("different-owner")));
    mvc.perform(get("/useradmin/consultant-creation-operations/by-key/" + KEY))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    when(facade.createNewConsultant(any(), any()))
        .thenAnswer(
            call -> {
              ((CreationOperationContext) call.getArgument(1)).bind(receipt("different-owner"));
              throw new CreationOperationFailure(OPERATION, 404, Reason.WORKFLOW_FAILED);
            });
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    when(journal.findById(OPERATION)).thenReturn(Optional.of(receipt("actual-owner")));
    doThrow(new IllegalStateException("PrivateScopeCanary")).when(scope).assertMay(any());
    mvc.perform(get("/useradmin/consultant-creation-operations/" + OPERATION))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PrivateScopeCanary"))));
    verifyNoInteractions(issuer);
  }

  @Test
  @WithMockUser(authorities = "unrelated")
  void revokedAuthorityCannotReadReceipts() throws Exception {
    platformCaller();
    mvc.perform(get("/useradmin/consultant-creation-operations/" + OPERATION))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(journal, facade, issuer);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 409, 424, 500, 503})
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void boundFailuresCarryPersistedReceiptButUnboundFailuresDoNot(int statusCode) throws Exception {
    platformCaller();
    when(facade.createNewConsultant(any(), any()))
        .thenAnswer(
            call -> {
              ((CreationOperationContext) call.getArgument(1)).bind(receipt("actual-owner"));
              throw new CreationOperationFailure(OPERATION, statusCode, Reason.WORKFLOW_FAILED);
            });
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().is(statusCode))
        .andExpect(header().string(RECEIPT, CreationOperationAccess.receiptUri(OPERATION)));
    doThrow(new CreationOperationFailure(OPERATION, statusCode, Reason.WORKFLOW_FAILED))
        .when(facade)
        .createNewConsultant(any(), any());
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().is(statusCode))
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(issuer);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void replayAndConflictReturnFixed409WithPersistedWinnerAndNoSetup(boolean conflict)
      throws Exception {
    platformCaller();
    when(facade.createNewConsultant(any(), any()))
        .thenAnswer(
            call -> {
              ((CreationOperationContext) call.getArgument(1)).bind(receipt("actual-owner"));
              throw new CreationOperationAccess.ExistingOperation(OPERATION, conflict);
            });
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().isConflict())
        .andExpect(header().string(RECEIPT, CreationOperationAccess.receiptUri(OPERATION)))
        .andExpect(
            jsonPath("$.code")
                .value(conflict ? "CREATION_OPERATION_CONFLICT" : "CREATION_OPERATION_REPLAY"));
    verifyNoInteractions(issuer, writer);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void duplicateKeysAndBoundsAreRejectedBeforeFacade() throws Exception {
    platformCaller();
    mvc.perform(creation(BODY).header(HEADER, KEY, KEY))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist(RECEIPT));
    mvc.perform(
            creation(BODY.replace("\"tenantId\":7", "\"tenantId\":7,\"agencyIds\":[-1]"))
                .header(HEADER, KEY))
        .andExpect(status().isBadRequest());
    mvc.perform(creation(BODY + " ".repeat(65536)).header(HEADER, KEY))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(facade, journal, issuer);
  }

  static MockHttpServletRequestBuilder creation(String body) {
    return post("/useradmin/consultants")
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test")
        .contentType("application/json")
        .content(body);
  }

  void legacyResult() {
    when(facade.createNewConsultant(any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("synthetic-consultant")));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void trackedTenantZeroImpostorIsDeniedBeforeCreation() throws Exception {
    legacyResult();
    when(caller.getTenantId()).thenReturn(0L);
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(issuer);
    verify(facade, never()).createNewConsultant(any());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.TECHNICAL_DEFAULT)
  void legacyTechnicalCallerStillHasHalSuccess() throws Exception {
    legacyResult();
    mvc.perform(creation(BODY))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/hal+json"));
    verify(issuer).issueAfterCreation(any(), any(), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {401, 403})
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void legacyAuthorizationStatusBodyAndReasonAreNotConcealedAsTrackedOwnership(int status)
      throws Exception {
    var failure =
        new de.caritas.cob.userservice.api.exception.httpresponses
            .CustomValidationHttpStatusException(
            de.caritas.cob.userservice.api.exception.httpresponses.customheader
                .HttpStatusExceptionReason.NUMBER_OF_LICENSES_EXCEEDED,
            org.springframework.http.HttpStatus.valueOf(status));
    failure.initCause(new IllegalStateException("PrivateLegacyAuthorizationCanary"));
    doThrow(failure).when(facade).createNewConsultant(any());
    mvc.perform(creation(BODY))
        .andExpect(status().is(status))
        .andExpect(header().string("X-Reason", "NUMBER_OF_LICENSES_EXCEEDED"))
        .andExpect(content().json("{\"reason\":\"NUMBER_OF_LICENSES_EXCEEDED\"}"))
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(issuer);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"not-found", "optimistic-spring", "optimistic-jpa", "invalid-data-access"})
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void legacyExistingNotFoundAndConcurrencyStatusStayUnchanged(String kind) throws Exception {
    RuntimeException failure =
        switch (kind) {
          case "not-found" ->
              new de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException(
                  "PrivateLegacyFailureCanary");
          case "optimistic-spring" ->
              new org.springframework.dao.OptimisticLockingFailureException(
                  "PrivateLegacyFailureCanary");
          case "optimistic-jpa" ->
              new jakarta.persistence.OptimisticLockException("PrivateLegacyFailureCanary");
          default ->
              new org.springframework.dao.InvalidDataAccessApiUsageException(
                  "PrivateLegacyFailureCanary");
        };
    doThrow(failure).when(facade).createNewConsultant(any());
    mvc.perform(creation(BODY))
        .andExpect(status().is("not-found".equals(kind) ? 404 : 409))
        .andExpect(content().string(""))
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(issuer);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void malformedKeyHasNoReceiptOrNativeEffect() throws Exception {
    legacyResult();
    mvc.perform(creation(BODY).header(HEADER, "bad-key"))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(issuer);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void unavailableReceiptLookupHasFixed503WithoutExistenceHeaders() throws Exception {
    platformCaller();
    when(journal.findById(OPERATION)).thenThrow(new IllegalStateException("PrivateJournalCanary"));
    mvc.perform(get("/useradmin/consultant-creation-operations/" + OPERATION))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().doesNotExist(RECEIPT))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("{\"code\":\"CREATION_OPERATION_UNAVAILABLE\"}"));
    verifyNoInteractions(facade, issuer);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant-admin", "agency-admin"})
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void nonPlatformAdministratorsCannotCreateOrReadTrackedOperations(String role) throws Exception {
    when(caller.getRoles()).thenReturn(Set.of(role));
    when(caller.getTenantId()).thenReturn(7L);
    when(caller.getUserId()).thenReturn("actual-owner");
    mvc.perform(creation(BODY).header(HEADER, KEY))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    mvc.perform(get("/useradmin/consultant-creation-operations/by-key/" + KEY))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(RECEIPT));
    verifyNoInteractions(facade, issuer, journal);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void legacyCreationKeepsBoundedValidationReasonWithoutRawFailure() throws Exception {
    var failure =
        new de.caritas.cob.userservice.api.exception.httpresponses
            .CustomValidationHttpStatusException(
            de.caritas.cob.userservice.api.exception.httpresponses.customheader
                .HttpStatusExceptionReason.NUMBER_OF_LICENSES_EXCEEDED);
    failure.initCause(new IllegalStateException("PrivateLegacyReasonCanary"));
    doThrow(failure).when(facade).createNewConsultant(any());
    mvc.perform(creation(BODY))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("X-Reason", "NUMBER_OF_LICENSES_EXCEEDED"))
        .andExpect(content().json("{\"reason\":\"NUMBER_OF_LICENSES_EXCEEDED\"}"))
        .andExpect(header().doesNotExist(RECEIPT))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PrivateLegacyReasonCanary"))));
    verifyNoInteractions(issuer);
  }

  @ParameterizedTest
  @ValueSource(strings = {"validation", "conflict", "tampered", "denied"})
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void preclaimReasonHeadersAreCanonicalAndNeverPassThroughPrivateMetadata(String kind)
      throws Exception {
    platformCaller();
    var validation =
        new de.caritas.cob.userservice.api.exception.httpresponses
            .CustomValidationHttpStatusException(
            de.caritas.cob.userservice.api.exception.httpresponses.customheader
                .HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE,
            kind.equals("denied")
                ? org.springframework.http.HttpStatus.NOT_FOUND
                : org.springframework.http.HttpStatus.BAD_REQUEST);
    validation.getCustomHttpHeaders().set("X-Injected", "PrivateHeaderCanary");
    if (kind.equals("tampered"))
      validation.getCustomHttpHeaders().set("X-Reason", "PrivateHeaderCanary");
    RuntimeException failure =
        kind.equals("conflict")
            ? new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
                "PrivateConflictCanary",
                de.caritas.cob.userservice.api.exception.httpresponses.customheader
                    .HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE)
            : validation;
    doThrow(failure).when(facade).createNewConsultant(any(), any());
    var result =
        mvc.perform(creation(BODY).header(HEADER, KEY))
            .andExpect(
                status().is(kind.equals("denied") ? 404 : kind.equals("conflict") ? 409 : 400))
            .andExpect(header().doesNotExist(RECEIPT))
            .andExpect(header().doesNotExist("X-Injected"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content()
                    .string(
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Private"))));
    if (kind.equals("tampered") || kind.equals("denied"))
      result.andExpect(header().doesNotExist("X-Reason"));
    else result.andExpect(header().string("X-Reason", "EMAIL_NOT_AVAILABLE"));
    verifyNoInteractions(issuer, writer, journal);
  }
}
