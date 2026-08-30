package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CreateEnquiryMessageException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionInfo;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NoContentException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationCompensationException;
import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationUpstreamException;
import java.util.List;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

class ApiResponseEntityExceptionHandlerTest {

  private final ApiResponseEntityExceptionHandler handler = new ApiResponseEntityExceptionHandler();
  private final WebRequest request = mock(WebRequest.class);

  @Test
  void handleCustomBadRequest_badRequestException_returnsBadRequest() {
    // Business reason: clients must receive the semantic validation reason, not a silent failure.
    var response = handler.handleCustomBadRequest(new BadRequestException("bad"), request);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("bad", body.get("message"));
  }

  @Test
  void handleCustomBadRequest_customValidationException_returnsConfiguredStatusAndReasonBody() {
    // Business reason: validation reasons must be exposed in a stable response contract.
    var ex =
        new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE, HttpStatus.CONFLICT);
    var response = handler.handleCustomBadRequest(ex, request);

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals("USERNAME_NOT_AVAILABLE", response.getHeaders().getFirst("X-Reason"));
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("USERNAME_NOT_AVAILABLE", body.get("reason"));
  }

  @Test
  void handleSmtpSendFailure_returnsBadGatewayWithMachineReadableReason() {
    // TEN-INV-U6 (#890): a failed SMTP handover must never look like success to any client.
    var ex = new de.caritas.cob.userservice.api.exception.SmtpSendException("handover failed");
    var response = handler.handleSmtpSendFailure(ex, request);

    assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("SMTP_SEND_FAILED", body.get("reason"));
  }

  @Test
  void handleAccountInviteLinkGone_returnsGoneWithDistinctReason() {
    // TEN-INV-U6 (#890): consumed/revoked/expired links surface a distinct machine-readable code.
    var ex =
        new de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException(
            de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException.Reason
                .CONSUMED);
    var response = handler.handleAccountInviteLinkGone(ex, request);

    assertEquals(HttpStatus.GONE, response.getStatusCode());
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("CONSUMED", body.get("reason"));
  }

  @Test
  void handleJPAConstraintViolationException_usernameConflict_returnsConflictReason() {
    // Business reason: duplicate usernames must return machine-readable conflict reasons.
    var ex = new ConstraintViolationException("duplicate username", null, null);
    var response = handler.handleJPAConstraintViolationException(ex, request);

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals("USERNAME_NOT_AVAILABLE", response.getHeaders().getFirst("X-Reason"));
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("USERNAME_NOT_AVAILABLE", body.get("reason"));
  }

  @Test
  void handleJPAConstraintViolationException_nullMessage_returnsConflictWithoutReason() {
    // Business reason: malformed persistence errors should still produce deterministic HTTP status.
    var ex = new ConstraintViolationException(null, null, null);
    var response = handler.handleJPAConstraintViolationException(ex, request);

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertNull(response.getBody());
    assertNull(response.getHeaders().getFirst("X-Reason"));
  }

  @Test
  void handleDistributedTransactionException_returnsFailedDependencyWithReasonHeader() {
    // Business reason: partial transaction failures need explicit step metadata for support teams.
    var info =
        DistributedTransactionInfo.builder()
            .name("create-consultant")
            .completedTransactionalOperations(
                List.of(TransactionalStep.CREATE_CONSULTANT_IN_MARIADB))
            .failedStep(TransactionalStep.CREATE_ACCOUNT_IN_KEYCLOAK)
            .build();
    var ex = new DistributedTransactionException(new RuntimeException("boom"), info);
    var response = handler.handleDistributedTransactionException(ex, request);

    assertEquals(HttpStatus.FAILED_DEPENDENCY, response.getStatusCode());
    assertEquals(
        "DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_CREATE_ACCOUNT_IN_KEYCLOAK",
        response.getHeaders().getFirst("X-Reason"));
  }

  @Test
  void handleIdentityReactivationUpstreamFailure_returnsBadGateway() {
    var response =
        handler.handleIdentityReactivationUpstreamFailure(
            new IdentityReactivationUpstreamException("Keycloak failed", new RuntimeException()),
            request);

    assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("IDENTITY_REACTIVATION_UPSTREAM_FAILED", body.get("reason"));
  }

  @Test
  void handleIdentityReactivationCompensationFailure_returnsFailedDependency() {
    var response =
        handler.handleIdentityReactivationCompensationFailure(
            new IdentityReactivationCompensationException(
                "disable failed", new RuntimeException(), new RuntimeException()),
            request);

    assertEquals(HttpStatus.FAILED_DEPENDENCY, response.getStatusCode());
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("IDENTITY_REACTIVATION_COMPENSATION_FAILED", body.get("reason"));
  }

  @Test
  void handleCreateEnquiryMessageException_returnsBadRequest() {
    // Business reason: broken enquiry payloads should map to client-fixable 400 responses.
    var response =
        handler.handleCreateEnquiryMessageException(
            new CreateEnquiryMessageException("bad"), request);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void handleAccessDenied_returnsForbidden() {
    var response =
        handler.handleAccessDenied(new AccessDeniedException("cross-tenant request"), request);

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
  }

  @Test
  void handleBadRequest_constraintViolation_returnsBadRequest() {
    // Business reason: bean validation failures must not leak as server errors.
    var response =
        handler.handleBadRequest(
            new jakarta.validation.ConstraintViolationException("invalid", null), request);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void handleHttpMessageNotReadable_returnsGivenStatus() {
    // Business reason: malformed JSON should preserve framework-selected status for API clients.
    var response =
        handler.handleHttpMessageNotReadable(
            new org.springframework.http.converter.HttpMessageNotReadableException(
                "oops", mock(HttpInputMessage.class)),
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            request);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void handleMethodArgumentNotValid_returnsGivenStatus() {
    // Business reason: invalid method arguments should keep consistent HTTP 400 behavior.
    var response =
        handler.handleMethodArgumentNotValid(
            mock(MethodArgumentNotValidException.class),
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            request);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void mappedExceptionHandlers_returnExpectedHttpStatus() {
    // Business reason: each domain exception type must map to the documented status code.
    assertEquals(
        HttpStatus.CONFLICT,
        handler
            .handleConflict(new InvalidDataAccessApiUsageException("c"), request)
            .getStatusCode());
    assertEquals(
        HttpStatus.CONFLICT,
        handler.handleCustomConflict(new ConflictException("c"), request).getStatusCode());
    assertEquals(
        HttpStatus.FORBIDDEN,
        handler.handleForbidden(new ForbiddenException("f"), request).getStatusCode());
    assertEquals(
        HttpStatus.NOT_FOUND,
        handler.handleForbidden(new NotFoundException("n"), request).getStatusCode());
    assertEquals(
        HttpStatus.INTERNAL_SERVER_ERROR,
        handler.handleInternal(new IllegalStateException("ise"), request).getStatusCode());
    assertEquals(
        HttpStatus.INTERNAL_SERVER_ERROR,
        handler.handleInternal(new InternalServerErrorException("ise"), request).getStatusCode());
    assertEquals(
        HttpStatus.NO_CONTENT,
        handler.handleInternal(new NoContentException("none"), request).getStatusCode());
  }

  @Test
  void handleExceptionInternal_internalServerError_setsRequestAttribute() {
    // Business reason: internal errors must populate servlet attributes for downstream error
    // handling.
    var ex = new RuntimeException("boom");
    var response =
        handler.handleExceptionInternal(
            ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    verify(request).setAttribute("jakarta.servlet.error.exception", ex, 0);
  }
}
