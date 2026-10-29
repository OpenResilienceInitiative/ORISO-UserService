package de.caritas.cob.userservice.api.service.creationoutcome;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Rechecks present authority before exposing any historical operation. */
@Service
@RequiredArgsConstructor
public class CreationOperationAccess {
  public static final String KEY_HEADER = "X-ORISO-Creation-Operation-Key";
  public static final String RECEIPT_HEADER = "X-ORISO-Creation-Operation";
  private final AuthenticatedUser caller;
  private final AdminScope scope;

  public String requireTrackedCaller() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var roles = caller.getRoles();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication.getAuthorities().stream()
            .noneMatch(a -> AuthorityValue.CONSULTANT_CREATE.equals(a.getAuthority()))
        || authentication.getAuthorities().stream()
            .anyMatch(a -> AuthorityValue.TECHNICAL_DEFAULT.equals(a.getAuthority()))
        || !caller.isPlatformAdmin()
        || roles == null
        || roles.contains(UserRole.TECHNICAL.getValue())
        || roles.contains(AuthorityValue.TECHNICAL_DEFAULT)
        || caller.getUserId() == null
        || caller.getUserId().isBlank()) throw denied();
    return caller.getUserId();
  }

  public void requireReceiptAccess(CreationOperation.Receipt receipt) {
    if (!requireTrackedCaller().equals(receipt.ownerId())) throw denied();
    requireScope(receipt.scope());
  }

  public void requireScope(CreationOperation.Scope target) {
    requireTrackedCaller();
    try {
      scope.assertMay(Target.tenant(target.tenantId()));
      scope.assertMay(Target.agencies(target.agencyIds()));
    } catch (RuntimeException denied) {
      throw denied();
    }
  }

  public static UUID parseKey(String key) {
    if (key == null
        || !key.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw invalid();
    return UUID.fromString(key);
  }

  public static void validateBounds(CreateConsultantDTO input) {
    try {
      CreationOperation.canonicalIds(input.getAgencyIds());
      CreationOperation.canonicalIds(input.getTopicIds());
      if (input.getPublicSlug() != null && input.getPublicSlug().length() > 255) throw invalid();
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  public static String receiptUri(UUID id) {
    return "/useradmin/consultant-creation-operations/" + id;
  }

  public static ResponseStatusException denied() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "CREATION_OPERATION_NOT_FOUND");
  }

  public static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CREATION_REQUEST");
  }

  /** Replay and conflict are observations, never native work or workflow failure. */
  public static final class ExistingOperation extends RuntimeException {
    private final UUID operationId;
    private final boolean conflict;

    public ExistingOperation(UUID operationId, boolean conflict) {
      super(
          conflict ? "CREATION_OPERATION_CONFLICT" : "CREATION_OPERATION_REPLAY",
          null,
          false,
          false);
      this.operationId = java.util.Objects.requireNonNull(operationId);
      this.conflict = conflict;
    }

    public UUID operationId() {
      return operationId;
    }

    public boolean conflict() {
      return conflict;
    }
  }
}
