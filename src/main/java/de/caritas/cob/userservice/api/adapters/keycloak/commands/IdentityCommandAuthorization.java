package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** A target-bound origin capability, constructed only after its originating authorization. */
public final class IdentityCommandAuthorization {
  private final String originKind;
  private final String operation;
  private final String target;
  private final String tenantId;
  private final List<String> roles;

  IdentityCommandAuthorization(
      String originKind, String operation, String target, String tenantId, List<String> roles) {
    this.originKind = originKind;
    this.operation = operation;
    this.target = target;
    this.tenantId = tenantId;
    this.roles = List.copyOf(roles);
  }

  public static IdentityCommandAuthorization selfService(
      Jwt verifiedCaller, String target, String operation) {
    if (verifiedCaller == null
        || technicalActor(verifiedCaller)
        || !verifiedCaller.getSubject().equals(target)
        || !Set.of("account.read", "account.profile", "account.password").contains(operation)) {
      throw new AccessDeniedException("Self-service identity operation is not authorized");
    }
    Object tenant = verifiedCaller.getClaims().get("tenantId");
    if (tenant instanceof java.util.Collection<?> values) {
      var mapped =
          values.stream()
              .filter(java.util.Objects::nonNull)
              .map(Object::toString)
              .filter(value -> !value.isBlank())
              .distinct()
              .toList();
      if (mapped.size() > 1) throw new AccessDeniedException("Ambiguous verified tenant claim");
      tenant = mapped.isEmpty() ? null : mapped.getFirst();
    }
    return new IdentityCommandAuthorization(
        "SELF_SERVICE", operation, target, tenant == null ? null : tenant.toString(), List.of());
  }

  public static IdentityCommandAuthorization selfService(
      Jwt caller, de.caritas.cob.userservice.api.model.Admin target, String operation) {
    return persistedSelf(caller, target.getId(), target.getTenantId(), operation);
  }

  public static IdentityCommandAuthorization selfService(
      Jwt caller, de.caritas.cob.userservice.api.model.Consultant target, String operation) {
    return persistedSelf(caller, target.getId(), target.getTenantId(), operation);
  }

  public static IdentityCommandAuthorization selfService(
      Jwt caller, de.caritas.cob.userservice.api.model.User target, String operation) {
    return persistedSelf(caller, target.getUserId(), target.getTenantId(), operation);
  }

  private static IdentityCommandAuthorization persistedSelf(
      Jwt caller, String id, Long tenant, String operation) {
    var origin = selfService(caller, id, operation);
    if (!java.util.Objects.equals(origin.tenantId(), tenant == null ? null : tenant.toString()))
      throw new AccessDeniedException(
          "Human session tenant differs from the actual persisted account");
    return origin;
  }

  /** Local metadata may be displayed under the original administrator listing permissions. */
  public static void verifiedAdministratorMetadata(
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller) {
    human(
        caller,
        "account.read",
        List.of(),
        Set.of("AUTHORIZATION_USER_ADMIN", "AUTHORIZATION_TENANT_ADMIN"));
  }

  public static void verifiedHumanCreation(
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller,
      IdentityCreationOrigin.Kind kind) {
    var permission =
        switch (kind) {
          case CONSULTANT, CONSULTANT_AGENCY_ADMIN ->
              Set.of("AUTHORIZATION_CONSULTANT_CREATE", "AUTHORIZATION_USER_ADMIN");
          case AGENCY_ADMIN -> Set.of("AUTHORIZATION_USER_ADMIN");
          case TENANT_ADMIN -> Set.of("AUTHORIZATION_TENANT_ADMIN");
          default -> Set.<String>of();
        };
    human(caller, "account.read", List.of(), permission);
  }

  public static IdentityCommandAuthorization registrationAvailability(String exactValue) {
    return registrationAvailability(exactValue, null);
  }

  public static IdentityCommandAuthorization registrationAvailability(
      String exactValue, Long verifiedTenant) {
    if (exactValue == null || exactValue.isBlank() || exactValue.length() > 255)
      throw new AccessDeniedException(
          "Only a bounded exact registration availability lookup is authorized");
    return new IdentityCommandAuthorization(
        "REGISTRATION",
        "account.search",
        exactValue,
        verifiedTenant == null ? null : verifiedTenant.toString(),
        List.of());
  }

  public static IdentityCommandAuthorization claimedPasswordReset(
      de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore.TokenClaim claim,
      de.caritas.cob.userservice.api.model.User target) {
    return reset(
        claim,
        target == null ? null : target.getUserId(),
        target == null ? null : target.getTenantId());
  }

  public static IdentityCommandAuthorization claimedPasswordReset(
      de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore.TokenClaim claim,
      de.caritas.cob.userservice.api.model.Consultant target) {
    return reset(
        claim,
        target == null ? null : target.getId(),
        target == null ? null : target.getTenantId());
  }

  public static IdentityCommandAuthorization claimedPasswordReset(
      de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore.TokenClaim claim,
      de.caritas.cob.userservice.api.model.Admin target) {
    return reset(
        claim,
        target == null ? null : target.getId(),
        target == null ? null : target.getTenantId());
  }

  private static IdentityCommandAuthorization reset(
      de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore.TokenClaim claim,
      String target,
      Long tenant) {
    if (claim == null
        || target == null
        || target.isBlank()
        || !target.equals(claim.subjectId())
        || claim.expiresAt() == null
        || !claim.expiresAt().isAfter(java.time.Instant.now()))
      throw new AccessDeniedException(
          "Password reset requires its live consumed claim and actual persisted owner");
    return new IdentityCommandAuthorization(
        "PASSWORD_RESET",
        "account.password",
        target,
        tenant == null ? null : tenant.toString(),
        List.of());
  }

  /** A setup-link read is issued only from the row whose atomic claim succeeded. */
  public static IdentityCommandAuthorization claimedSetupRead(
      de.caritas.cob.userservice.api.model.AccountInvite invite) {
    requireHeldSetup(invite);
    return onboarding("account.read", invite.getProvisionedUserId(), invite.getTenantId());
  }

  public static IdentityCommandAuthorization checkedSetupPassword(
      de.caritas.cob.userservice.api.model.AccountInvite invite,
      String chosenPassword,
      de.caritas.cob.userservice.api.service.accountinvite.InitialPasswordVerifier verifier) {
    requireHeldSetup(invite);
    if (chosenPassword == null
        || chosenPassword.isBlank()
        || verifier == null
        || verifier.matches(chosenPassword, invite.getInitialPasswordVerifier()))
      throw new AccessDeniedException("Setup must replace the verified initial password");
    return onboarding("account.password", invite.getProvisionedUserId(), invite.getTenantId());
  }

  private static void requireHeldSetup(de.caritas.cob.userservice.api.model.AccountInvite invite) {
    if (invite == null
        || invite.getId() == null
        || invite.getPurpose()
            != de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose
                .EXISTING_ACCOUNT_SETUP
        || invite.getStatus()
            != de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus.EMAIL_SENT
        || invite.getProvisioningStatus()
            != de.caritas.cob.userservice.api.service.accountinvite.AccountInviteProvisioningStatus
                .IN_PROGRESS
        || invite.getExpiresAt() == null
        || !invite.getExpiresAt().isAfter(java.time.LocalDateTime.now())
        || invite.getInitialPasswordVerifier() == null
        || invite.getInitialPasswordVerifier().isBlank())
      throw new AccessDeniedException("Existing-account setup requires its live held claim");
  }

  /** An accepted invitation remains usable only for the unfinished two-factor gate. */
  public static IdentityCommandAuthorization acceptedInviteRead(
      de.caritas.cob.userservice.api.model.AccountInvite invite) {
    if (invite == null
        || invite.getId() == null
        || invite.getPurpose()
            != de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose.INVITE
        || invite.getStatus()
            != de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus.ACCEPTED
        || de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService
            .isTwoFactorGateSatisfied(invite.getTwoFactorStatus()))
      throw new AccessDeniedException(
          "Onboarding read requires an accepted invitation with an unfinished gate");
    return onboarding("account.read", invite.getAcceptedByUserId(), invite.getTenantId());
  }

  private static IdentityCommandAuthorization onboarding(
      String operation, String target, Long tenant) {
    if (target == null || target.isBlank())
      throw new AccessDeniedException("Missing persisted onboarding target");
    return new IdentityCommandAuthorization(
        "ONBOARDING", operation, target, tenant == null ? null : tenant.toString(), List.of());
  }

  public static IdentityCommandAuthorization checkedHuman(
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller,
      de.caritas.cob.userservice.api.admin.service.admin.AdminScope scope,
      de.caritas.cob.userservice.api.model.Admin target,
      String operation,
      java.util.Collection<String> roles) {
    if (target.getType() == de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT
        && java.util.Objects.equals(target.getTenantId(), 0L))
      throw new AccessDeniedException(
          "Protected platform account requires its own bounded self-service or recovery authority");
    human(
        caller,
        operation,
        roles,
        target.getType() == de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT
            ? ("account.read".equals(operation)
                ? Set.of("AUTHORIZATION_TENANT_ADMIN", "AUTHORIZATION_USER_ADMIN")
                : Set.of("AUTHORIZATION_TENANT_ADMIN"))
            : Set.of("AUTHORIZATION_USER_ADMIN"));
    scope.assertMay(
        de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target.admin(target.getId()));
    return boundedHuman(operation, target.getId(), target.getTenantId(), roles);
  }

  public static IdentityCommandAuthorization checkedHuman(
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller,
      de.caritas.cob.userservice.api.admin.service.admin.AdminScope scope,
      de.caritas.cob.userservice.api.model.Consultant target,
      String operation,
      java.util.Collection<String> roles) {
    var allowed =
        operation.equals("account.profile") || operation.equals("account.roles")
            ? Set.of("AUTHORIZATION_USER_ADMIN", "AUTHORIZATION_CONSULTANT_UPDATE")
            : Set.of(
                "AUTHORIZATION_USER_ADMIN",
                "AUTHORIZATION_RESTRICTED_AGENCY_ADMIN",
                "AUTHORIZATION_TENANT_ADMIN");
    human(caller, operation, roles, allowed);
    scope.assertMay(
        de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target.counsellor(
            target.getId()));
    return boundedHuman(operation, target.getId(), target.getTenantId(), roles);
  }

  public static IdentityCommandAuthorization checkedHuman(
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller,
      de.caritas.cob.userservice.api.admin.service.admin.AdminScope scope,
      de.caritas.cob.userservice.api.model.User target,
      String operation,
      java.util.Collection<String> roles) {
    human(caller, operation, roles, Set.of("AUTHORIZATION_USER_ADMIN"));
    scope.assertMay(
        de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target.adviceSeeker(
            target.getUserId()));
    return boundedHuman(operation, target.getUserId(), target.getTenantId(), roles);
  }

  private static void human(
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller,
      String operation,
      java.util.Collection<String> roles,
      Set<String> permissions) {
    if (caller == null
        || !caller.isAuthenticated()
        || !Set.of(
                "account.read",
                "account.profile",
                "account.password",
                "account.roles",
                "account.deactivate",
                "account.delete")
            .contains(operation)
        || caller.getAuthorities().stream().map(Object::toString).noneMatch(permissions::contains))
      throw new AccessDeniedException("Human account operation lacks its original permission");
    if (technicalActor(caller.getToken()))
      throw new AccessDeniedException("A technical actor cannot claim human maintenance authority");
    if (!Set.of(
            "user",
            "consultant",
            "group-chat-consultant",
            "restricted-agency-admin",
            "user-admin",
            "agency-admin",
            "tenant-admin",
            "topic-admin")
        .containsAll(roles))
      throw new AccessDeniedException(
          "Account role command exceeds the bounded human role allowlist");
  }

  private static boolean technicalActor(Jwt token) {
    var tokenRoles = token.getClaimAsMap("realm_access");
    return tokenRoles != null
        && tokenRoles.get("roles") instanceof java.util.Collection<?> values
        && values.stream()
            .map(Object::toString)
            .anyMatch(
                v ->
                    v.equals("technical")
                        || v.equals("consultant-import")
                        || v.equals("smtp-sync")
                        || java.util.Arrays.stream(
                                de.caritas.cob.userservice.api.config.auth.TaskIdentity.values())
                            .flatMap(task -> task.roles().stream())
                            .anyMatch(v::equals));
  }

  private static IdentityCommandAuthorization boundedHuman(
      String operation, String target, Long tenant, java.util.Collection<String> roles) {
    if (target == null || target.isBlank())
      throw new AccessDeniedException("Missing persisted account target");
    return new IdentityCommandAuthorization(
        "HUMAN_ADMIN",
        operation,
        target,
        tenant == null ? null : tenant.toString(),
        roles.stream().sorted().distinct().toList());
  }

  public static IdentityCommandAuthorization persistedDeletion(
      de.caritas.cob.userservice.api.model.User target, String operation) {
    if (target == null || target.getDeleteDate() == null)
      throw new AccessDeniedException("User is not marked for lifecycle deletion");
    return lifecycle(operation, target.getUserId(), target.getTenantId());
  }

  public static IdentityCommandAuthorization persistedDeletion(
      de.caritas.cob.userservice.api.model.Consultant target, String operation) {
    if (target == null || target.getDeleteDate() == null)
      throw new AccessDeniedException("Consultant is not marked for lifecycle deletion");
    return lifecycle(operation, target.getId(), target.getTenantId());
  }

  private static IdentityCommandAuthorization lifecycle(
      String operation, String target, Long tenant) {
    if (target == null
        || target.isBlank()
        || !Set.of("account.read", "account.profile", "account.deactivate", "account.delete")
            .contains(operation))
      throw new AccessDeniedException("Invalid bounded account lifecycle action");
    return new IdentityCommandAuthorization(
        "LIFECYCLE", operation, target, tenant == null ? null : tenant.toString(), List.of());
  }

  /** Scheduled deactivation has authority only over a stale, persisted anonymous session. */
  public static IdentityCommandAuthorization staleAnonymousSession(
      de.caritas.cob.userservice.api.model.Session session, java.time.LocalDateTime cutoff) {
    if (session == null
        || session.getId() == null
        || session.getUser() == null
        || cutoff == null
        || session.getRegistrationType()
            != de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS
        || !Set.of(
                de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW,
                de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS)
            .contains(session.getStatus())
        || session.getUpdateDate() == null
        || !session.getUpdateDate().isBefore(cutoff))
      throw new AccessDeniedException(
          "Anonymous account is not eligible for scheduled deactivation");
    return lifecycle(
        "account.deactivate", session.getUser().getUserId(), session.getUser().getTenantId());
  }

  public static IdentityCommandAuthorization finishedAnonymousSession(
      de.caritas.cob.userservice.api.model.Session session,
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller) {
    if (session == null
        || session.getId() == null
        || session.getUser() == null
        || !de.caritas.cob.userservice.api.service.session.AnonymousSessionRegistration.matches(
            session))
      throw new AccessDeniedException("Only the actual anonymous conversation can be finished");
    human(
        caller,
        "account.deactivate",
        List.of(),
        Set.of(
            "AUTHORIZATION_CONSULTANT_DEFAULT",
            "AUTHORIZATION_ANONYMOUS_DEFAULT",
            "AUTHORIZATION_USER_DEFAULT"));
    boolean assigned =
        session.getConsultant() != null
            && caller.getToken().getSubject().equals(session.getConsultant().getId())
            && caller.getAuthorities().stream()
                .map(Object::toString)
                .anyMatch("AUTHORIZATION_CONSULTANT_DEFAULT"::equals);
    if (!caller.getToken().getSubject().equals(session.getUser().getUserId()) && !assigned)
      throw new AccessDeniedException("Caller does not own or counsel the anonymous conversation");
    return lifecycle(
        "account.deactivate", session.getUser().getUserId(), session.getUser().getTenantId());
  }

  public static IdentityCommandAuthorization lastSessionDeletion(
      de.caritas.cob.userservice.api.model.Session session,
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller) {
    human(caller, "account.deactivate", List.of(), Set.of("AUTHORIZATION_CONSULTANT_DEFAULT"));
    if (session == null
        || session.getId() == null
        || session.getUser() == null
        || session.getUser().getSessions() == null
        || session.getUser().getSessions().size() != 1
        || session.getUser().getSessions().stream()
            .noneMatch(saved -> java.util.Objects.equals(saved.getId(), session.getId())))
      throw new AccessDeniedException(
          "Only the actual last persisted session authorizes deactivation");
    return lifecycle(
        "account.deactivate", session.getUser().getUserId(), session.getUser().getTenantId());
  }

  /** Consumers can verify this sealed capability without constructing or widening it. */
  public void requireLifecycleDeletion(String identityId) {
    if (!"LIFECYCLE".equals(originKind())
        || !"account.delete".equals(operation())
        || !target().equals(identityId))
      throw new AccessDeniedException("Inactivity deletion exceeds persisted target authority");
  }

  /** Package-private: only the DB-backed lifecycle adapter derives these state facts. */
  static IdentityCommandAuthorization inactivityWorkflow(
      String target,
      Long tenant,
      de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
          status,
      String operation,
      Boolean originalEnabled) {
    if (target == null
        || target.isBlank()
        || status == null
        || !(operation.equals("account.lifecycle-status")
            || operation.equals("account.suspend")
                && Set.of(
                        de.caritas.cob.userservice.api.workflow.accountinactivity
                            .AccountInactivityService.Status.SUSPENDING,
                        de.caritas.cob.userservice.api.workflow.accountinactivity
                            .AccountInactivityService.Status.DELETING)
                    .contains(status)
            || operation.equals("account.delete")
                && Boolean.TRUE.equals(originalEnabled)
                && status
                    == de.caritas.cob.userservice.api.workflow.accountinactivity
                        .AccountInactivityService.Status.DELETING
            || operation.equals("account.restore")
                && originalEnabled != null
                && status
                    == de.caritas.cob.userservice.api.workflow.accountinactivity
                        .AccountInactivityService.Status.REACTIVATING))
      throw new AccessDeniedException(
          "Identity lifecycle effect exceeds its persisted workflow authority");
    return new IdentityCommandAuthorization(
        "LIFECYCLE", operation, target, tenant == null ? null : tenant.toString(), List.of());
  }

  /** Package-private: the DB-backed inventory adapter is the only originating boundary. */
  static IdentityCommandAuthorization inactivityInventory(
      java.time.Instant cutoff, int first, int max) {
    if (cutoff == null || first < 0 || max < 1 || max > 1000)
      throw new AccessDeniedException(
          "Inactivity inventory range is outside its persisted authority");
    return new IdentityCommandAuthorization(
        "LIFECYCLE",
        "account.inventory",
        "cutoff:" + cutoff + "/first:" + first + "/max:" + max,
        null,
        List.of());
  }

  /** Only ConfiguredConsultantImport's verified captured-row boundary may call this factory. */
  static IdentityCommandAuthorization importedExisting(
      de.caritas.cob.userservice.api.model.Consultant target,
      String operation,
      java.util.Collection<String> requestedAdditions) {
    if (target == null
        || target.getId() == null
        || target.getId().isBlank()
        || target.getDeleteDate() != null
        || java.util.Objects.equals(target.getTenantId(), 0L)
        || !Set.of("account.read", "account.roles").contains(operation)
        || requestedAdditions == null
        || requestedAdditions.isEmpty()
        || !Set.of("consultant", "group-chat-consultant").containsAll(requestedAdditions))
      throw new AccessDeniedException(
          "Existing-account import exceeds its verified consultant role additions");
    try {
      java.util.UUID.fromString(target.getId());
    } catch (IllegalArgumentException invalid) {
      throw new AccessDeniedException("Import requires the actual persisted consultant UUID");
    }
    return new IdentityCommandAuthorization(
        "IMPORT",
        operation,
        target.getId(),
        target.getTenantId() == null ? null : target.getTenantId().toString(),
        operation.equals("account.roles")
            ? requestedAdditions.stream().sorted().distinct().toList()
            : List.of());
  }

  public static IdentityCommandAuthorization persistedRecipient(
      de.caritas.cob.userservice.api.model.User target) {
    if (target == null) throw new AccessDeniedException("Missing persisted mail recipient");
    return lifecycle("account.read", target.getUserId(), target.getTenantId());
  }

  public static IdentityCommandAuthorization persistedRecipient(
      de.caritas.cob.userservice.api.model.Consultant target) {
    if (target == null) throw new AccessDeniedException("Missing persisted mail recipient");
    return lifecycle("account.read", target.getId(), target.getTenantId());
  }

  public static IdentityCommandAuthorization persistedRecipient(
      de.caritas.cob.userservice.api.model.Admin target) {
    if (target == null) throw new AccessDeniedException("Missing persisted mail recipient");
    return lifecycle("account.read", target.getId(), target.getTenantId());
  }

  String originKind() {
    return originKind;
  }

  String operation() {
    return operation;
  }

  String target() {
    return target;
  }

  String tenantId() {
    return tenantId;
  }

  List<String> roles() {
    return roles;
  }
}
