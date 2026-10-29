package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static com.google.common.collect.Lists.newArrayList;
import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.helper.json.JsonSerializationUtils.serializeToJsonString;
import static de.caritas.cob.userservice.api.service.creationoutcome.TrackedConsultantCreation.transition;
import static java.util.Objects.*;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.hibernate.validator.internal.util.CollectionHelper.asSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantResponseDTOBuilder;
import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.CreateConsultantDTOAbsenceInputAdapter;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionInfo;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
import de.caritas.cob.userservice.api.model.ConsultantAvatars;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.creationoutcome.*;
import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;

/** Creates consultants in the identity provider, Matrix and the application database. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateConsultantSaga {
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private de.caritas.cob.userservice.api.service.accountpolicy.CheckedAccountPolicyReads
      checkedPolicyReads;

  private final ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;
  private final de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      inactivityEnrollment;
  private static final String CREATE_CONSULTANT = "createConsultant";
  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityPasswordUpdater identityPasswordUpdater;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultantPublicSlugService consultantPublicSlugService;
  private final @NonNull UserHelper userHelper;
  private final @NonNull UserAccountInputValidator userAccountInputValidator;
  private final @NonNull TenantAdminService tenantAdminService;
  private final @NonNull MatrixUserClient matrixUserClient;
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;
  private final @NonNull ConsultantTopicAgencyCompatibilityValidator
      consultantTopicAgencyCompatibilityValidator;

  private final @NonNull RollbackFacade rollbackFacade;

  @Value("${feature.appointment.enabled}")
  private boolean appointmentFeatureEnabled;

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  private final @NonNull AuthenticatedUser authenticatedUser;

  private final @NonNull AppointmentService appointmentService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @org.springframework.beans.factory.annotation.Autowired
  private AccountCreationAttemptJournal creationJournal;

  @org.springframework.beans.factory.annotation.Autowired
  private CreationOperationWriter creationWriter;

  @org.springframework.beans.factory.annotation.Autowired
  private CreationOperationAccess creationAccess;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.core.env.Environment lifecycleEnvironment;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<
          de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation>
      ordinaryAccountCreation;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.admin.service.admin.AdminScope lifecycleAdminScope;

  private boolean fiveActors() {
    return lifecycleEnvironment != null
        && "FIVE_ACTORS".equals(lifecycleEnvironment.getProperty("TASK_IDENTITY_MODEL", "LEGACY"));
  }

  private de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
      ordinaryWorkflow() {
    var workflow =
        ordinaryAccountCreation == null ? null : ordinaryAccountCreation.getIfAvailable();
    if (workflow == null)
      throw new IllegalStateException("Ordinary consultant lifecycle activation remains gated");
    return workflow;
  }

  private Consultant createNewConsultantWithoutAppointment(
      CreateConsultantDTO createConsultantDTO) {
    return createNewConsultantWithoutAppointment(createConsultantDTO, null, null, null);
  }

  private Consultant createNewConsultantWithoutAppointment(
      CreateConsultantDTO createConsultantDTO,
      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.CreationScope
          checkedScope,
      Set<String> checkedRoles,
      java.util.function.Consumer<Consultant> finish) {
    return createNewConsultantWithoutAppointment(
        createConsultantDTO, checkedScope, checkedRoles, finish, null);
  }

  private Consultant createNewConsultantWithoutAppointment(
      CreateConsultantDTO createConsultantDTO,
      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.CreationScope
          checkedScope,
      Set<String> checkedRoles,
      java.util.function.Consumer<Consultant> finish,
      CreationOperationContext context) {
    if (fiveActors() && !multiTenancyEnabled)
      createConsultantDTO.setTenantId(
          ordinaryWorkflow()
              .resolveSingleTenant(
                  createConsultantDTO.getTenantId(), createConsultantDTO.getAgencyIds()));
    else setCurrentTenant(createConsultantDTO);
    validateTenantId(createConsultantDTO);
    ensureTenantIdResolved(createConsultantDTO);
    java.util.Map<Long, java.util.Set<Long>> topicIdsByAgencyId = null;
    if (createConsultantDTO.getAgencyIds() != null
        && !createConsultantDTO.getAgencyIds().isEmpty()) {
      topicIdsByAgencyId =
          consultantTopicAgencyCompatibilityValidator.validateGrantTopicsAgainstSelectedAgencies(
              createConsultantDTO.getTopicIds(),
              createConsultantDTO.getAgencyIds(),
              createConsultantDTO.getTenantId());
    }

    var roles = asSet(CONSULTANT.getValue());
    addGroupChatConsultantRole(createConsultantDTO, roles);
    if (checkedRoles != null) roles = checkedRoles;
    if (fiveActors() && checkedScope == null) {
      // Establish the actual human scope before any full licensing or enrollment policy read.
      checkedScope =
          ordinaryWorkflow()
              .humanScope(
                  lifecycleAdminScope,
                  createConsultantDTO.getTenantId(),
                  createConsultantDTO.getAgencyIds(),
                  "CONSULTANT",
                  roles);
    }
    Prepared prepared = null;
    if (context != null) {
      CreationOperationAccess.validateBounds(createConsultantDTO);
      var scope =
          new Scope(
              java.util.Objects.requireNonNull(createConsultantDTO.getTenantId()),
              createConsultantDTO.getAgencyIds(),
              "COUNSELLOR");
      creationAccess.requireScope(scope);
      prepared =
          new Prepared(
              context.candidate(),
              scope,
              CreationRequestFingerprint.of(createConsultantDTO, scope));
      var existing =
          TrackedConsultantCreation.journalRead(
              () -> creationJournal.findByKey(context.candidate().operationKey()));
      if (existing.isPresent()) observeExisting(context, prepared, existing.get());
      consultantPublicSlugService.validateNewAdminSlug(createConsultantDTO.getPublicSlug());
    }
    boolean checkedMachine =
        checkedScope != null
            && java.util.Set.of("INVITATION", "IMPORT").contains(checkedScope.origin().kind());
    assertLicensesNotExceeded(createConsultantDTO, checkedMachine, context != null);

    this.userAccountInputValidator.validateAbsence(
        new CreateConsultantDTOAbsenceInputAdapter(createConsultantDTO));

    if (context != null && createConsultantDTO.getPassword() == null)
      createConsultantDTO.setPassword(userHelper.getRandomPassword(32));
    ConsultantCreationInput consultantCreationInput =
        new CreateConsultantDTOCreationInputAdapter(createConsultantDTO)
            .withTopicIdsByAgencyId(topicIdsByAgencyId);

    return createNewConsultant(
        consultantCreationInput, roles, checkedScope, finish, context, prepared);
  }

  private void observeExisting(
      CreationOperationContext context, Prepared prepared, Receipt receipt) {
    creationAccess.requireReceiptAccess(receipt);
    context.bind(receipt);
    throw new CreationOperationAccess.ExistingOperation(
        receipt.operationId(),
        !prepared.scope().equals(receipt.scope())
            || !prepared.fingerprint().equals(receipt.requestFingerprint()));
  }

  /**
   * Creates a new {@link Consultant} by {@link CreateConsultantDTO} in the database, identity
   * provider and Matrix, and optionally in the appointment service, provided the appointment
   * feature is enabled.
   *
   * @param createConsultantDTO the input used for creation
   * @return the generated {@link Consultant}
   */
  @Transactional
  public ConsultantAdminResponseDTO createNewConsultant(CreateConsultantDTO createConsultantDTO)
      throws DistributedTransactionException {
    Consultant newConsultant = this.createNewConsultantWithoutAppointment(createConsultantDTO);

    // adminRemarks intentionally stays at the builder's fail-closed default (null) here: this
    // DTO is also the appointment-service payload. ConsultantAdminService#createNewConsultant
    // re-attaches the submitted remarks to the outgoing response for tenant-level admins.
    ConsultantAdminResponseDTO consultantAdminResponseDTO =
        ConsultantResponseDTOBuilder.getInstance(newConsultant).buildResponseDTO();

    if (appointmentFeatureEnabled && !fiveActors()) {
      createConsultantInAppointmentServiceOrRollback(newConsultant, consultantAdminResponseDTO);
    }
    return consultantAdminResponseDTO;
  }

  @Transactional
  public TrackedConsultantCreation createNewConsultant(
      CreateConsultantDTO input, CreationOperationContext context) {
    try {
      var consultant = createNewConsultantWithoutAppointment(input, null, null, null, context);
      var response = ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO();
      if (appointmentFeatureEnabled && !fiveActors())
        createConsultantInAppointmentServiceOrRollback(consultant, response, context);
      return new TrackedConsultantCreation(response, context.receipt().operationId());
    } catch (RuntimeException failure) {
      if (context.persistedOperationId().isEmpty()
          || failure instanceof CreationOperationAccess.ExistingOperation) throw failure;
      Reason reason =
          failure instanceof CreationOperationFailure safe
              ? safe.reason()
              : context.receipt().nativeId() == null
                  ? Reason.NATIVE_OUTCOME_UNKNOWN
                  : Reason.WORKFLOW_FAILED;
      if (context.receipt().workflow() == WorkflowState.IN_PROGRESS) {
        transition(
            context,
            creationWriter,
            new CreationTransition.Workflow(
                context.receipt().nativeId() == null ? WorkflowState.UNKNOWN : WorkflowState.FAILED,
                reason));
      }
      throw TrackedConsultantCreation.failure(context, failure, reason);
    }
  }

  /** Held public invitation owns the final local writes before activation is requested. */
  public <T> T createInvitedConsultant(
      CreateConsultantDTO dto,
      de.caritas.cob.userservice.api.model.AccountInvite invite,
      boolean grantAgencyAdmin,
      java.util.function.Function<Consultant, T> finish) {
    if (!fiveActors()) throw new IllegalStateException("Checked C invitation path is unavailable");
    var roles = new java.util.HashSet<>(Set.of(CONSULTANT.getValue()));
    addGroupChatConsultantRole(dto, roles);
    if (grantAgencyAdmin) roles.addAll(Set.of("restricted-agency-admin", "user-admin"));
    var scope =
        ordinaryWorkflow()
            .heldInvitationScope(
                invite, grantAgencyAdmin ? "CONSULTANT_AGENCY_ADMIN" : "CONSULTANT", roles);
    if (!java.util.Objects.equals(
        scope.tenantId(), dto.getTenantId() == null ? null : dto.getTenantId().toString()))
      throw new org.springframework.security.access.AccessDeniedException(
          "Invitation tenant differs from creation");
    var value = new java.util.concurrent.atomic.AtomicReference<T>();
    createNewConsultantWithoutAppointment(
        dto, scope, roles, consultant -> value.set(finish.apply(consultant)));
    return value.get();
  }

  private void createConsultantInAppointmentServiceOrRollback(
      Consultant newConsultant, ConsultantAdminResponseDTO consultantAdminResponseDTO) {
    try {
      this.appointmentService.createConsultant(consultantAdminResponseDTO);
    } catch (Exception e) {
      log.error(
          "User with id {}, who has roles {}, has created a consultant with id {} but the appointment service returned an error: {}",
          authenticatedUser.getUserId(),
          authenticatedUser.getRoles(),
          newConsultant.getId(),
          e.getMessage());
      this.rollbackCreateNewConsultant(newConsultant);
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .name("createNewConsultant")
              .completedTransactionalOperations(
                  newArrayList(
                      TransactionalStep.CREATE_ACCOUNT_IN_KEYCLOAK,
                      TransactionalStep.UPDATE_USER_PASSWORD_IN_KEYCLOAK,
                      TransactionalStep.UPDATE_USER_ROLES_IN_KEYCLOAK,
                      TransactionalStep.CREATE_CONSULTANT_IN_MARIADB))
              .failedStep(TransactionalStep.CREATE_ACCOUNT_IN_CALCOM_OR_APPOINTMENTSERVICE)
              .build());
    }
  }

  private void createConsultantInAppointmentServiceOrRollback(
      Consultant newConsultant,
      ConsultantAdminResponseDTO consultantAdminResponseDTO,
      CreationOperationContext context) {
    try {
      if (context != null)
        this.appointmentService.createConsultant(consultantAdminResponseDTO, true);
      else this.appointmentService.createConsultant(consultantAdminResponseDTO);
    } catch (Exception e) {
      if (context != null) {
        rollbackCreateNewConsultant(newConsultant, context);
        throw new CreationOperationFailure(
            context.receipt().operationId(), 424, Reason.WORKFLOW_FAILED);
      }
      log.error(
          "User with id {}, who has roles {}, has created a consultant with id {} but the appointment service returned an error: {}",
          authenticatedUser.getUserId(),
          authenticatedUser.getRoles(),
          newConsultant.getId(),
          e.getMessage());
      this.rollbackCreateNewConsultant(newConsultant);
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .name("createNewConsultant")
              .completedTransactionalOperations(
                  newArrayList(
                      TransactionalStep.CREATE_ACCOUNT_IN_KEYCLOAK,
                      TransactionalStep.UPDATE_USER_PASSWORD_IN_KEYCLOAK,
                      TransactionalStep.UPDATE_USER_ROLES_IN_KEYCLOAK,
                      TransactionalStep.CREATE_CONSULTANT_IN_MARIADB))
              .failedStep(TransactionalStep.CREATE_ACCOUNT_IN_CALCOM_OR_APPOINTMENTSERVICE)
              .build());
    }
  }

  private void validateTenantId(CreateConsultantDTO createConsultantDTO) {
    if (multiTenancyEnabled) {
      if (isGlobalTenantContext()) {
        if (createConsultantDTO.getTenantId() == null) {
          throw new BadRequestException(
              "TenantId must be set if consultant is created by superadmin");
        }
      } else {
        checkIfTenantIdMatch(createConsultantDTO);
      }
    }
  }

  private void checkIfTenantIdMatch(CreateConsultantDTO createConsultantDTO) {
    Long effectiveTenantId = resolveEffectiveTenantId();
    if (createConsultantDTO.getTenantId() != null
        && effectiveTenantId != null
        && !createConsultantDTO.getTenantId().equals(effectiveTenantId)) {
      log.error(
          "TenantId of createConsultantDTO {} does not match current tenant {}",
          createConsultantDTO.getTenantId(),
          effectiveTenantId);
      throw new BadRequestException(
          "TenantId of createConsultantDTO does not match current tenant");
    }
  }

  /**
   * Creates a new {@link Consultant} by {@link ImportRecord}.
   *
   * @param importRecord the input record from csv used by the importer service
   * @param roles the roles to add to given {@link Consultant}
   * @return the generated {@link Consultant}
   */
  @Transactional
  public Consultant createNewConsultant(ImportRecord importRecord, Set<String> roles) {
    if (fiveActors())
      throw new org.springframework.security.access.AccessDeniedException(
          "Configured CSV row authorization is required");
    ConsultantCreationInput consultantCreationInput =
        new ImportRecordCreationInputAdapter(importRecord);
    return createNewConsultant(consultantCreationInput, roles);
  }

  /** The configured import owner supplies the exact parsed source and resolved agency set. */
  @Transactional
  public Consultant createImportedConsultant(
      ImportRecord row,
      Set<String> roles,
      Set<Long> agencies,
      String sourceDigest,
      long rowNumber,
      java.util.function.Consumer<Consultant> finish) {
    if (!fiveActors()) throw new IllegalStateException("Checked C import path is unavailable");
    var scope =
        ordinaryWorkflow()
            .configuredImportScope(row.getTenantId(), roles, agencies, sourceDigest, rowNumber);
    assertLicensesNotExceeded(new CreateConsultantDTO().tenantId(row.getTenantId()), true);
    return createNewConsultant(new ImportRecordCreationInputAdapter(row), roles, scope, finish);
  }

  private Consultant createNewConsultant(
      ConsultantCreationInput consultantCreationInput, Set<String> roles) {
    return createNewConsultant(consultantCreationInput, roles, null, null);
  }

  private Consultant createNewConsultant(
      ConsultantCreationInput consultantCreationInput,
      Set<String> roles,
      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.CreationScope
          checkedScope,
      java.util.function.Consumer<Consultant> finish) {
    return createNewConsultant(consultantCreationInput, roles, checkedScope, finish, null, null);
  }

  private Consultant createNewConsultant(
      ConsultantCreationInput consultantCreationInput,
      Set<String> roles,
      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.CreationScope
          checkedScope,
      java.util.function.Consumer<Consultant> finish,
      CreationOperationContext context,
      Prepared prepared) {

    // MATRIX MIGRATION: Get plain credentials from ThreadLocal (captured during JSON
    // deserialization)
    de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.PlainCredentials plainCreds =
        de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.get();

    String plainMatrixUsername =
        context == null
            ? (plainCreds == null ? null : plainCreds.getUsername())
            : consultantCreationInput.getUserName();
    RecoveryPolicySnapshot snapshot =
        chatRecoveryEnrollmentPolicyService.forNewConsultant(consultantCreationInput.getTenantId());
    var inactivityPolicy =
        inactivityEnrollment.capture(
            consultantCreationInput.getTenantId(),
            de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group
                .CONSULTANT);
    if (fiveActors()) {
      var workflow = ordinaryWorkflow();
      var scope =
          checkedScope == null
              ? workflow.humanScope(
                  lifecycleAdminScope,
                  consultantCreationInput.getTenantId(),
                  consultantCreationInput.getAgencyIds(),
                  "CONSULTANT",
                  roles)
              : checkedScope;
      return createOrdinaryConsultant(
          consultantCreationInput, scope, snapshot, inactivityPolicy, finish, context, prepared);
    }
    String keycloakUserId = createKeycloakUser(consultantCreationInput, context, prepared);

    String password = consultantCreationInput.getPassword();
    if ((password == null || password.isEmpty())
        && consultantCreationInput.shouldGeneratePassword()) {
      password = userHelper.getRandomPassword();
      log.info("Using generated password for consultant import");
    } else if (password == null || password.isEmpty()) {
      throw new BadRequestException("Password is required for consultant creation");
    } else {
      log.info("Using the password of the creation request");
    }
    updateKeycloakPasswordOrRollback(consultantCreationInput, keycloakUserId, password, context);
    updateKeyloakRolesOrRollback(roles, keycloakUserId, consultantCreationInput, context);

    // MATRIX MIGRATION: Create Matrix user for consultant with a random password that is never
    // persisted. User-scoped Matrix tokens are minted via Synapse admin login-as-user.
    String matrixUserId = null;
    try {
      if (plainMatrixUsername != null) {
        String matrixPassword = userHelper.getRandomPassword();
        // Privacy: the Matrix localpart and the resulting Matrix ID are user identifiers, and
        // application logs are aggregated, retained and backed up. Only the internal consultant id
        // (the Keycloak id this row is built from) and the outcome go into the log.
        log.info("Provisioning the chat account of consultant {}", keycloakUserId);
        // The Synapse displayname is readable by every member of a shared room, the advice seeker
        // included, so it must never be the real name. The resolver decides which name may go
        // there; the saga only hands it the inputs.
        String matrixDisplayName =
            consultantDisplayNameResolver.resolveMatrixDisplayName(
                consultantCreationInput.getDisplayName(), plainMatrixUsername);
        matrixUserId =
            (context == null
                ? matrixUserClient.createUserId(
                    plainMatrixUsername, matrixPassword, matrixDisplayName)
                : matrixUserClient.createUserIdTracked(
                    plainMatrixUsername, matrixPassword, matrixDisplayName));

        if (matrixUserId != null) {
          log.info("Provisioned the chat account of consultant {}", keycloakUserId);
        } else {
          log.warn(
              "Chat account provisioning for consultant {} answered without a user_id; the"
                  + " consultant is created without a chat identity and must be repaired via POST"
                  + " /useradmin/consultants/{}/chat-identity (#1194)",
              keycloakUserId,
              keycloakUserId);
        }
      } else {
        log.warn(
            "Plain credentials not available from ThreadLocal, skipping chat account provisioning"
                + " for consultant {}. The consultant is created without a chat identity and must"
                + " be repaired via POST /useradmin/consultants/{}/chat-identity (#1194)",
            keycloakUserId,
            keycloakUserId);
      }
    } catch (Exception e) {
      // Not fatal: a chat outage must not stop onboarding. The consultant is stored with
      // chatIdentityStatus = MISSING and repaired via POST .../consultants/{id}/chat-identity.
      if (context != null)
        log.warn("Tracked consultant chat provisioning did not return an identity");
      else
        log.error(
            "Chat account provisioning failed for consultant {}; continuing without a chat identity."
                + " The consultant cannot be used for counselling until it is repaired via POST"
                + " /useradmin/consultants/{}/chat-identity",
            keycloakUserId,
            keycloakUserId,
            e);
    } finally {
      // Clean up ThreadLocal
      de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.clear();
    }

    var consultant =
        createConsultantInMariaDBOrRollback(
            consultantCreationInput,
            keycloakUserId,
            matrixUserId,
            snapshot,
            inactivityPolicy,
            context);

    assignAgenciesOrRollback(consultant, consultantCreationInput.getAgencyIds(), context);
    return consultant;
  }

  private Consultant createOrdinaryConsultant(
      ConsultantCreationInput input,
      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.CreationScope
          scope,
      RecoveryPolicySnapshot snapshot,
      de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy
          inactivityPolicy,
      java.util.function.Consumer<Consultant> finish,
      CreationOperationContext context,
      Prepared prepared) {
    var dto = buildUserDTO(input.getUserName(), input.getEmail(), input.getTenantId());
    userAccountInputValidator.validateUserDTO(dto);
    String password = input.getPassword();
    if ((password == null || password.isBlank()) && input.shouldGeneratePassword())
      password = userHelper.getRandomPassword();
    if (password == null || password.isBlank())
      throw new BadRequestException("Password is required for consultant creation");
    var account =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
            .AccountCreation(
            input.getUserName(),
            input.getEmail(),
            input.getFirstName(),
            input.getLastName(),
            "de",
            password,
            !"INVITATION".equals(scope.origin().kind()) && input.isPasswordChangeRequired());
    var workflow = ordinaryWorkflow();
    try {
      var dispatch = context == null ? null : beginTracked(context, prepared);
      java.util.function.Function<
              de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                  .Pending,
              Consultant>
          localWrites =
              pending -> {
                String id = pending.accountId();
                if (consultantService.getConsultant(id).isPresent())
                  throw new org.springframework.security.access.AccessDeniedException(
                      "New creation cannot adopt an existing consultant");
                String displayName =
                    consultantDisplayNameResolver.resolveMatrixDisplayName(
                        input.getDisplayName(), input.getUserName());
                String matrixId;
                try {
                  matrixId =
                      (context == null
                          ? workflow.createChatAccount(pending, input.getUserName(), displayName)
                          : workflow.createChatAccount(
                              pending, input.getUserName(), displayName, true));
                } catch (IllegalStateException unknown) {
                  if (context != null)
                    throw new CreationOperationFailure(
                        context.receipt().operationId(), 503, Reason.WORKFLOW_FAILED);
                  throw unknown;
                }
                var consultant = buildConsultant(input, id, matrixId);
                consultant.setChatRecoveryMode(snapshot.mode());
                consultant.setChatRecoveryPolicyRevision(snapshot.revision());
                inactivityEnrollment.enroll(id, input.getTenantId(), inactivityPolicy);
                consultant = consultantService.saveConsultant(consultant);
                if (input.getAgencyIds() != null)
                  for (Long agency :
                      input.getAgencyIds().stream()
                          .filter(java.util.Objects::nonNull)
                          .distinct()
                          .toList())
                    consultantAgencyRelationCreatorService.createNewConsultantAgency(
                        id, new CreateConsultantAgencyDTO().agencyId(agency));
                if (appointmentFeatureEnabled) {
                  var response =
                      ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO();
                  if (context == null) workflow.createAppointment(pending, response);
                  else workflow.createAppointment(pending, response, true);
                }
                if (finish != null) finish.accept(consultant);
                return consultant;
              };
      if (context == null) return workflow.create(scope, account, localWrites);
      return workflow.create(
          context.receipt().operationId(),
          nativeId -> {
            dispatch.capture().captured(new CreatedIdentity(nativeId));
            observeTrackedSql(context);
          },
          scope,
          account,
          localWrites);
    } finally {
      de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.clear();
    }
  }

  private void assignAgenciesOrRollback(Consultant consultant, List<Long> agencyIds) {
    assignAgenciesOrRollback(consultant, agencyIds, null);
  }

  private void assignAgenciesOrRollback(
      Consultant consultant, List<Long> agencyIds, CreationOperationContext context) {
    if (agencyIds == null) {
      return;
    }
    try {
      agencyIds.stream()
          .filter(java.util.Objects::nonNull)
          .distinct()
          .forEach(
              agencyId ->
                  consultantAgencyRelationCreatorService.createNewConsultantAgency(
                      consultant.getId(), new CreateConsultantAgencyDTO().agencyId(agencyId)));
    } catch (RuntimeException e) {
      rollbackCreateNewConsultant(consultant, context);
      if (context != null)
        throw TrackedConsultantCreation.failure(context, e, Reason.WORKFLOW_FAILED);
      throw e;
    }
  }

  private void updateKeycloakPasswordOrRollback(
      ConsultantCreationInput consultantCreationInput,
      String keycloakUserId,
      String password,
      CreationOperationContext context) {
    try {
      if (consultantCreationInput.isPasswordChangeRequired()) {
        identityPasswordUpdater.updateTemporaryPassword(keycloakUserId, password);
      } else {
        identityPasswordUpdater.updatePassword(keycloakUserId, password);
      }
    } catch (CustomValidationHttpStatusException e) {
      rollbackCreateNewConsultant(
          buildConsultantDataForRollback(consultantCreationInput, keycloakUserId), context);
      throw e;
    } catch (Exception e) {
      if (context != null) {
        rollbackCreateNewConsultant(
            buildConsultantDataForRollback(consultantCreationInput, keycloakUserId), context);
        throw new CreationOperationFailure(
            context.receipt().operationId(), 424, Reason.WORKFLOW_FAILED);
      }
      log.error(
          "Unable to update password or roles for user with encoded username {}",
          consultantCreationInput.getEncodedUsername());
      rollbackCreateNewConsultant(
          buildConsultantDataForRollback(consultantCreationInput, keycloakUserId), context);
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .name(CREATE_CONSULTANT)
              .completedTransactionalOperations(
                  newArrayList(TransactionalStep.CREATE_ACCOUNT_IN_KEYCLOAK))
              .failedStep(TransactionalStep.UPDATE_USER_PASSWORD_IN_KEYCLOAK)
              .build());
    }
  }

  private void updateKeyloakRolesOrRollback(
      Set<String> roles,
      String keycloakUserId,
      ConsultantCreationInput consultantCreationInput,
      CreationOperationContext context) {
    try {
      roles.forEach(roleName -> identityClient.updateRole(keycloakUserId, roleName));
    } catch (Exception e) {
      if (context != null) {
        rollbackCreateNewConsultant(
            buildConsultantDataForRollback(consultantCreationInput, keycloakUserId), context);
        throw new CreationOperationFailure(
            context.receipt().operationId(), 424, Reason.WORKFLOW_FAILED);
      }
      log.error(
          "Unable to update roles for user with keycloak id {}. Initiating user rollback.",
          keycloakUserId);
      rollbackCreateNewConsultant(
          buildConsultantDataForRollback(consultantCreationInput, keycloakUserId), context);
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .completedTransactionalOperations(
                  newArrayList(
                      TransactionalStep.CREATE_ACCOUNT_IN_KEYCLOAK,
                      TransactionalStep.UPDATE_USER_PASSWORD_IN_KEYCLOAK))
              .name(CREATE_CONSULTANT)
              .failedStep(TransactionalStep.UPDATE_USER_ROLES_IN_KEYCLOAK)
              .build());
    }
  }

  private void observeTrackedSql(CreationOperationContext context) {
    transition(
        context, creationWriter, new CreationTransition.Row(RowState.COMMIT_UNKNOWN, Reason.NONE));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              try {
                transition(
                    context,
                    creationWriter,
                    new CreationTransition.Row(
                        status == STATUS_COMMITTED
                            ? RowState.COMMITTED
                            : status == STATUS_ROLLED_BACK
                                ? RowState.ROLLED_BACK
                                : RowState.COMMIT_UNKNOWN,
                        status == STATUS_COMMITTED ? Reason.NONE : Reason.ROW_COMMIT_FAILED));
              } catch (RuntimeException unavailable) {
                log.error("Creation operation transaction observation could not be persisted");
              }
            }
          });
    }
  }

  private Consultant createConsultantInMariaDBOrRollback(
      ConsultantCreationInput consultantCreationInput,
      String keycloakUserId,
      String matrixUserId,
      RecoveryPolicySnapshot snapshot,
      de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy
          inactivityPolicy,
      CreationOperationContext context) {
    if (context != null) observeTrackedSql(context);
    var existing = consultantService.getConsultant(keycloakUserId);
    if (existing.isPresent()) return existing.get();
    // Preserve legacy construction placement. Tracked construction can fail after native capture
    // (for example a final slug uniqueness race), so it belongs to the existing row-step rollback.
    Consultant consultant =
        context == null
            ? buildConsultant(consultantCreationInput, keycloakUserId, matrixUserId)
            : null;
    try {
      if (context != null)
        consultant = buildConsultant(consultantCreationInput, keycloakUserId, matrixUserId);
      consultant.setChatRecoveryMode(snapshot.mode());
      consultant.setChatRecoveryPolicyRevision(snapshot.revision());
      inactivityEnrollment.enroll(keycloakUserId, consultant.getTenantId(), inactivityPolicy);
      return consultantService.saveConsultant(consultant);
    } catch (Exception e) {
      if (context != null) {
        if (consultant == null) {
          consultant = buildConsultantDataForRollback(consultantCreationInput, keycloakUserId);
          consultant.setMatrixUserId(matrixUserId);
        }
        rollbackCreateNewConsultant(consultant, context);
        throw new CreationOperationFailure(
            context.receipt().operationId(),
            e instanceof RuntimeException runtime
                    && TrackedConsultantCreation.status(runtime) == 409
                ? 409
                : 500,
            Reason.WORKFLOW_FAILED);
      }
      log.error(
          "Unable to create consultant with encoded username {} in database. Rolling back identity creation",
          consultantCreationInput.getEncodedUsername());
      rollbackCreateNewConsultant(consultant);

      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .name(CREATE_CONSULTANT)
              .completedTransactionalOperations(
                  newArrayList(
                      TransactionalStep.CREATE_ACCOUNT_IN_KEYCLOAK,
                      TransactionalStep.UPDATE_USER_PASSWORD_IN_KEYCLOAK,
                      TransactionalStep.UPDATE_USER_ROLES_IN_KEYCLOAK))
              .failedStep(TransactionalStep.CREATE_CONSULTANT_IN_MARIADB)
              .build());
    }
  }

  private void setCurrentTenant(CreateConsultantDTO createConsultantDTO) {
    if (createConsultantDTO.getTenantId() == null) {
      Long effectiveTenantId = resolveEffectiveTenantId();
      if (effectiveTenantId != null && effectiveTenantId > 0) {
        createConsultantDTO.setTenantId(effectiveTenantId);
      }
    }
  }

  private void ensureTenantIdResolved(CreateConsultantDTO createConsultantDTO) {
    if (createConsultantDTO.getTenantId() != null) {
      return;
    }
    Long effectiveTenantId = resolveEffectiveTenantId();
    if (effectiveTenantId != null && effectiveTenantId > 0) {
      createConsultantDTO.setTenantId(effectiveTenantId);
      return;
    }
    if (isGlobalTenantContext()) {
      throw new BadRequestException("TenantId must be set if consultant is created by superadmin");
    }
  }

  private record TrackedDispatch(
      NativeCreationCapture capture, java.util.concurrent.atomic.AtomicReference<Reason> failure) {}

  private TrackedDispatch beginTracked(CreationOperationContext context, Prepared prepared) {
    Claim claim;
    try {
      claim = creationJournal.claim(prepared);
    } catch (CreationOperationFailure collision) {
      if (collision.status() != 409 && collision.status() != 404)
        throw TrackedConsultantCreation.journalUnavailable();
      var existing =
          TrackedConsultantCreation.journalRead(
                  () -> creationJournal.findByKey(context.candidate().operationKey()))
              .orElseThrow(CreationOperationAccess::denied);
      observeExisting(context, prepared, existing);
      throw new IllegalStateException("Unreachable operation observation");
    } catch (RuntimeException unavailable) {
      throw TrackedConsultantCreation.journalUnavailable();
    }
    if (!claim.newlyInserted()) observeExisting(context, prepared, claim.receipt());
    context.bind(claim.receipt());
    final NativeCreationCapture capture;
    try {
      capture = creationJournal.beginDispatch(context, claim);
    } catch (RuntimeException failure) {
      throw new CreationOperationFailure(
          context.receipt().operationId(), 503, Reason.JOURNAL_UNAVAILABLE);
    }
    transition(
        context,
        creationWriter,
        new CreationTransition.Workflow(WorkflowState.IN_PROGRESS, Reason.NONE));
    var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Reason>();
    NativeCreationCapture observed =
        new NativeCreationCapture() {
          @Override
          public void captured(CreatedIdentity identity) {
            try {
              capture.captured(identity);
            } catch (RuntimeException failure) {
              callbackFailure.set(Reason.NATIVE_CAPTURE_FAILED);
              throw failure;
            }
          }

          @Override
          public void compensationStarting() {
            try {
              capture.compensationStarting();
            } catch (RuntimeException failure) {
              callbackFailure.set(Reason.JOURNAL_UNAVAILABLE);
              throw failure;
            }
          }

          @Override
          public void compensationReturned() {
            try {
              capture.compensationReturned();
            } catch (RuntimeException failure) {
              callbackFailure.set(Reason.JOURNAL_UNAVAILABLE);
              throw failure;
            }
          }
        };
    return new TrackedDispatch(observed, callbackFailure);
  }

  private String createKeycloakUser(
      ConsultantCreationInput consultantCreationInput,
      CreationOperationContext context,
      Prepared prepared) {
    // MATRIX MIGRATION: Use PLAIN username for Keycloak (Keycloak rejects encrypted usernames)
    String plainUsername = consultantCreationInput.getUserName();

    // Store plain username in ThreadLocal for Matrix creation
    if (context == null)
      de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.set(plainUsername, null);

    UserDTO userDto =
        buildUserDTO(
            plainUsername,
            consultantCreationInput.getEmail(),
            consultantCreationInput.getTenantId());

    this.userAccountInputValidator.validateUserDTO(userDto);

    if (context != null) {
      var dispatch = beginTracked(context, prepared);
      var observed = dispatch.capture();
      var callbackFailure = dispatch.failure();
      try {
        return CreatedIdentity.requireUserId(
            identityClient.createUser(
                userDto,
                consultantCreationInput.getFirstName(),
                consultantCreationInput.getLastName(),
                observed));
      } catch (RuntimeException failure) {
        Reason reason = callbackFailure.get();
        if (reason == null)
          reason =
              context.receipt().nativeId() == null
                  ? Reason.NATIVE_OUTCOME_UNKNOWN
                  : Reason.MANDATORY_ATTRIBUTES_FAILED;
        throw new CreationOperationFailure(
            context.receipt().operationId(),
            callbackFailure.get() != null
                ? 503
                : failure
                        instanceof
                        de.caritas.cob.userservice.api.port.out.identity.NativeCreationConflict
                    ? 409
                    : 424,
            reason);
      }
    }
    CreatedIdentity response =
        identityClient.createUser(
            userDto, consultantCreationInput.getFirstName(), consultantCreationInput.getLastName());

    return CreatedIdentity.requireUserId(response);
  }

  private static Consultant buildConsultantDataForRollback(
      ConsultantCreationInput consultantCreationInput, String keycloakUserId) {
    return Consultant.builder()
        .id(keycloakUserId)
        .tenantId(consultantCreationInput.getTenantId())
        .username(consultantCreationInput.getEncodedUsername())
        .firstName(consultantCreationInput.getFirstName())
        .lastName(consultantCreationInput.getLastName())
        .email(consultantCreationInput.getEmail())
        .magicLinkLoginEnabled(false)
        .build();
  }

  private Consultant buildConsultant(
      ConsultantCreationInput consultantCreationInput, String keycloakUserId, String matrixUserId) {

    var consultant =
        Consultant.builder()
            .id(keycloakUserId)
            .idOld(consultantCreationInput.getIdOld())
            .username(consultantCreationInput.getEncodedUsername())
            .firstName(consultantCreationInput.getFirstName())
            .lastName(consultantCreationInput.getLastName())
            .email(consultantCreationInput.getEmail())
            .absent(isTrue(consultantCreationInput.isAbsent()))
            .absenceMessage(consultantCreationInput.getAbsenceMessage())
            .displayName(consultantCreationInput.getDisplayName())
            .internalDisplayName(consultantCreationInput.getInternalDisplayName())
            .salutation(consultantCreationInput.getSalutation())
            .position(consultantCreationInput.getPosition())
            .title(consultantCreationInput.getTitle())
            .adminRemarks(consultantCreationInput.getAdminRemarks())
            .teamConsultant(consultantCreationInput.isTeamConsultant())
            .matrixUserId(matrixUserId)
            .encourage2fa(true)
            .twoFactorRequired(consultantCreationInput.isTwoFactorRequired())
            .passwordChangeRequired(consultantCreationInput.isPasswordChangeRequired())
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .languageFormal(consultantCreationInput.isLanguageFormal())
            .languages(Set.of())
            .createDate(consultantCreationInput.getCreateDate())
            .updateDate(consultantCreationInput.getUpdateDate())
            .tenantId(consultantCreationInput.getTenantId())
            .status(ConsultantStatus.CREATED)
            .walkThroughEnabled(false)
            .languageCode(LanguageCode.de)
            .notificationsEnabled(true)
            .notificationsSettings(serializeToJsonString(allActiveNotifications()))
            .build();

    consultant.assignInitialTopics(
        consultantCreationInput.getTopicIds(), consultantCreationInput.getTopicIdsByAgencyId());
    // Normalised in one shared place so a half avatar choice can never be persisted.
    ConsultantAvatars.apply(
        consultant,
        ConsultantAvatarKind.fromNameOrNull(consultantCreationInput.getAvatarKind()),
        consultantCreationInput.getAvatarId());
    consultantPublicSlugService.applyAdminSlug(consultant, consultantCreationInput.getPublicSlug());
    return consultant;
  }

  private NotificationsSettingsDTO allActiveNotifications() {
    NotificationsSettingsDTO notificationsSettingsDTO = new NotificationsSettingsDTO();
    notificationsSettingsDTO.setReassignmentNotificationEnabled(true);
    notificationsSettingsDTO.setInitialEnquiryNotificationEnabled(true);
    notificationsSettingsDTO.setAppointmentNotificationEnabled(true);
    notificationsSettingsDTO.setNewChatMessageNotificationEnabled(true);
    return notificationsSettingsDTO;
  }

  private UserDTO buildUserDTO(String username, String email, Long tenantId) {
    UserDTO userDto = new UserDTO();
    // MATRIX MIGRATION: Don't encrypt username here - use it as-is (already plain from
    // getUserName())
    userDto.setUsername(username);
    userDto.setEmail(email);
    userDto.setTenantId(tenantId);
    return userDto;
  }

  /**
   * Enforces the tenant's licensed user limit, when one is configured.
   *
   * <p>{@code TenantDTO.licensing} is optional in the tenant admin contract: every tenant created
   * through the invite flow has {@code licensing_allowed_users = NULL} — only the seed tenant
   * carries a number. A missing cap therefore means <em>no limit</em> and must not refuse creation;
   * unboxing it straight into the comparison used to kill consultant creation for every new tenant
   * with a bare 500, and the previous {@code assert nonNull(...)} guard never executed in
   * production because the JVM strips assertions unless it is started with {@code -ea}.
   *
   * <p>Only states in which the limit cannot be established at all refuse creation: an unresolvable
   * tenant id is a plain 400, and an unreadable tenant is reported as a named {@code
   * TENANT_LICENSING_NOT_CONFIGURED} 400 that the admin UI can render — silently treating a
   * TenantService outage as "unlimited" would let creations slip past a limit that is merely
   * unreadable at that moment.
   */
  private void assertLicensesNotExceeded(
      CreateConsultantDTO createConsultantDTO, boolean checkedMachine) {
    assertLicensesNotExceeded(createConsultantDTO, checkedMachine, false);
  }

  private void assertLicensesNotExceeded(
      CreateConsultantDTO createConsultantDTO, boolean checkedMachine, boolean fixedDiagnostics) {
    if (fiveActors() && checkedMachine) {
      if (checkedPolicyReads == null || createConsultantDTO.getTenantId() == null)
        throw new org.springframework.security.access.AccessDeniedException(
            "Checked account policy origin required");
      final Integer limit;
      try {
        limit = checkedPolicyReads.read(createConsultantDTO.getTenantId()).allowedNumberOfUsers();
      } catch (IllegalStateException unavailable) {
        throw new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.TENANT_LICENSING_NOT_CONFIGURED);
      }
      if (limit != null
          && consultantService.getNumberOfActiveConsultants(createConsultantDTO.getTenantId())
              >= limit)
        throw new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.NUMBER_OF_LICENSES_EXCEEDED);
      return;
    }
    if (!multiTenancyEnabled) {
      return;
    }

    var tenantId = createConsultantDTO.getTenantId();
    if (isNull(tenantId)) {
      // ensureTenantIdResolved only throws for a superadmin (global) context, so a request whose
      // tenant is in neither the body, the access token nor the tenant context reaches here with
      // nothing resolved. Everything below is tenant-scoped: the lookup would be a doomed remote
      // call with a null id, and its failure would then be misreported as a licensing problem.
      throw new BadRequestException(
          "TenantId could not be resolved for the consultant to be created");
    }

    TenantDTO tenant;
    try {
      tenant = tenantAdminService.getTenantById(tenantId);
    } catch (RestClientException exception) {
      if (fixedDiagnostics) log.warn("Tracked consultant licensing lookup failed");
      else
        log.warn(
            "TenantService could not be reached for tenant {}; refusing consultant creation because"
                + " its licensed user limit cannot be established.",
            tenantId,
            exception);
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.TENANT_LICENSING_NOT_CONFIGURED);
    }
    if (isNull(tenant)) {
      log.warn(
          "TenantService returned no tenant {}; refusing consultant creation because its licensed"
              + " user limit cannot be established.",
          tenantId);
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.TENANT_LICENSING_NOT_CONFIGURED);
    }

    // No configured limit means no limit — the invite-flow default, see the javadoc above.
    var licensing = tenant.getLicensing();
    Integer allowedNumberOfUsers = isNull(licensing) ? null : licensing.getAllowedNumberOfUsers();
    if (isNull(allowedNumberOfUsers)) {
      return;
    }

    // Licenses are counted per tenant, so always scope the active-consultant count to the
    // target tenant. Relying on the ambient tenant filter for the current context counts
    // consultants across all tenants, which falsely triggers NUMBER_OF_LICENSES_EXCEEDED when a
    // tenant admin creates a consultant.
    long numberOfActiveConsultants = consultantService.getNumberOfActiveConsultants(tenantId);
    if (numberOfActiveConsultants >= allowedNumberOfUsers) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.NUMBER_OF_LICENSES_EXCEEDED);
    }
  }

  private void addGroupChatConsultantRole(
      CreateConsultantDTO createConsultantDTO, Set<String> roles) {
    if (Boolean.TRUE.equals(createConsultantDTO.getIsGroupchatConsultant())) {
      roles.add(GROUP_CHAT_CONSULTANT.getValue());
    }
  }

  public void rollbackCreateNewConsultant(Consultant newConsultant) {
    log.info("Rollback creation of consultant with id {}", newConsultant.getId());
    rollbackFacade.rollbackConsultantAccount(newConsultant);
  }

  private void rollbackCreateNewConsultant(
      Consultant consultant, CreationOperationContext context) {
    if (context == null) {
      rollbackCreateNewConsultant(consultant);
      return;
    }
    transition(
        context,
        creationWriter,
        new CreationTransition.Compensation(
            CompensationState.STARTED_OUTCOME_UNKNOWN, Reason.NONE));
    rollbackFacade.rollbackConsultantAccount(consultant, true);
    transition(
        context,
        creationWriter,
        new CreationTransition.Compensation(CompensationState.RETURNED_UNVERIFIED, Reason.NONE));
  }

  private boolean isGlobalTenantContext() {
    Long effectiveTenantId = resolveEffectiveTenantId();
    return effectiveTenantId != null && effectiveTenantId == 0L;
  }

  private Long resolveEffectiveTenantId() {
    Long tokenTenantId = getTenantIdFromAccessToken();
    if (tokenTenantId != null) {
      return tokenTenantId;
    }
    return TenantContext.getCurrentTenant();
  }

  private Long getTenantIdFromAccessToken() {
    try {
      String accessToken = authenticatedUser.getAccessToken();
      if (accessToken == null || accessToken.isBlank()) {
        return null;
      }
      String[] tokenParts = accessToken.split("\\.");
      if (tokenParts.length < 2) {
        return null;
      }
      String payload =
          new String(Base64.getUrlDecoder().decode(tokenParts[1]), StandardCharsets.UTF_8);
      JsonNode payloadNode = objectMapper.readTree(payload);
      JsonNode tenantIdNode = payloadNode.get("tenantId");
      if (tenantIdNode == null || tenantIdNode.isNull()) {
        return null;
      }
      if (tenantIdNode.isNumber()) {
        return tenantIdNode.asLong();
      }
      if (tenantIdNode.isTextual()) {
        String tenantIdText = tenantIdNode.asText();
        if (tenantIdText.isBlank()) {
          return null;
        }
        return Long.parseLong(tenantIdText);
      }
      return null;
    } catch (Exception exception) {
      return null;
    }
  }

  /** Read-only startup check of the actual installed production consumers. */
  public boolean hasScopedAccountBindings(
      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation owner) {
    return ordinaryAccountCreation != null
        && ordinaryAccountCreation.getIfAvailable() == owner
        && lifecycleEnvironment != null
        && "FIVE_ACTORS".equals(lifecycleEnvironment.getProperty("TASK_IDENTITY_MODEL", "LEGACY"));
  }
}
