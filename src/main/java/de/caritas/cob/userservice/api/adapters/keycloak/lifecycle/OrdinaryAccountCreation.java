package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.exception.matrix.MatrixUserInUseException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ordinary account workflows. Protected platform/Träger creation uses a separate receiver surface.
 */
public class OrdinaryAccountCreation {
  private final AccountLifecycleCoordinator coordinator;
  private final AccountLifecycleJournal journal;
  private final IdentityProvisioningCommands commands;
  private final JdbcOwnedLifecycleEffects effects;
  private final AccountLifecycleCodec codec;
  private final MatrixSynapseService matrix;
  private final MatrixConfig matrixConfig;
  private final AppointmentService appointments;
  private final AuthenticatedUser authenticatedUser;
  private final String issuer;
  private final Set<String> humanClients;
  private final Map<String, Set<String>> groups;
  private final OrdinaryCreationTenantResolver tenantResolver;

  public OrdinaryAccountCreation(
      AccountLifecycleCoordinator coordinator,
      AccountLifecycleJournal journal,
      IdentityProvisioningCommands commands,
      JdbcOwnedLifecycleEffects effects,
      AccountLifecycleCodec codec,
      MatrixSynapseService matrix,
      MatrixConfig matrixConfig,
      AppointmentService appointments,
      AuthenticatedUser authenticatedUser,
      String issuer,
      Set<String> humanClients,
      Map<String, Set<String>> groups,
      OrdinaryCreationTenantResolver tenantResolver) {
    this.coordinator = Objects.requireNonNull(coordinator);
    this.journal = Objects.requireNonNull(journal);
    this.commands = Objects.requireNonNull(commands);
    this.effects = Objects.requireNonNull(effects);
    this.codec = Objects.requireNonNull(codec);
    this.matrix = Objects.requireNonNull(matrix);
    this.matrixConfig = Objects.requireNonNull(matrixConfig);
    this.appointments = Objects.requireNonNull(appointments);
    this.authenticatedUser = Objects.requireNonNull(authenticatedUser);
    this.tenantResolver = Objects.requireNonNull(tenantResolver);
    if (issuer == null || issuer.isBlank() || humanClients == null || humanClients.isEmpty())
      throw denied();
    this.issuer = issuer;
    this.humanClients = Set.copyOf(humanClients);
    var copy = new TreeMap<String, Set<String>>();
    groups.forEach(
        (path, roles) -> {
          if (path == null
              || !path.startsWith("/human-roles/")
              || roles == null
              || kind(roles) == null) throw denied();
          copy.put(path, Set.copyOf(roles));
        });
    if (copy.isEmpty() || new HashSet<>(copy.values()).size() != copy.size()) throw denied();
    this.groups = Map.copyOf(copy);
  }

  public CreationScope humanScope(
      AdminScope scope,
      Long tenantId,
      Collection<Long> agencies,
      String registrationKind,
      Set<String> roles) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken caller)
        || !caller.isAuthenticated()
        || caller.getToken().getIssuer() == null
        || !issuer.equals(caller.getToken().getIssuer().toString())
        || !Objects.equals(authenticatedUser.getUserId(), caller.getToken().getSubject())
        || !humanClients.contains(caller.getToken().getClaimAsString("azp"))
        || !(caller.getToken().getClaim("realm_access") instanceof Map<?, ?> realm)
        || !(realm.get("roles") instanceof Collection<?> realmRoles)
        || !realmRoles.contains("user-admin")) throw denied();
    var ids =
        agencies == null
            ? List.<Long>of()
            : agencies.stream().filter(Objects::nonNull).distinct().sorted().toList();
    var reach = scope.current();
    if (!(reach instanceof AdminScope.Platform)
        && reach.tenantId() != null
        && !Objects.equals(reach.tenantId(), tenantId)) throw denied();
    if ("AGENCY_ADMIN".equals(registrationKind))
      scope.assertMay(AdminScope.Target.tenant(tenantId));
    else scope.assertMay(AdminScope.Target.agencies(ids));
    return scope(
        tenantId,
        registrationKind,
        roles,
        new Origin(
            "HUMAN_ADMIN",
            "human:" + UUID.randomUUID(),
            "human:" + caller.getToken().getSubject(),
            issuer,
            caller.getToken().getSubject(),
            "user-admin",
            ids.stream().map(Object::toString).sorted().toList()));
  }

  /** Called only by the supported multitenancy=false public creator paths. */
  public Long resolveSingleTenant(Long requestedTenant, Collection<Long> agencyIds) {
    return tenantResolver.resolve(requestedTenant, agencyIds);
  }

  /** Caller has validated the raw link and holds the actual existing invitation row. */
  public CreationScope heldInvitationScope(
      AccountInvite invite, String registrationKind, Set<String> roles) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || invite == null
        || invite.getId() == null
        || invite.getTenantId() == null
        || invite.getPurpose() != AccountInvitePurpose.INVITE
        || invite.getStatus() != AccountInviteStatus.EMAIL_SENT
        || invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(LocalDateTime.now()))
      throw denied();
    boolean compound = "CONSULTANT_AGENCY_ADMIN".equals(registrationKind);
    boolean allowed =
        switch (invite.getTargetRole()) {
          case COUNSELLOR ->
              "CONSULTANT".equals(registrationKind)
                  || (compound
                      && IdAllocationMode.reservesAnId(invite.getAgencyIdAllocationMode()));
          case AGENCY_ADMIN -> "AGENCY_ADMIN".equals(registrationKind) || compound;
          default -> false;
        };
    if (!allowed || invite.getAgencyId() == null) throw denied();
    return scope(
        invite.getTenantId(),
        registrationKind,
        roles,
        new Origin(
            "INVITATION",
            "invite:" + invite.getId(),
            "invite:" + invite.getId(),
            null,
            null,
            null,
            List.of(invite.getAgencyId().toString())));
  }

  /**
   * Only the configured import owner calls this after resolving and validating the exact CSV row.
   */
  public CreationScope configuredImportScope(
      Long tenantId,
      Set<String> roles,
      Collection<Long> agencies,
      String sourceDigest,
      long rowNumber) {
    if (sourceDigest == null
        || !sourceDigest.matches("[0-9a-f]{64}")
        || rowNumber < 1
        || agencies == null
        || agencies.isEmpty()) throw denied();
    return scope(
        tenantId,
        "CONSULTANT",
        roles,
        new Origin(
            "IMPORT",
            "import:" + sourceDigest + ":" + rowNumber,
            "configured-csv:" + sourceDigest + ":" + rowNumber,
            null,
            null,
            null,
            agencies.stream().map(Object::toString).distinct().sorted().toList()));
  }

  /** Actual anonymous facade calls after tenant, consulting-type/invite and DPA checks. */
  public CreationScope anonymousScope(
      de.caritas.cob.userservice.api.adapters.web.dto.UserDTO request, Long tenant) {
    if (request == null
        || !"true".equalsIgnoreCase(request.getTermsAccepted())
        || !"00000".equals(request.getPostcode())
        || request.getUsername() == null
        || request.getPassword() == null
        || request.getPassword().isBlank()
        || request.getTenantId() != null && !Objects.equals(tenant, request.getTenantId()))
      throw denied();
    return scope(
        tenant,
        "ANONYMOUS",
        Set.of("user"),
        new Origin(
            "ANONYMOUS",
            "anonymous:" + UUID.randomUUID(),
            "anonymous:consulting:" + request.getConsultingType(),
            null,
            null,
            null,
            List.of()));
  }

  private CreationScope scope(
      Long tenantId, String registrationKind, Set<String> roles, Origin origin) {
    if (!Objects.equals(
        "ANONYMOUS".equals(registrationKind) ? "ASKER" : registrationKind, kind(roles)))
      throw denied();
    String group =
        groups.entrySet().stream()
            .filter(e -> e.getValue().equals(roles))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(OrdinaryAccountCreation::denied);
    return new CreationScope(
        tenantId == null ? null : tenantId.toString(), registrationKind, group, origin);
  }

  public final class Pending {
    private final AccountLifecycleJournal.Attempt attempt;
    private final AccountProjection projection;

    private Pending(AccountLifecycleJournal.Attempt attempt, AccountProjection projection) {
      this.attempt = attempt;
      this.projection = projection;
    }

    public String accountId() {
      return attempt.accountId();
    }

    public AccountProjection projection() {
      return projection;
    }

    public JdbcOwnedLifecycleEffects.Scope owned() {
      return effects.capture(attempt);
    }
  }

  public <T> T create(
      CreationScope scope, AccountCreation account, Function<Pending, T> localWrites) {
    var intended = groups.get(scope.initialGroup());
    if (intended == null
        || !Objects.equals(
            "ANONYMOUS".equals(scope.registrationKind()) ? "ASKER" : scope.registrationKind(),
            kind(intended))) throw denied();
    boolean outer = TransactionSynchronizationManager.isActualTransactionActive();
    UUID id = UUID.randomUUID();
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
                  || projection.enabled()
                  || !projection.effectiveRoles().isEmpty()
                  || !Objects.equals(scope.tenantId(), projection.tenantId())
                  || !account.username().equalsIgnoreCase(projection.username())
                  || projection.passwordChangeRequired() != account.passwordTemporary())
                throw denied();
              return PendingCreationRoles.within(
                  attempt, intended, () -> localWrites.apply(new Pending(attempt, projection)));
            });
    if (outer)
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              requireCommitted(id);
            }
          });
    else requireCommitted(id);
    return result;
  }

  public String createChatAccount(Pending pending, String plainUsername, String displayName) {
    String localpart = plainUsername.toLowerCase(Locale.ROOT);
    String expected = "@" + localpart + ":" + matrixConfig.getServerName();
    UUID effect =
        effects.started(pending.attempt, JdbcOwnedLifecycleEffects.Kind.MATRIX_USER, expected);
    try {
      String actual =
          matrix.createUserIdWithoutReactivation(
              localpart, UUID.randomUUID() + "-" + UUID.randomUUID(), displayName);
      if (!expected.equals(actual)) throw denied();
      effects.acknowledged(effect, actual);
      return actual;
    } catch (MatrixUserInUseException rejected) {
      effects.rejectedWithoutEffect(effect);
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE, HttpStatus.CONFLICT);
    } catch (de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException failure) {
      throw new IllegalStateException("Owned chat identity creation remains unconfirmed");
    }
  }

  public void createAppointment(Pending pending, ConsultantAdminResponseDTO response) {
    if (response == null
        || response.getEmbedded() == null
        || !pending.accountId().equals(response.getEmbedded().getId())) throw denied();
    UUID effect =
        effects.started(
            pending.attempt,
            JdbcOwnedLifecycleEffects.Kind.APPOINTMENT_CONSULTANT,
            pending.accountId());
    appointments.createOwnedConsultant(
        response, Long.valueOf(pending.attempt.identity().scope().tenantId()));
    effects.acknowledged(effect, pending.accountId());
  }

  /** A committed local invite with an unknown activation reply resumes its same durable intent. */
  public boolean finishInvitationIfPending(AccountInvite invite) {
    if (invite == null || invite.getId() == null || invite.getProvisionedUserId() == null)
      throw denied();
    var saved =
        journal.findWorkflow(
            "INVITATION",
            "invite:" + invite.getId(),
            invite.getTenantId(),
            invite.getProvisionedUserId());
    if (saved.isEmpty())
      return false; // pre-C existing-account invitations retain their old contract
    var attempt = saved.get();
    if ("COMMITTED".equals(attempt.status())) return false;
    if (!"COMMIT_REQUESTED".equals(attempt.status())) throw denied();
    coordinator.retryFinalization(attempt.id());
    requireCommitted(attempt.id());
    return true;
  }

  private void requireCommitted(UUID id) {
    try {
      journal.requireCommitted(id);
    } catch (IllegalStateException pending) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Account finalization is pending; retry this workflow");
    }
  }

  static String kind(Set<String> roles) {
    if (roles.equals(Set.of("user"))) return "ASKER";
    if (roles.equals(Set.of("consultant"))
        || roles.equals(Set.of("consultant", "group-chat-consultant"))) return "CONSULTANT";
    if (roles.equals(Set.of("restricted-agency-admin", "user-admin"))) return "AGENCY_ADMIN";
    if (roles.equals(Set.of("consultant", "restricted-agency-admin", "user-admin"))
        || roles.equals(
            Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")))
      return "CONSULTANT_AGENCY_ADMIN";
    return null;
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Account creation exceeds checked ordinary workflow");
  }
}
