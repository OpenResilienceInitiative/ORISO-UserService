package de.caritas.cob.userservice.api.service.creationoutcome;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;

/** Carries the persisted identity across the transactional boundary, with safe state helpers. */
public record TrackedConsultantCreation(ConsultantAdminResponseDTO consultant, UUID operationId) {
  /** A lookup before a verified binding cannot carry an operation identity. */
  public static <T> T journalRead(java.util.function.Supplier<T> read) {
    try {
      return read.get();
    } catch (RuntimeException failure) {
      throw journalUnavailable();
    }
  }

  public static ResponseStatusException journalUnavailable() {
    return new ResponseStatusException(
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "CREATION_OPERATION_UNAVAILABLE");
  }

  public static void transition(
      CreationOperationContext context,
      CreationOperationWriter writer,
      CreationTransition transition) {
    try {
      var receipt = context.receipt();
      context.bind(writer.transition(receipt.operationId(), receipt.version(), transition));
    } catch (RuntimeException failure) {
      throw new CreationOperationFailure(
          context.receipt().operationId(), 503, Reason.JOURNAL_UNAVAILABLE);
    }
  }

  public static CreationOperationFailure failure(
      CreationOperationContext context, RuntimeException failure, Reason reason) {
    if (failure instanceof CreationOperationFailure safe) return safe;
    return new CreationOperationFailure(context.receipt().operationId(), status(failure), reason);
  }

  public static int status(RuntimeException failure) {
    if (failure instanceof CustomValidationHttpStatusException expected) {
      int status = expected.getHttpStatus().value();
      return status == 400 || status == 409 || status == 424 || status == 503 ? status : 500;
    }
    if (failure instanceof ResponseStatusException expected) {
      int status = expected.getStatusCode().value();
      return status == 400 || status == 404 || status == 409 || status == 424 || status == 503
          ? status
          : 500;
    }
    if (failure instanceof ForbiddenException
        || failure instanceof org.springframework.security.access.AccessDeniedException) return 404;
    if (failure instanceof BadRequestException || failure instanceof IllegalArgumentException)
      return 400;
    if (failure instanceof ConflictException) return 409;
    if (failure instanceof DistributedTransactionException) return 424;
    if (failure instanceof ServiceUnavailableException) return 503;
    return 500;
  }
}
