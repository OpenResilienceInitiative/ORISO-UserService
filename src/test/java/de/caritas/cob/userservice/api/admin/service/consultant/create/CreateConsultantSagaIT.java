package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.EMAIL_NOT_VALID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(classes = UserServiceApplication.class)
@org.springframework.context.annotation.Import(
    de.caritas.cob.userservice.api.testHelper.VerifiedRequestCallerFixture.class)
@TestPropertySource(properties = "spring.profiles.active=testing,verified-request-caller")
@AutoConfigureTestDatabase(replace = Replace.NONE)
public class CreateConsultantSagaIT extends AccountInactivityPolicyHttpFixture {
  @org.junit.jupiter.api.BeforeEach
  void recoveryPolicyFixture() {
    org.mockito.Mockito.when(
            tenantService.getRestrictedTenantDataFresh(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
  }

  @MockitoBean
  private de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenantService;

  @MockitoBean(name = "keycloakRestTemplate")
  private RestTemplate boundedIdentityHttp;

  @MockitoBean private TaskIdentityGrant taskIdentityGrant;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment identityEnvironment;
  @Autowired private ObjectMapper identityMapper;
  private BoundedIdentityHttpFixtures.Provider nativeAccounts;

  private void givenBoundedAccounts() {
    nativeAccounts =
        BoundedIdentityHttpFixtures.givenProvider(
            boundedIdentityHttp,
            taskIdentityGrant,
            taskIdentities,
            identityEnvironment,
            identityMapper,
            id -> {});
    givenHuman("7ad454de-cf29-4557-b8b3-1bf986524de2", 1L, List.of("user-admin", "tenant-admin"));
  }

  private void givenHuman(String id, Long tenant, List<String> roles) {
    var token =
        Jwt.withTokenValue("verified-creator-session")
            .header("alg", "RS256")
            .subject(id)
            .claim("azp", "admin")
            .claim("preferred_username", "apau1")
            .claim("tenantId", tenant == null ? null : tenant.toString())
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    var authentication =
        new JwtAuthenticationToken(
            token,
            List.of(
                    "AUTHORIZATION_USER_ADMIN",
                    "AUTHORIZATION_TENANT_ADMIN",
                    "AUTHORIZATION_CONSULTANT_CREATE")
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(authentication);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @org.junit.jupiter.api.AfterEach
  void clearBoundedCaller() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  private static final String VALID_USERNAME = "validUsername";
  private static final String VALID_EMAILADDRESS = "valid@emailaddress.de";
  private static final long TENANT_ID = 1L;

  @Autowired private CreateConsultantSaga createConsultantSaga;

  @Autowired
  private de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityCreationFinalizationRetry
      identityFinalizationRetry;

  @Autowired
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCreationLocalCompletion
      localCompletion;

  @MockitoBean private MatrixSynapseService matrixSynapseService;

  @MockitoBean private TenantAdminService tenantAdminService;

  @MockitoBean private AppointmentService appointmentService;

  @Autowired private de.caritas.cob.userservice.api.service.ConsultantImportService actualImporter;

  @Autowired
  private de.caritas.cob.userservice.api.service.ConsultingTypeService actualConsultingTypeService;

  @Autowired
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.ConfiguredConsultantImport
      configuredImport;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.ConsultantRepository importedConsultants;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.IdentityCreationAttemptRepository
      creationAttempts;

  @MockitoBean
  private de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory
      importAgencyFactory;

  @MockitoBean
  private de.caritas.cob.userservice.api.config.apiclient.ConsultingTypeServiceApiControllerFactory
      importTypeFactory;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void laterInvalidImportRowPreservesEarlierCompletedRowAndReportsFailure(boolean failAfterCreation)
      throws Exception {
    var directory = java.nio.file.Files.createTempDirectory("atomic-import-failure");
    var file = directory.resolve("configured.csv");
    String username = "import" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String failedUsername =
        failAfterCreation
            ? "failed" + java.util.UUID.randomUUID().toString().substring(0, 8)
            : "ab";
    java.nio.file.Files.writeString(
        file,
        ",424242,"
            + username
            + ",Given,Family,"
            + username
            + "@example.org,nein,,8;standard,1\n,424243,"
            + failedUsername
            + ",Given,Family,"
            + failedUsername
            + "@example.org,nein,,8;standard,1\n");
    Object priorFilename = ReflectionTestUtils.getField(configuredImport, "filename");
    Object priorMultitenancy = ReflectionTestUtils.getField(configuredImport, "multitenancy");
    Object priorImportMultitenancy =
        ReflectionTestUtils.getField(actualImporter, "multiTenancyEnabled");
    Object priorProtocol = ReflectionTestUtils.getField(actualImporter, "protocolFilename");
    Object priorTypeManager = ReflectionTestUtils.getField(actualImporter, "consultingTypeManager");
    try {
      // This class' legacy shared manager fabricates random policies; use the real production
      // mapper against this test's exact external CTS API response, with no authorization mock.
      ReflectionTestUtils.setField(
          actualImporter,
          "consultingTypeManager",
          new de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager(
              actualConsultingTypeService));
      ReflectionTestUtils.setField(configuredImport, "filename", file.toString());
      ReflectionTestUtils.setField(configuredImport, "multitenancy", true);
      ReflectionTestUtils.setField(actualImporter, "multiTenancyEnabled", true);
      ReflectionTestUtils.setField(
          actualImporter, "protocolFilename", directory.resolve("protocol").toString());
      var importer =
          Jwt.withTokenValue("verified-configured-importer")
              .header("alg", "RS256")
              .subject(
                  identityEnvironment.getRequiredProperty(
                      "identity.consultant-import.service-subject"))
              .claim(
                  "azp",
                  identityEnvironment.getRequiredProperty("identity.consultant-import.client-id"))
              .claim("realm_access", Map.of("roles", List.of("consultant-import")))
              .audience(List.of("userservice"))
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(300))
              .build();
      SecurityContextHolder.getContext()
          .setAuthentication(new JwtAuthenticationToken(importer, List.of()));
      de.caritas.cob.userservice.api.tenant.TenantContext.setCurrentTenant(1L);
      var agencyApi =
          org.mockito.Mockito.mock(
              de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi.class);
      when(agencyApi.getApiClient())
          .thenReturn(new de.caritas.cob.userservice.agencyserivce.generated.ApiClient());
      var agency =
          new de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO();
      agency.setId(8L);
      agency.setTenantId(1L);
      agency.setConsultingType(1);
      agency.setTeamAgency(false);
      agency.setTopicIds(List.of());
      when(agencyApi.getAgenciesByIds(List.of(8L))).thenReturn(List.of(agency));
      when(importAgencyFactory.createControllerApi()).thenReturn(agencyApi);
      var typeApi =
          org.mockito.Mockito.mock(
              de.caritas.cob.userservice.consultingtypeservice.generated.web
                  .ConsultingTypeControllerApi.class);
      when(typeApi.getApiClient())
          .thenReturn(new de.caritas.cob.userservice.consultingtypeservice.generated.ApiClient());
      var policy =
          new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
              .ExtendedConsultingTypeResponseDTO();
      var roles =
          new de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO();
      roles.setConsultant(
          new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant(
              new java.util.LinkedHashMap<>(Map.of("standard", List.of("consultant")))));
      policy.setId(1);
      policy.setRoles(roles);
      policy.setLanguageFormal(true);
      policy.setSlug("fixture");
      policy.setConsultantBoundedToConsultingType(false);
      when(typeApi.getExtendedConsultingTypeById(1)).thenReturn(policy);
      when(importTypeFactory.createControllerApi()).thenReturn(typeApi);
      for (var rowUsername : List.of(username, failedUsername)) {
        org.assertj.core.api.Assertions.assertThat(
                importJdbc.queryForObject(
                    "SELECT COUNT(*) FROM consultant WHERE username=? OR email=?",
                    Integer.class,
                    new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                        .encodeUsername(rowUsername),
                    rowUsername + "@example.org"))
            .isZero();
        org.assertj.core.api.Assertions.assertThat(nativeAccounts.commands())
            .noneMatch(
                command ->
                    command.operation().equals("account.create")
                        && rowUsername.equals(command.body().get("username")));
      }
      org.assertj.core.api.Assertions.assertThat(username).isNotEqualTo(failedUsername);
      if (failAfterCreation) {
        RejectSecondImportedRelation.username =
            new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                .encodeUsername(failedUsername);
        importJdbc.execute(
            "CREATE TRIGGER reject_second_imported_relation BEFORE INSERT ON consultant_agency FOR EACH ROW CALL '"
                + RejectSecondImportedRelation.class.getName()
                + "'");
      }
      var failure = assertThrows(RuntimeException.class, actualImporter::startImport);
      if (failAfterCreation)
        org.assertj.core.api.Assertions.assertThat(failure)
            .hasStackTraceContaining("synthetic second-row relation refusal");
      else
        org.assertj.core.api.Assertions.assertThat(failure)
            .isInstanceOf(BadRequestException.class)
            .cause()
            .hasMessage("Configured consultant import username length is invalid");
      var created =
          nativeAccounts.commands().stream()
              .filter(
                  command ->
                      command.operation().equals("account.create")
                          && username.equals(command.body().get("username")))
              .findFirst()
              .orElseThrow();
      var attempt = creationAttempts.findById(created.target()).orElseThrow();
      org.assertj.core.api.Assertions.assertThat(
              importedConsultants.findById(attempt.getAccountId()))
          .isPresent();
      org.assertj.core.api.Assertions.assertThat(attempt.getStatus()).isEqualTo("COMMITTED");
      org.assertj.core.api.Assertions.assertThat(nativeAccounts.commands())
          .anyMatch(
              command ->
                  command.operation().equals("account.commit")
                      && command.target().equals(created.target()));
      org.assertj.core.api.Assertions.assertThat(
              nativeAccounts.projections().get(attempt.getAccountId()).enabled())
          .isTrue();
      org.assertj.core.api.Assertions.assertThat(nativeAccounts.commands())
          .noneMatch(
              command ->
                  command.operation().equals("account.compensate")
                      && command.target().equals(created.target()));
      if (failAfterCreation) {
        var failedCreate =
            nativeAccounts.commands().stream()
                .filter(
                    command ->
                        command.operation().equals("account.create")
                            && failedUsername.equals(command.body().get("username")))
                .findFirst()
                .orElseThrow();
        var failedAttempt = creationAttempts.findById(failedCreate.target()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(failedAttempt.getStatus())
            .isEqualTo("COMPENSATED");
        org.assertj.core.api.Assertions.assertThat(
                importedConsultants.findById(failedAttempt.getAccountId()))
            .isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                importJdbc.queryForObject(
                    "SELECT COUNT(*) FROM consultant_agency WHERE consultant_id=?",
                    Integer.class,
                    failedAttempt.getAccountId()))
            .isZero();
        org.assertj.core.api.Assertions.assertThat(nativeAccounts.projections())
            .doesNotContainKey(failedAttempt.getAccountId());
        org.assertj.core.api.Assertions.assertThat(nativeAccounts.commands())
            .anyMatch(
                command ->
                    command.operation().equals("account.compensate")
                        && command.target().equals(failedCreate.target()));
      }
    } finally {
      importJdbc.execute("DROP TRIGGER IF EXISTS reject_second_imported_relation");
      RejectSecondImportedRelation.username = null;
      ReflectionTestUtils.setField(configuredImport, "filename", priorFilename);
      ReflectionTestUtils.setField(configuredImport, "multitenancy", priorMultitenancy);
      ReflectionTestUtils.setField(actualImporter, "multiTenancyEnabled", priorImportMultitenancy);
      ReflectionTestUtils.setField(actualImporter, "protocolFilename", priorProtocol);
      ReflectionTestUtils.setField(actualImporter, "consultingTypeManager", priorTypeManager);
      de.caritas.cob.userservice.api.tenant.TenantContext.clear();
      try (var paths = java.nio.file.Files.walk(directory)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          java.nio.file.Files.delete(path);
      }
    }
  }

  @Autowired private org.springframework.jdbc.core.JdbcTemplate importJdbc;

  public static class RejectSecondImportedRelation implements org.h2.api.Trigger {
    static volatile String username;
    private int consultantColumn;

    @Override
    public void init(
        java.sql.Connection connection,
        String schema,
        String trigger,
        String table,
        boolean before,
        int type)
        throws java.sql.SQLException {
      try (var columns =
          connection
              .createStatement()
              .executeQuery("SELECT * FROM " + schema + "." + table + " WHERE 1=0")) {
        var metadata = columns.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++)
          if (metadata.getColumnName(i).equalsIgnoreCase("consultant_id")) consultantColumn = i - 1;
      }
    }

    @Override
    public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
        throws java.sql.SQLException {
      try (var query =
          connection.prepareStatement("SELECT username FROM consultant WHERE consultant_id=?")) {
        query.setObject(1, newRow[consultantColumn]);
        try (var row = query.executeQuery()) {
          if (row.next() && java.util.Objects.equals(username, row.getString(1)))
            throw new java.sql.SQLException("synthetic second-row relation refusal", "23514");
        }
      }
    }
  }

  private final EasyRandom easyRandom = new EasyRandom();

  @BeforeEach
  public void setup() {
    givenBoundedAccounts();
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", false);
  }

  @Test
  public void createNewConsultant_Should_returnExpectedCreatedConsultant_When_inputDataIsCorrect() {
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setPublicSlug(null);
    createConsultantDTO.setIsGroupchatConsultant(false);
    createConsultantDTO.setAgencyIds(List.of());

    var consultantAdminResponseDTO =
        this.createConsultantSaga.createNewConsultant(createConsultantDTO);

    ConsultantDTO consultant = consultantAdminResponseDTO.getEmbedded();
    org.assertj.core.api.Assertions.assertThat(
            nativeAccounts.projections().get(consultant.getId()).roles())
        .containsExactly("consultant");

    assertThat(consultant, notNullValue());
    assertThat(consultant.getId(), notNullValue());
    assertDefaultInactivityPolicy(consultant.getId());
    assertThat(consultant.getAbsenceMessage(), notNullValue());
    assertThat(consultant.getCreateDate(), notNullValue());
    assertThat(consultant.getUpdateDate(), notNullValue());
    assertThat(consultant.getUsername(), notNullValue());
    assertThat(consultant.getFirstname(), notNullValue());
    assertThat(consultant.getLastname(), notNullValue());
    assertThat(consultant.getEmail(), notNullValue());
  }

  @Test
  public void createNewConsultant_Should_callRollback_When_AppointmentServiceThrowsException() {
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", true);
    doThrow(BadRequestException.class)
        .when(appointmentService)
        .createOwnedConsultant(any(), any(), any());
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setPublicSlug(null);
    createConsultantDTO.setIsGroupchatConsultant(false);
    createConsultantDTO.setAgencyIds(List.of());

    try {
      this.createConsultantSaga.createNewConsultant(createConsultantDTO);
      fail("Exception should be thrown");
    } catch (DistributedTransactionException ex) {
      assertThat(
          ex.getCustomHttpHeaders().get("X-Reason").get(0),
          is(
              "DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_CREATE_ACCOUNT_IN_CALCOM_OR_APPOINTMENTSERVICE"));
      // The failed local transaction records a durable compensation intent. The same recovery
      // entry point used by the scheduler completes it after the transaction has rolled back.
      identityFinalizationRetry.retry();
      org.assertj.core.api.Assertions.assertThat(nativeAccounts.commands())
          .extracting(BoundedIdentityHttpFixtures.Command::operation)
          .contains("account.create", "account.compensate")
          .doesNotContain("account.commit");
      org.assertj.core.api.Assertions.assertThat(nativeAccounts.projections()).isEmpty();
    }
  }

  @Test
  public void createNewConsultant_Should_notPersistAccount_When_nativePasswordValidationFails() {
    givenNativeCreationFailure(org.springframework.http.HttpStatus.BAD_REQUEST);
    assertThrows(
        org.springframework.web.client.HttpClientErrorException.BadRequest.class,
        () -> createConsultantSaga.createNewConsultant(validInput()));
    org.assertj.core.api.Assertions.assertThat(nativeAccounts.projections()).isEmpty();
  }

  @Test
  public void createNewConsultant_Should_notPersistAccount_When_nativeRoleAuthorizationFails() {
    givenNativeCreationFailure(org.springframework.http.HttpStatus.FORBIDDEN);
    assertThrows(
        org.springframework.web.client.HttpClientErrorException.Forbidden.class,
        () -> createConsultantSaga.createNewConsultant(validInput()));
    org.assertj.core.api.Assertions.assertThat(nativeAccounts.projections()).isEmpty();
  }

  private void givenNativeCreationFailure(org.springframework.http.HttpStatus status) {
    org.mockito.Mockito.doThrow(
            org.springframework.web.client.HttpClientErrorException.create(
                status,
                "Native atomic creation rejected",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8))
        .when(boundedIdentityHttp)
        .exchange(
            org.mockito.ArgumentMatchers.contains("/account-creations/"),
            eq(org.springframework.http.HttpMethod.PUT),
            any(org.springframework.http.HttpEntity.class),
            eq(KeycloakTaskCommands.CreationResult.class));
  }

  private CreateConsultantDTO validInput() {
    var input = easyRandom.nextObject(CreateConsultantDTO.class);
    input.setTenantId(TENANT_ID);
    input.setUsername(VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    input.setEmail(VALID_EMAILADDRESS);
    input.setPublicSlug(null);
    input.setIsGroupchatConsultant(false);
    input.setAgencyIds(List.of());
    return input;
  }

  @Test
  public void
      createNewConsultant_Should_addConsultantAndGroupChatConsultantRole_When_isGroupChatConsultantFlagIsEnabled() {
    // given
    var tenant = new TenantDTO().settings(new Settings().featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById((long) TENANT_ID)).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setPublicSlug(null);
    createConsultantDTO.setIsGroupchatConsultant(true);
    createConsultantDTO.setAgencyIds(List.of());

    // when
    var consultantAdminResponseDTO = createConsultantSaga.createNewConsultant(createConsultantDTO);

    // then
    org.assertj.core.api.Assertions.assertThat(
            nativeAccounts
                .projections()
                .get(consultantAdminResponseDTO.getEmbedded().getId())
                .roles())
        .containsExactlyInAnyOrder("consultant", "group-chat-consultant");

    assertThat(consultantAdminResponseDTO.getEmbedded(), notNullValue());
    assertThat(consultantAdminResponseDTO.getEmbedded().getId(), notNullValue());
  }

  @Test
  public void
      createNewConsultant_Should_returnExpectedCreatedConsultant_When_inputDataIsCorrectImportRecord()
          throws Exception {
    var file = java.nio.file.Files.createTempFile("oriso-authorized-consultant-import-", ".csv");
    try {
      java.nio.file.Files.writeString(
          file, ",,validUsername,Given,Family,valid@emailaddress.de,,,1;consultant,1\n");
      var importer =
          Jwt.withTokenValue("verified-configured-importer")
              .header("alg", "RS256")
              .subject(
                  identityEnvironment.getRequiredProperty(
                      "identity.consultant-import.service-subject"))
              .claim(
                  "azp",
                  identityEnvironment.getRequiredProperty("identity.consultant-import.client-id"))
              .claim("realm_access", Map.of("roles", List.of("consultant-import")))
              .audience(List.of("userservice"))
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(300))
              .build();
      SecurityContextHolder.getContext()
          .setAuthentication(
              new JwtAuthenticationToken(
                  importer,
                  List.of(new SimpleGrantedAuthority("AUTHORIZATION_CONSULTANT_IMPORT"))));
      var configured =
          new de.caritas.cob.userservice.api.adapters.keycloak.commands.ConfiguredConsultantImport(
              file.toString(),
              identityEnvironment.getRequiredProperty("identity.consultant-import.client-id"),
              identityEnvironment.getRequiredProperty("identity.consultant-import.service-subject"),
              "userservice",
              true);
      var captured = configured.capture();
      ImportRecord importRecord = this.easyRandom.nextObject(ImportRecord.class);
      importRecord.setConsultantId(null);
      importRecord.setTenantId(TENANT_ID);
      importRecord.setUsername(VALID_USERNAME);
      importRecord.setFirstName("Given");
      importRecord.setLastName("Family");
      importRecord.setEmail(VALID_EMAILADDRESS);
      importRecord.setAgenciesAndRoleSets("1;consultant");
      var origin =
          captured.authorize(
              captured.records().getFirst(),
              importRecord,
              List.of("consultant"),
              List.of(
                  new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                      .id(1L)
                      .tenantId(TENANT_ID)));
      Consultant consultant = createConsultantSaga.createImportedConsultant(importRecord, origin);
      localCompletion.importedConsultant(consultant);
      assertThat(consultant, notNullValue());
      assertThat(consultant.getId(), notNullValue());
      assertThat(consultant.getMatrixUserId(), is((String) null));
      assertDefaultInactivityPolicy(consultant.getId());
      assertThat(consultant.getFirstName(), is("Given"));
      assertThat(consultant.getLastName(), is("Family"));
      assertThat(consultant.getEmail(), is(VALID_EMAILADDRESS));
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  @Test
  public void createNewConsultant_Should_rejectMissingNativeCreationReceipt() {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String endpoint = invocation.getArgument(0);
              var attempt =
                  java.util.UUID.fromString(endpoint.substring(endpoint.lastIndexOf('/') + 1));
              return org.springframework.http.ResponseEntity.ok(
                  new KeycloakTaskCommands.CreationResult(attempt, null, "receipt", "OPEN"));
            })
        .when(boundedIdentityHttp)
        .exchange(
            org.mockito.ArgumentMatchers.contains("/account-creations/"),
            eq(org.springframework.http.HttpMethod.PUT),
            any(org.springframework.http.HttpEntity.class),
            eq(KeycloakTaskCommands.CreationResult.class));
    var input = validInput();
    var failure =
        assertThrows(
            IllegalStateException.class, () -> createConsultantSaga.createNewConsultant(input));
    org.assertj.core.api.Assertions.assertThat(failure)
        .hasMessage("Identity provider returned no creation receipt");
    org.assertj.core.api.Assertions.assertThat(nativeAccounts.commands())
        .noneMatch(command -> command.operation().equals("account.commit"));
    org.assertj.core.api.Assertions.assertThat(
            importedConsultants.findByUsernameAndDeleteDateIsNull(input.getUsername()))
        .isEmpty();
  }

  @Test
  public void createNewConsultant_Should_throwExpectedException_When_emailIsInvalid() {
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setEmail("invalid");

    try {
      this.createConsultantSaga.createNewConsultant(createConsultantDTO);
      fail("Exception should be thrown");
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(e.getCustomHttpHeaders().get("X-Reason").get(0), is(EMAIL_NOT_VALID.name()));
    }
  }
}
