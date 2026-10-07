package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;

/** Immutable provenance issued at the domain authorization point; it carries no caller secret. */
public final class IdentityCreationOrigin {
  public enum Kind {
    ASKER,
    ANONYMOUS,
    CONSULTANT,
    CONSULTANT_AGENCY_ADMIN,
    AGENCY_ADMIN,
    TENANT_ADMIN
  }

  private final String originKind;
  private final Kind registrationKind;
  private final Long tenantId;
  private final List<String> roles;
  private final String provenance;
  private final Map<String, String> importTarget;
  private final List<Long> agencyIds;

  private IdentityCreationOrigin(
      String originKind, Kind kind, Long tenantId, Collection<String> roles, String provenance) {
    this(originKind, kind, tenantId, roles, provenance, Map.of(), List.of());
  }

  private IdentityCreationOrigin(
      String originKind,
      Kind kind,
      Long tenantId,
      Collection<String> roles,
      String provenance,
      Map<String, String> importTarget,
      Collection<Long> agencyIds) {
    this.importTarget = Map.copyOf(importTarget);
    this.agencyIds = agencyIds.stream().sorted().distinct().toList();
    this.originKind = originKind;
    this.registrationKind = kind;
    this.tenantId = tenantId;
    this.roles = roles.stream().sorted().distinct().toList();
    this.provenance = provenance;
    var allowed =
        switch (kind) {
          case ASKER -> Set.of("user");
          case ANONYMOUS -> Set.of("user");
          case CONSULTANT -> Set.of("consultant", "group-chat-consultant");
          case CONSULTANT_AGENCY_ADMIN ->
              Set.of(
                  "consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin");
          case AGENCY_ADMIN -> Set.of("restricted-agency-admin", "user-admin");
          case TENANT_ADMIN -> Set.of("user-admin", "agency-admin", "tenant-admin", "topic-admin");
        };
    if (roles.isEmpty() || !allowed.containsAll(roles))
      throw new AccessDeniedException(
          "Initial human roles are outside the authorized creation kind");
  }

  public static IdentityCreationOrigin checkedHuman(
      AdminScope scope,
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller,
      Long tenantId,
      Kind kind,
      Collection<String> roles) {
    IdentityCommandAuthorization.verifiedHumanCreation(caller, kind);
    scope.assertMay(AdminScope.Target.tenant(tenantId));
    if (kind == Kind.ASKER || kind == Kind.ANONYMOUS)
      throw new AccessDeniedException("Administrator account creation kind is invalid");
    return new IdentityCreationOrigin(
        "HUMAN_ADMIN", kind, tenantId, roles, "human-admin:" + caller.getToken().getSubject());
  }

  public static IdentityCreationOrigin checkedHumanForAgencies(
      AdminScope scope,
      org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
          caller,
      Long tenantId,
      Collection<Long> agencyIds,
      Kind kind,
      Collection<String> roles) {
    IdentityCommandAuthorization.verifiedHumanCreation(caller, kind);
    var reach = scope.current();
    if (!(reach instanceof AdminScope.Platform) && !Objects.equals(reach.tenantId(), tenantId))
      throw new AccessDeniedException("Human account creation exceeds the caller's tenant");
    if (kind != Kind.CONSULTANT && kind != Kind.CONSULTANT_AGENCY_ADMIN)
      throw new AccessDeniedException("Agency scope cannot authorize this account kind");
    scope.assertMay(AdminScope.Target.agencies(agencyIds));
    return new IdentityCreationOrigin(
        "HUMAN_ADMIN",
        kind,
        tenantId,
        roles,
        "human-admin:agencies:" + caller.getToken().getSubject());
  }

  /** Called only with the persisted invite held by the existing verified-token transaction. */
  public static IdentityCreationOrigin heldInvitation(
      AccountInvite invite, Kind kind, Collection<String> roles) {
    boolean matches =
        switch (invite.getTargetRole()) {
          case COUNSELLOR ->
              kind == Kind.CONSULTANT
                  || (kind == Kind.CONSULTANT_AGENCY_ADMIN
                      && invite.getAgencyIdAllocationMode() != null
                      && invite.getAgencyIdAllocationMode() != IdAllocationMode.EXISTING
                      && invite.getAgencyReservationToken() != null
                      && !invite.getAgencyReservationToken().isBlank());
          case AGENCY_ADMIN -> kind == Kind.AGENCY_ADMIN || kind == Kind.CONSULTANT_AGENCY_ADMIN;
          case TENANT_ADMIN -> kind == Kind.TENANT_ADMIN;
          case PLATFORM_ADMIN, ADVICE_SEEKER -> false;
        };
    if (invite.getId() == null
        || invite.getTenantId() == null
        || !matches
        || invite.getPurpose() != AccountInvitePurpose.INVITE
        || invite.getStatus() != AccountInviteStatus.EMAIL_SENT
        || (invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(LocalDateTime.now()))) {
      throw new AccessDeniedException("Invitation does not authorize this account creation");
    }
    return new IdentityCreationOrigin(
        "INVITATION",
        kind,
        invite.getTenantId(),
        roles,
        "invite:" + invite.getId(),
        Map.of(),
        invite.getAgencyId() == null ? List.of() : List.of(invite.getAgencyId()));
  }

  /** Recovery accepts only a durable previously authorized finalization intent and receipt. */
  static IdentityCreationOrigin pendingRecovery(
      de.caritas.cob.userservice.api.model.IdentityCreationAttempt row) {
    if (!Set.of("RECOVERY_REQUESTED", "LOCAL_CLEANUP_REQUESTED").contains(row.getStatus())
        || row.getExecutionClaim() == null
        || row.getProvenance() == null
        || row.getProvenance().isBlank()
        || !Set.of("INVITATION", "REGISTRATION", "ANONYMOUS", "HUMAN_ADMIN", "IMPORT")
            .contains(row.getOriginKind()))
      throw new AccessDeniedException("Recovery requires a claimed durable creation attempt");
    return new IdentityCreationOrigin(
        row.getOriginKind(),
        Kind.valueOf(row.getRegistrationKind()),
        row.getTenantId(),
        List.of(row.getInitialRoles().split(",")),
        row.getProvenance());
  }

  static IdentityCreationOrigin pendingFinalization(
      de.caritas.cob.userservice.api.model.IdentityCreationAttempt row) {
    if (!Set.of(
                "OPEN",
                "LOCAL_RECONCILIATION_REQUIRED",
                "RECOVERY_REQUESTED",
                "LOCAL_CLEANUP_REQUESTED",
                "COMMITTED",
                "COMMIT_REQUESTED",
                "COMPENSATION_REQUESTED",
                "COMPENSATED")
            .contains(row.getStatus())
        || row.getAccountId() == null
        || row.getCreationProof() == null
        || row.getCreationProof().isBlank()
        || row.getProvenance() == null
        || row.getProvenance().isBlank()
        || !Set.of("INVITATION", "REGISTRATION", "ANONYMOUS", "HUMAN_ADMIN", "IMPORT")
            .contains(row.getOriginKind()))
      throw new AccessDeniedException("Attempt has no recoverable authorized finalization intent");
    return new IdentityCreationOrigin(
        row.getOriginKind(),
        Kind.valueOf(row.getRegistrationKind()),
        row.getTenantId(),
        List.of(row.getInitialRoles().split(",")),
        row.getProvenance());
  }

  public static org.springframework.security.oauth2.server.resource.authentication
          .JwtAuthenticationToken
      verifiedCaller(org.springframework.security.core.Authentication authentication) {
    if (!(authentication
            instanceof
            org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken
                    caller)
        || !caller.isAuthenticated())
      throw new AccessDeniedException("Human account creation requires a verified caller");
    return caller;
  }

  public String originKindForPolicy() {
    return originKind;
  }

  public String registrationKind() {
    return registrationKind.name();
  }

  public Long tenantId() {
    return tenantId;
  }

  public List<String> roles() {
    return roles;
  }

  public List<Long> agencyIds() {
    return agencyIds;
  }

  public String provenance() {
    return provenance;
  }

  IdentityCommandAuthorization command(String operation, UUID attemptId) {
    if (!Set.of(
            "account.create", "account.commit", "account.compensate", "account.creation-recover")
        .contains(operation))
      throw new AccessDeniedException("Creation origin cannot authorize maintenance");
    return new IdentityCommandAuthorization(
        originKind,
        operation,
        attemptId.toString(),
        tenantId == null ? null : tenantId.toString(),
        roles);
  }

  /** Called at the registration owner after its input, agency, DPA and group-invite gates. */
  public static IdentityCreationOrigin checkedRegistration(
      de.caritas.cob.userservice.api.adapters.web.dto.UserDTO request,
      de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO verifiedAgency,
      Long resolvedTenant) {
    if (request == null
        || request.getUsername() == null
        || request.getUsername().isBlank()
        || request.getPassword() == null
        || request.getPassword().isBlank()
        || !"true".equalsIgnoreCase(request.getTermsAccepted())
        || verifiedAgency == null
        || !Objects.equals(request.getAgencyId(), verifiedAgency.getId())
        || !Objects.equals(
            Integer.valueOf(request.getConsultingType()), verifiedAgency.getConsultingType())
        || (verifiedAgency.getTenantId() != null
            && !Objects.equals(verifiedAgency.getTenantId(), resolvedTenant))
        || (request.getTenantId() != null
            && !Objects.equals(request.getTenantId(), resolvedTenant)))
      throw new AccessDeniedException("Registration is outside the verified agency and tenant");
    return new IdentityCreationOrigin(
        "REGISTRATION",
        Kind.ASKER,
        resolvedTenant,
        List.of("user"),
        "registration:agency:" + verifiedAgency.getId());
  }

  /** Anonymous owner calls only after the consulting-type/invite and tenant DPA gates. */
  public static IdentityCreationOrigin checkedAnonymous(
      de.caritas.cob.userservice.api.adapters.web.dto.UserDTO request, Long resolvedTenant) {
    if (request == null
        || request.getUsername() == null
        || request.getUsername().isBlank()
        || request.getPassword() == null
        || request.getPassword().isBlank()
        || !"true".equalsIgnoreCase(request.getTermsAccepted())
        || !"00000".equals(request.getPostcode())
        || (request.getTenantId() != null
            && !Objects.equals(request.getTenantId(), resolvedTenant)))
      throw new AccessDeniedException(
          "Anonymous account request exceeds its verified registration");
    return new IdentityCreationOrigin(
        "ANONYMOUS",
        Kind.ANONYMOUS,
        resolvedTenant,
        List.of("user"),
        "anonymous:consulting:" + request.getConsultingType());
  }

  static IdentityCreationOrigin configuredImport(
      de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord row,
      Collection<String> roles,
      String provenance) {
    if (!roles.contains("consultant"))
      throw new AccessDeniedException("Consultant import requires consultant role");
    return new IdentityCreationOrigin(
        "IMPORT",
        Kind.CONSULTANT,
        row.getTenantId(),
        roles,
        provenance,
        Map.of(
            "username",
            row.getUsername(),
            "email",
            row.getEmail(),
            "firstName",
            row.getFirstName(),
            "lastName",
            row.getLastName()),
        Arrays.stream(row.getAgenciesAndRoleSets().split(","))
            .map(value -> Long.valueOf(value.split(";", 2)[0]))
            .toList());
  }

  void assertCreationTarget(KeycloakTaskCommands.AccountCreation command) {
    if ("IMPORT".equals(originKind)
        && (importTarget.isEmpty()
            || !Objects.equals(importTarget.get("username"), command.username())
            || !Objects.equals(importTarget.get("email"), command.email())
            || !Objects.equals(importTarget.get("firstName"), command.firstName())
            || !Objects.equals(importTarget.get("lastName"), command.lastName())))
      throw new AccessDeniedException("Import creation exceeds its authorized account row");
  }

  String originKind() {
    return originKind;
  }

  @Override
  public String toString() {
    return "IdentityCreationOrigin[kind=" + registrationKind + "]";
  }
}
