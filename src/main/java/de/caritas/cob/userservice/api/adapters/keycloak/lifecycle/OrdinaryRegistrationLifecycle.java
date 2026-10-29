package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.exception.matrix.MatrixUserInUseException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;

/**
 * Used after CreateUserFacade's real tenant, agency, DPA and invitation validation. Default-off.
 */
public final class OrdinaryRegistrationLifecycle {
  private final AccountLifecycleCoordinator coordinator;
  private final AccountLifecycleJournal journal;
  private final IdentityProvisioningCommands commands;
  private final JdbcOwnedLifecycleEffects effects;
  private final MatrixSynapseService matrix;
  private final MatrixConfig matrixConfig;
  private final String initialAskerGroup;

  public OrdinaryRegistrationLifecycle(
      AccountLifecycleCoordinator coordinator,
      AccountLifecycleJournal journal,
      IdentityProvisioningCommands commands,
      JdbcOwnedLifecycleEffects effects,
      MatrixSynapseService matrix,
      MatrixConfig matrixConfig,
      String initialAskerGroup) {
    if (initialAskerGroup == null || initialAskerGroup.isBlank())
      throw new IllegalArgumentException("Reviewed ordinary asker group is required");
    this.coordinator = coordinator;
    this.journal = journal;
    this.commands = commands;
    this.effects = effects;
    this.matrix = matrix;
    this.matrixConfig = matrixConfig;
    this.initialAskerGroup = initialAskerGroup;
  }

  public record Session(AccountLifecycleJournal.Attempt attempt, AccountProjection projection) {}

  public <T> T register(UserDTO dto, Long verifiedTenant, Function<Session, T> localWrites) {
    if (dto.getTenantId() != null && !Objects.equals(verifiedTenant, dto.getTenantId()))
      throw new org.springframework.security.access.AccessDeniedException(
          "Registration tenant differs from verified scope");
    UUID id = UUID.randomUUID();
    var agencies =
        dto.getAgencyId() == null ? List.<String>of() : List.of(dto.getAgencyId().toString());
    var scope =
        new CreationScope(
            verifiedTenant == null ? null : verifiedTenant.toString(),
            "ASKER",
            initialAskerGroup,
            new Origin(
                "REGISTRATION",
                id.toString(),
                (dto.getAgencyId() == null
                        ? "tenantless-registration"
                        : "registration-agency:" + dto.getAgencyId())
                    + (dto.getGroupChatId() == null ? "" : "/group-invite:" + dto.getGroupChatId()),
                null,
                null,
                null,
                agencies));
    var account =
        new AccountCreation(
            new UsernameTranscoder().decodeUsername(dto.getUsername()),
            dto.getEmail() == null || dto.getEmail().isBlank() ? null : dto.getEmail(),
            null,
            null,
            dto.getPreferredLanguage() == null ? "de" : dto.getPreferredLanguage().toString(),
            dto.getPassword(),
            false);
    var receipt = coordinator.create(id, scope, account);
    T result =
        coordinator.localSaga(
            receipt,
            attempt -> {
              var projection =
                  commands.ownedRead(
                      id,
                      new OwnedReadRequest(
                          attempt.identity(),
                          attempt.execution(),
                          attempt.accountId(),
                          attempt.creationProof()));
              if (projection == null
                  || !attempt.accountId().equals(projection.id())
                  || !Objects.equals(scope.tenantId(), projection.tenantId())
                  || projection.enabled()
                  || !projection.effectiveRoles().isEmpty())
                throw new IllegalStateException("Foreign pending registration projection");
              return localWrites.apply(new Session(attempt, projection));
            });
    journal.requireCommitted(id); // persisted local success is not reported as remote activation
    return result;
  }

  public JdbcOwnedLifecycleEffects.Scope ownedRooms(Session session) {
    return effects.capture(session.attempt());
  }

  public String createMatrixUser(Session session, String plainUsername) {
    String localpart =
        plainUsername == null ? null : plainUsername.toLowerCase(java.util.Locale.ROOT);
    if (localpart == null || localpart.isBlank())
      throw new IllegalArgumentException("Registration chat username is required");
    String expected = "@" + localpart + ":" + matrixConfig.getServerName();
    UUID effect =
        effects.started(session.attempt(), JdbcOwnedLifecycleEffects.Kind.MATRIX_USER, expected);
    // Existing reserved users are not adopted or reactivated. A timeout remains STARTED/uncertain.
    String actual;
    try {
      actual =
          matrix.createUserIdWithoutReactivation(
              localpart, UUID.randomUUID() + "-" + UUID.randomUUID(), plainUsername);
    } catch (MatrixUserInUseException rejected) {
      effects.rejectedWithoutEffect(effect);
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE, HttpStatus.CONFLICT);
    } catch (de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException failure) {
      throw new IllegalStateException("Created chat identity outcome is unconfirmed");
    }
    if (!expected.equals(actual)) throw new IllegalStateException("Foreign created chat identity");
    effects.acknowledged(effect, actual);
    return actual;
  }
}
