package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsultantImportServiceTest {

  @InjectMocks private ConsultantImportService consultantImportService;

  @Mock private IdentityUsernameAvailability identityUsernameAvailability;
  @Mock private de.caritas.cob.userservice.api.facade.rollback.RollbackFacade rollbackFacade;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCreationLocalCompletion
      localCompletion;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityAccountProvisioning
      identityProvisioning;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.ConfiguredConsultantImport
      configuredImport;

  @Mock private ConsultantService consultantService;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private AgencyService agencyService;
  @Mock private UserHelper userHelper;
  @Mock private CreateConsultantSaga createConsultantSaga;
  @Mock private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @TempDir Path tempDir;

  private Path importFile;
  private Path protocolFile;

  @BeforeEach
  void setUp() throws IOException {
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified-test-token")
            .header("alg", "RS256")
            .subject("import-subject")
            .audience(List.of("userservice"))
            .claim("azp", "backend-consultant-import")
            .claim("realm_access", Map.of("roles", List.of("consultant-import")))
            .build();
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationToken(jwt, List.of()));
    importFile = tempDir.resolve("consultant-import.csv");
    protocolFile = tempDir.resolve("protocol");
    ReflectionTestUtils.setField(consultantImportService, "importFilename", importFile.toString());
    ReflectionTestUtils.setField(
        consultantImportService, "protocolFilename", protocolFile.toString());
    ReflectionTestUtils.setField(consultantImportService, "multiTenancyEnabled", false);
    when(configuredImport.capture())
        .thenAnswer(
            ignored ->
                new de.caritas.cob.userservice.api.adapters.keycloak.commands
                        .ConfiguredConsultantImport(
                        importFile.toString(),
                        "backend-consultant-import",
                        "import-subject",
                        "userservice",
                        Boolean.TRUE.equals(
                            ReflectionTestUtils.getField(
                                consultantImportService, "multiTenancyEnabled")))
                    .capture());
  }

  // ---------------------------------------------------------------------------
  // startImport — file read
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_ThrowInternalServerError_When_ImportFileDoesNotExist() {
    ReflectionTestUtils.setField(
        consultantImportService, "importFilename", "/nonexistent/file.csv");

    assertThrows(IllegalStateException.class, () -> consultantImportService.startImport());
  }

  // ---------------------------------------------------------------------------
  // startImport — username validation
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_SkipRecord_When_UsernameIsInvalid() throws IOException {
    writeCsv(",1,ab,First,Last,valid@example.com,nein,,10;roleA\r\n");
    when(userHelper.isUsernameValid("ab")).thenReturn(false);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);

    consultantImportService.startImport();

    verify(agencyService, never()).getPublicImportAgency(any(), any());
  }

  // ---------------------------------------------------------------------------
  // startImport — agency validation
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_BreakOnImportException_When_AgencyRoleSetHasNoDelimiter()
      throws IOException {
    writeCsv(",1,validuser,First,Last,valid@example.com,nein,,10\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);

    consultantImportService.startImport();

    verify(agencyService, never()).getPublicImportAgency(any(), any());
  }

  @Test
  void startImport_Should_BreakOnImportException_When_AgencyIsNull() throws IOException {
    writeCsv(",1,validuser,First,Last,valid@example.com,nein,,10;roleA\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);
    when(agencyService.getPublicImportAgency(eq(10L), any())).thenReturn(null);

    consultantImportService.startImport();

    verify(consultingTypeManager, never()).getConsultingTypeSettings(any());
  }

  @Test
  void startImport_Should_BreakOnImportException_When_RoleSetIsInvalidForConsultingType()
      throws IOException {
    writeCsv(",1,validuser,First,Last,valid@example.com,nein,,10;unknownRole\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);
    when(agencyService.getPublicImportAgency(eq(10L), any()))
        .thenReturn(agencyWithConsultingType(1));
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(typeWithRoles(Map.of("validRole", List.of("consultant"))));

    consultantImportService.startImport();

    verify(createConsultantSaga, never()).createImportedConsultant(any(), any());
  }

  // ---------------------------------------------------------------------------
  // startImport — consultant existence check (consultantId null branch)
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_SkipRecord_When_ConsultantAlreadyExistsByUsername() throws IOException {
    writeCsv(",1,existinguser,First,Last,valid@example.com,nein,,10;roleA\r\n");
    when(userHelper.isUsernameValid("existinguser")).thenReturn(true);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);
    when(agencyService.getPublicImportAgency(eq(10L), any()))
        .thenReturn(agencyWithConsultingType(1));
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(typeWithRoles(Map.of("roleA", List.of("consultant"))));
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.of(new Consultant()));

    consultantImportService.startImport();

    verify(identityUsernameAvailability, never()).isUsernameAvailable(anyString());
  }

  @Test
  void startImport_Should_SkipRecord_When_UsernameAlreadyTakenInKeycloak() throws IOException {
    writeCsv(",1,newuser,First,Last,valid@example.com,nein,,10;roleA\r\n");
    when(userHelper.isUsernameValid("newuser")).thenReturn(true);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);
    when(agencyService.getPublicImportAgency(eq(10L), any()))
        .thenReturn(agencyWithConsultingType(1));
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(typeWithRoles(Map.of("roleA", List.of("consultant"))));
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(createConsultantSaga.createImportedConsultant(any(), any()))
        .thenThrow(new IllegalStateException("atomic username conflict"));

    consultantImportService.startImport();

    verify(createConsultantSaga).createImportedConsultant(any(), any());
    verify(identityUsernameAvailability, never()).isUsernameAvailable(anyString());
  }

  // ---------------------------------------------------------------------------
  // startImport — consultant existence check (consultantId present branch)
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_SkipRecord_When_ConsultantIdProvidedButNotFound() throws IOException {
    writeCsv("existing-id-123,1,someuser,First,Last,valid@example.com,nein,,10;roleA\r\n");
    when(userHelper.isValidEmail(anyString())).thenReturn(true);
    when(agencyService.getPublicImportAgency(eq(10L), any()))
        .thenReturn(agencyWithConsultingType(1));
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(typeWithRoles(Map.of("roleA", List.of("consultant"))));
    when(consultantService.getConsultant("existing-id-123")).thenReturn(Optional.empty());

    consultantImportService.startImport();

    verify(createConsultantSaga, never()).createImportedConsultant(any(), any());
  }

  // ---------------------------------------------------------------------------
  // startImport — formalLanguage resolution
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_CreateConsultant_When_AllValidationsPass() throws IOException {
    writeCsv(",1,brandnewuser,First,Last,valid@example.com,nein,,10;roleA\r\n");
    when(userHelper.isUsernameValid("brandnewuser")).thenReturn(true);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);
    when(agencyService.getPublicImportAgency(eq(10L), any()))
        .thenReturn(agencyWithConsultingType(1));
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(typeWithRoles(Map.of("roleA", List.of("consultant"))));
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(identityUsernameAvailability.isUsernameAvailable("brandnewuser")).thenReturn(true);
    Consultant newConsultant = new Consultant();
    newConsultant.setId("new-id-456");
    when(createConsultantSaga.createImportedConsultant(any(), any())).thenReturn(newConsultant);

    consultantImportService.startImport();

    verify(createConsultantSaga).createImportedConsultant(any(), any());
    verify(consultantAgencyRelationCreatorService)
        .createOwnedCreationRelations(eq("new-id-456"), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // startImport — email validation
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_BreakOnImportException_When_EmailIsInvalid() throws IOException {
    writeCsv(",1,validuser,First,Last,not-an-email,nein,,10;roleA\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(userHelper.isValidEmail("not-an-email")).thenReturn(false);

    consultantImportService.startImport();

    verify(agencyService, never()).getPublicImportAgency(any(), any());
  }

  // ---------------------------------------------------------------------------
  // startImport — multitenancy
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_ReadTenantId_When_MultitenancyEnabled() throws IOException {
    ReflectionTestUtils.setField(consultantImportService, "multiTenancyEnabled", true);
    writeCsv(",1,tenantuser,First,Last,valid@example.com,nein,,10;roleA,5\r\n");
    when(userHelper.isUsernameValid("tenantuser")).thenReturn(true);
    when(userHelper.isValidEmail(anyString())).thenReturn(true);
    when(agencyService.getPublicImportAgency(eq(10L), any()))
        .thenReturn(agencyWithConsultingType(1));
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(typeWithRoles(Map.of("roleA", List.of("consultant"))));
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(identityUsernameAvailability.isUsernameAvailable("tenantuser")).thenReturn(true);
    Consultant newConsultant = new Consultant();
    newConsultant.setId("tenant-cons-id");
    when(createConsultantSaga.createImportedConsultant(any(), any())).thenReturn(newConsultant);

    consultantImportService.startImport();

    verify(createConsultantSaga).createImportedConsultant(any(), any());
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private void writeCsv(String content) throws IOException {
    Files.writeString(importFile, content);
  }

  private AgencyDTO agencyWithConsultingType(int consultingTypeId) {
    AgencyDTO agency = new AgencyDTO();
    agency.setConsultingType(consultingTypeId);
    agency.setId(10L);
    agency.setTeamAgency(false);
    return agency;
  }

  private ExtendedConsultingTypeResponseDTO typeWithRoles(Map<String, List<String>> roleSets) {
    de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant consultantRoles =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant(
            new java.util.LinkedHashMap<>(roleSets));
    RolesDTO roles = new RolesDTO();
    roles.setConsultant(consultantRoles);
    ExtendedConsultingTypeResponseDTO dto = new ExtendedConsultingTypeResponseDTO();
    dto.setRoles(roles);
    dto.setLanguageFormal(true);
    dto.setSlug("test-slug");
    return dto;
  }
}
