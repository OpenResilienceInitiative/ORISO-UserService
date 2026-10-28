package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static com.google.common.collect.Lists.newArrayList;
import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.helper.json.JsonSerializationUtils.serializeToJsonString;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantResponseDTOBuilder;
import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionInfo;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Multi-identity foundation: grants an existing admin user a full functional consultant identity.
 *
 * <p>The admin's existing Keycloak account is reused (no new Keycloak user is created and the
 * password is not changed). The {@code consultant} realm role is added, a Matrix account is created
 * (best effort), a {@link Consultant} row sharing the admin's Keycloak id is persisted and the
 * requested consultant_agency links are created — with keycloak-role rollback should the consultant
 * row fail to persist.
 *
 * <p>The admin and consultant tables are independent (different PKs, no cross-FKs) so the same
 * Keycloak id may legitimately hold both an {@code admin} row and a {@code consultant} row; no
 * schema change is required.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GrantConsultantIdentityService {

  private final ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;
  private static final String GRANT_CONSULTANT_IDENTITY = "grantConsultantIdentity";

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityRoleUpdater identityRoleUpdater;
  private final @NonNull MatrixUserClient matrixUserClient;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;
  private final @NonNull UserHelper userHelper;
  private final @NonNull ConsultantTopicAgencyCompatibilityValidator
      consultantTopicAgencyCompatibilityValidator;
  private final @NonNull AdminScope adminScope;
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityMode;
  private de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance maintenance;
  private org.springframework.transaction.support.TransactionTemplate localWrites;
  private String matrixServer;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;

  @org.springframework.beans.factory.annotation.Autowired
  public void setScopedMaintenance(
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode,
      @org.springframework.lang.Nullable
          de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance owner) {
    identityMode = Objects.requireNonNull(mode);
    maintenance = owner;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public void setLocalTransactionManager(
      org.springframework.transaction.PlatformTransactionManager manager) {
    localWrites = new org.springframework.transaction.support.TransactionTemplate(manager);
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setMatrixConfiguration(
      de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig configuration) {
    matrixServer = configuration.getServerName();
  }

  /**
   * Grants an existing admin user a full functional consultant identity.
   *
   * @param adminId the Keycloak id of the existing admin user
   * @param dto the consultant-specific attributes for the new identity
   * @return the {@link ConsultantAdminResponseDTO} describing the created consultant identity
   */
  public ConsultantAdminResponseDTO grantConsultantIdentityToAdmin(
      String adminId, GrantConsultantIdentityDTO dto) {
    if (identityMode != null && identityMode.isFiveActorMode()) {
      if (org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()
          || maintenance == null
          || !maintenance.isFiveActorMode()
          || entities == null
          || matrixServer == null
          || matrixServer.isBlank()
          || matrixServer.contains("@")
          || matrixServer.chars().anyMatch(c -> c <= 32 || c == 127))
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      try (var grant = new ScopedGrant(adminId, dto)) {
        return maintenance.grantConsultantIdentity(grant);
      }
    }
    return localWrites == null
        ? grantLegacy(adminId, dto)
        : localWrites.execute(ignored -> grantLegacy(adminId, dto));
  }

  /** Selects an existing owned self-assignment action without minting any mutation authority. */
  public boolean hasOriginalSelfAssignmentGrant(String accountId) {
    if (maintenance == null || !maintenance.isFiveActorMode())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    return maintenance.hasOriginalSelfAssignmentGrant(accountId);
  }

  private ConsultantAdminResponseDTO grantLegacy(String adminId, GrantConsultantIdentityDTO dto) {

    // Looked up across tenants on purpose: an admin of another Träger must be refused (403) by the
    // scope check below, not reported as unknown.
    var admin =
        TenantContext.supplyAcrossTenants(() -> adminRepository.findById(adminId))
            .orElseThrow(
                () ->
                    new BadRequestException(String.format("Admin with id %s not found", adminId)));

    adminScope.assertMay(Target.admin(admin.getId()));
    adminScope.assertMay(Target.agencies(dto.getAgencyIds()));

    if (consultantRepository.findByIdAndDeleteDateIsNull(adminId).isPresent()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CONSULTANT_IDENTITY_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }

    var encodedUsername = usernameTranscoder.encodeUsername(admin.getUsername());
    if (consultantRepository.findByUsernameAndDeleteDateIsNull(encodedUsername).isPresent()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CONSULTANT_IDENTITY_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }

    var topicIdsByAgencyId =
        consultantTopicAgencyCompatibilityValidator.validateGrantTopicsAgainstSelectedAgencies(
            dto.getTopicIds(), dto.getAgencyIds(), admin.getTenantId());

    var snapshot = chatRecoveryEnrollmentPolicyService.forNewConsultant(admin.getTenantId());
    assignKeycloakRoles(adminId, dto);

    String matrixUserId = createMatrixAccount(admin);

    var consultant = buildConsultant(admin, encodedUsername, dto, matrixUserId);
    consultant.assignInitialTopics(dto.getTopicIds(), topicIdsByAgencyId);
    consultant.setChatRecoveryMode(snapshot.mode());
    consultant.setChatRecoveryPolicyRevision(snapshot.revision());
    saveConsultantOrRollback(adminId, dto, consultant);

    try {
      assignAgencies(adminId, dto);
    } catch (RuntimeException e) {
      removeGrantedRoles(adminId, dto);
      throw e;
    }

    return ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO();
  }

  /** Only the actual Grant producer can issue this complete immutable callback. */
  public final class ScopedGrant implements AutoCloseable {
    private final String account;
    private final GrantConsultantIdentityDTO input;
    private final de.caritas.cob.userservice.api.model.Admin source;
    private final java.util.Map<Long, Set<Long>> topics;
    private final ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot recovery;
    private final List<ConsultantAgencyRelationCreatorService.GrantAgency> agencies;
    private final Set<String> required;
    private final String intent;
    private final String server;
    private final Thread thread = Thread.currentThread();
    private boolean active = true;
    private Instant creation;
    private String matrixIdentity;

    private ScopedGrant(String account, GrantConsultantIdentityDTO requested) {
      this.account = account;
      server = matrixServer;
      if (requested == null
          || requested.getAgencyIds() == null
          || requested.getAgencyIds().isEmpty()
          || requested.getAgencyIds().size() > 100
          || requested.getAgencyIds().stream().anyMatch(id -> id == null || id <= 0)
          || new java.util.HashSet<>(requested.getAgencyIds()).size()
              != requested.getAgencyIds().size())
        throw new BadRequestException("Consultant grant agencies are invalid");
      try {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        input =
            json.readValue(json.writeValueAsString(requested), GrantConsultantIdentityDTO.class);
      } catch (java.io.IOException invalid) {
        throw new BadRequestException("Consultant grant is invalid");
      }
      var original = currentAdmin();
      source =
          de.caritas.cob.userservice.api.model.Admin.builder()
              .id(original.getId())
              .tenantId(original.getTenantId())
              .type(original.getType())
              .username(original.getUsername())
              .firstName(original.getFirstName())
              .lastName(original.getLastName())
              .email(original.getEmail())
              .createDate(original.getCreateDate())
              .updateDate(original.getUpdateDate())
              .oldId(original.getOldId())
              .managedPaused(original.isManagedPaused())
              .build();
      adminScope.assertMay(Target.admin(account));
      adminScope.assertMay(Target.agencies(input.getAgencyIds()));
      var resolved =
          consultantTopicAgencyCompatibilityValidator.validateGrantTopicsAgainstSelectedAgencies(
              input.getTopicIds(), input.getAgencyIds(), source.getTenantId());
      var copied = new java.util.TreeMap<Long, Set<Long>>();
      resolved.forEach((id, values) -> copied.put(id, Set.copyOf(values)));
      topics = java.util.Collections.unmodifiableMap(copied);
      recovery = chatRecoveryEnrollmentPolicyService.forNewConsultant(source.getTenantId());
      agencies = consultantAgencyRelationCreatorService.resolveGrantAgencies(this);
      var roles = new java.util.TreeSet<String>();
      roles.add(CONSULTANT.getValue());
      if (input.isGroupchatConsultant()) roles.add(GROUP_CHAT_CONSULTANT.getValue());
      agencies.forEach(agency -> roles.addAll(agency.requiredRoles()));
      if (!Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
          .containsAll(roles))
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      required = Set.copyOf(roles);
      var fields = new java.util.TreeMap<String, Object>();
      fields.put("request", input);
      var stableAgencies = new java.util.ArrayList<java.util.Map<String, Object>>();
      for (var agency : agencies) {
        var data = new java.util.TreeMap<String, Object>();
        data.put("id", agency.id());
        data.put("tenant", agency.tenant());
        data.put("consultingType", agency.consultingType());
        data.put("team", agency.team());
        data.put("bounded", agency.bounded());
        data.put("requiredRoles", agency.requiredRoles().stream().sorted().toList());
        stableAgencies.add(data);
      }
      fields.put("agencies", stableAgencies);
      var stableTopics = new java.util.TreeMap<Long, List<Long>>();
      topics.forEach((id, values) -> stableTopics.put(id, values.stream().sorted().toList()));
      fields.put("topics", stableTopics);
      fields.put("recovery", recovery);
      fields.put("requiredRoles", roles);
      fields.put("matrixServer", server);
      try {
        intent = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fields);
      } catch (java.io.IOException invalid) {
        throw new BadRequestException("Consultant grant is invalid");
      }
    }

    private void requireActive() {
      if (!active || thread != Thread.currentThread())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    private de.caritas.cob.userservice.api.model.Admin currentAdmin() {
      requireActive();
      var found =
          TenantContext.supplyAcrossTenants(() -> adminRepository.findById(account))
              .orElseThrow(() -> new BadRequestException("Admin not found"));
      if (org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()
          && entities.contains(found)) entities.refresh(found);
      return found;
    }

    public String accountId() {
      requireActive();
      return account;
    }

    public Long tenantId() {
      requireActive();
      return source.getTenantId();
    }

    public String username() {
      requireActive();
      return source.getUsername();
    }

    public List<Long> agencyIds() {
      requireActive();
      return List.copyOf(input.getAgencyIds());
    }

    public List<ConsultantAgencyRelationCreatorService.GrantAgency> agencies() {
      requireActive();
      return agencies;
    }

    public Set<String> requiredRoles() {
      requireActive();
      return required;
    }

    public java.util.Map<Long, Set<Long>> topicsByAgency() {
      requireActive();
      return topics;
    }

    public String intentJson() {
      requireActive();
      return intent;
    }

    public String localpart() {
      requireActive();
      return source.getUsername().toLowerCase(java.util.Locale.ROOT);
    }

    public void requireJpaOwner(jakarta.persistence.EntityManagerFactory factory) {
      requireActive();
      if (entities == null || factory == null || entities.getEntityManagerFactory() != factory)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public void bindCreation(Instant chosen) {
      requireActive();
      if (chosen == null
          || chosen.getEpochSecond() <= 0
          || chosen.getNano() % 1000 != 0
          || creation != null && !creation.equals(chosen))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Grant creation changed");
      creation = chosen;
    }

    public void bindMatrixResult(String result) {
      requireActive();
      if (result != null && !result.equals("@" + localpart() + ":" + server))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Matrix result is not owned by this grant");
      matrixIdentity = result;
    }

    public String createMatrixWithoutReactivation()
        throws de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException {
      requireActive();
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      return matrixUserClient.createUserIdWithoutReactivation(
          localpart(),
          userHelper.getRandomPassword(),
          consultantDisplayNameResolver.resolveMatrixDisplayName(null, source.getUsername()));
    }

    public void validate() {
      var admin = currentAdmin();
      adminScope.assertMay(Target.admin(account));
      if (!Objects.equals(admin.getTenantId(), source.getTenantId())
          || !Objects.equals(admin.getType(), source.getType())
          || !Objects.equals(admin.getUsername(), source.getUsername())
          || !Objects.equals(admin.getFirstName(), source.getFirstName())
          || !Objects.equals(admin.getLastName(), source.getLastName())
          || !Objects.equals(admin.getEmail(), source.getEmail())
          || !Objects.equals(admin.getCreateDate(), source.getCreateDate())
          || !Objects.equals(admin.getUpdateDate(), source.getUpdateDate())
          || !Objects.equals(admin.getOldId(), source.getOldId())
          || admin.isManagedPaused() != source.isManagedPaused()
          || consultantRepository.findById(account).isPresent()
          || consultantRepository
              .findByUsernameAndDeleteDateIsNull(
                  usernameTranscoder.encodeUsername(source.getUsername()))
              .isPresent())
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Consultant grant source changed");
    }

    public void validateOutsideSql() {
      requireActive();
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      adminScope.assertMay(Target.admin(account));
      adminScope.assertMay(Target.agencies(input.getAgencyIds()));
      if (!agencies.equals(consultantAgencyRelationCreatorService.resolveGrantAgencies(this)))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Consultant grant agencies changed");
    }

    public void requireLocalCurrent() {
      requireActive();
      if (!org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()
          || org.springframework.transaction.support.TransactionSynchronizationManager.getResource(
                  this)
              != this
          || !entities.isJoinedToTransaction()
          || creation == null)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    private Consultant expectedConsultant() {
      if (creation == null)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      var consultant =
          buildConsultant(
              source,
              usernameTranscoder.encodeUsername(source.getUsername()),
              input,
              matrixIdentity);
      var born = LocalDateTime.ofInstant(creation, ZoneOffset.UTC);
      consultant.setCreateDate(born);
      consultant.setUpdateDate(born);
      consultant.assignInitialTopics(input.getTopicIds(), topics);
      if (consultant.getConsultantTopics() != null)
        consultant
            .getConsultantTopics()
            .forEach(
                topic -> {
                  topic.setCreateDate(born);
                  topic.setUpdateDate(born);
                });
      consultant.setChatRecoveryMode(recovery.mode());
      consultant.setChatRecoveryPolicyRevision(recovery.revision());
      consultant.setTeamConsultant(
          agencies.stream().anyMatch(ConsultantAgencyRelationCreatorService.GrantAgency::team));
      return consultant;
    }

    public void apply() {
      requireLocalCurrent();
      validate();
      consultantService.saveConsultant(expectedConsultant());
      consultantAgencyRelationCreatorService.createCheckedGrantAgencies(this);
    }

    public LocalDateTime creationDate() {
      requireLocalCurrent();
      return LocalDateTime.ofInstant(creation, ZoneOffset.UTC);
    }

    public void flushAndRequireResult() {
      requireLocalCurrent();
      entities.flush();
      var actual = consultantRepository.findById(account).orElseThrow();
      entities.refresh(actual);
      var expected = expectedConsultant();
      for (var field : Consultant.class.getDeclaredFields()) {
        if (field.getAnnotation(jakarta.persistence.Column.class) == null) continue;
        try {
          field.setAccessible(true);
          if (!Objects.equals(field.get(expected), field.get(actual)))
            throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
                "Consultant grant SQL postcondition changed");
        } catch (IllegalAccessException invalid) {
          throw new de.caritas.cob.userservice.api.port.out
              .ScopedIdentityCommandRequiredException();
        }
      }
    }

    public ConsultantAdminResponseDTO currentResult() {
      requireActive();
      var result = new java.util.concurrent.atomic.AtomicReference<ConsultantAdminResponseDTO>();
      TenantContext.runIn(
          source.getTenantId(),
          () ->
              result.set(
                  ConsultantResponseDTOBuilder.getInstance(
                          consultantRepository.findById(account).orElseThrow())
                      .buildResponseDTO()));
      return Objects.requireNonNull(result.get());
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "ScopedGrant[source=redacted]";
    }
  }

  private void assignKeycloakRoles(String adminId, GrantConsultantIdentityDTO dto) {
    var requestedRoles =
        dto.isGroupchatConsultant()
            ? Set.of(CONSULTANT.getValue(), GROUP_CHAT_CONSULTANT.getValue())
            : Set.of(CONSULTANT.getValue());
    identityRoleUpdater.ensureRoles(adminId, requestedRoles);
  }

  /**
   * Mirrors {@code CreateConsultantSaga}: Matrix account creation is non-fatal — on any failure the
   * matrixUserId is left {@code null} and the grant continues.
   */
  private String createMatrixAccount(de.caritas.cob.userservice.api.model.Admin admin) {
    try {
      var matrixPassword = userHelper.getRandomPassword();
      // ADR-002 §2 / #1200: the Synapse displayname is readable by every member of a shared room
      // via /joined_members, the advice seeker included — so it must never be the real name. An
      // Admin carries no public display name of its own (nor does GrantConsultantIdentityDTO), so
      // the resolver falls back to the username the Matrix ID already exposes. The rule is NOT
      // repeated here: ConsultantDisplayNameResolver stays the only place that decides.
      var matrixDisplayName =
          consultantDisplayNameResolver.resolveMatrixDisplayName(null, admin.getUsername());
      var matrixUserId =
          matrixUserClient.createUserId(admin.getUsername(), matrixPassword, matrixDisplayName);
      if (matrixUserId != null) {
        return matrixUserId;
      }
      log.warn(
          "Chat account provisioning answered without a user_id while granting consultant identity"
              + " to admin {}; the consultant is created without a chat identity and must be"
              + " repaired via POST /useradmin/consultants/{}/chat-identity (#1194)",
          admin.getId(),
          admin.getId());
    } catch (Exception e) {
      log.error(
          "Chat account provisioning failed while granting consultant identity to admin {};"
              + " continuing without a chat identity. The consultant cannot be used for"
              + " counselling until it is repaired via POST"
              + " /useradmin/consultants/{}/chat-identity (#1194)",
          admin.getId(),
          admin.getId(),
          e);
    }
    return null;
  }

  /**
   * Builds the {@link Consultant} entity by mirroring {@code CreateConsultantSaga.buildConsultant}
   * — only the fields that method sets are set here. The consultant shares the admin's Keycloak id
   * and copies username/firstName/lastName/email/tenantId from the {@code Admin}.
   */
  private Consultant buildConsultant(
      de.caritas.cob.userservice.api.model.Admin admin,
      String encodedUsername,
      GrantConsultantIdentityDTO dto,
      String matrixUserId) {

    var now = nowInUtc();
    var consultant =
        Consultant.builder()
            .id(admin.getId())
            .createDate(now)
            .updateDate(now)
            .username(encodedUsername)
            .firstName(admin.getFirstName())
            .lastName(admin.getLastName())
            .email(admin.getEmail())
            .absent(dto.isAbsent())
            .absenceMessage(dto.getAbsenceMessage())
            .teamConsultant(false)
            .matrixUserId(matrixUserId)
            .encourage2fa(true)
            // Same kind of account as the admin create path, so it owes the same second factor.
            // passwordChangeRequired stays false: no new password is chosen here.
            .twoFactorRequired(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .languageFormal(dto.isFormalLanguage())
            .languages(Set.of())
            .tenantId(admin.getTenantId())
            .status(ConsultantStatus.CREATED)
            .walkThroughEnabled(false)
            .languageCode(LanguageCode.de)
            .notificationsEnabled(true)
            .notificationsSettings(serializeToJsonString(allActiveNotifications()))
            .build();

    return consultant;
  }

  private void saveConsultantOrRollback(
      String adminId, GrantConsultantIdentityDTO dto, Consultant consultant) {
    try {
      consultantService.saveConsultant(consultant);
    } catch (Exception e) {
      log.error(
          "Unable to persist consultant identity for admin with id {} in database. Rolling back keycloak roles.",
          adminId,
          e);
      removeGrantedRoles(adminId, dto);
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .name(GRANT_CONSULTANT_IDENTITY)
              .completedTransactionalOperations(
                  newArrayList(TransactionalStep.ASSIGN_CONSULTANT_ROLE_IN_KEYCLOAK))
              .failedStep(TransactionalStep.CREATE_CONSULTANT_IN_MARIADB_FOR_EXISTING_USER)
              .build());
    }
  }

  /**
   * Assigns the requested agencies to the new consultant identity. Assignment failures are surfaced
   * to the caller so the grant does not silently leave a partially usable consultant identity.
   */
  private void assignAgencies(String adminId, GrantConsultantIdentityDTO dto) {
    if (dto.getAgencyIds() == null) {
      return;
    }
    for (Long agencyId : dto.getAgencyIds()) {
      consultantAgencyRelationCreatorService.createNewConsultantAgency(
          adminId, new CreateConsultantAgencyDTO().agencyId(agencyId));
    }
  }

  private void removeGrantedRoles(String adminId, GrantConsultantIdentityDTO dto) {
    identityClient.removeRoleIfPresent(adminId, CONSULTANT.getValue());
    if (dto.isGroupchatConsultant()) {
      identityClient.removeRoleIfPresent(adminId, GROUP_CHAT_CONSULTANT.getValue());
    }
  }

  private NotificationsSettingsDTO allActiveNotifications() {
    NotificationsSettingsDTO notificationsSettingsDTO = new NotificationsSettingsDTO();
    notificationsSettingsDTO.setReassignmentNotificationEnabled(true);
    notificationsSettingsDTO.setInitialEnquiryNotificationEnabled(true);
    notificationsSettingsDTO.setAppointmentNotificationEnabled(true);
    notificationsSettingsDTO.setNewChatMessageNotificationEnabled(true);
    return notificationsSettingsDTO;
  }
}
