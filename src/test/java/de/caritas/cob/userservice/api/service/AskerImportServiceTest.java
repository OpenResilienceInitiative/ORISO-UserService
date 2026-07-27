package de.caritas.cob.userservice.api.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.DataDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.LoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatCreateGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatPostWelcomeMessageException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveSystemMessagesException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.message.MessageServiceProvider;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AskerImportServiceTest {

  @InjectMocks private AskerImportService askerImportService;

  @Mock private IdentityClient identityClient;
  @Mock private IdentityUsernameAvailability identityUsernameAvailability;
  @Mock private UserService userService;
  @Mock private SessionService sessionService;
  @Mock private RocketChatService rocketChatService;
  @Mock private SessionDataService sessionDataService;
  @Mock private ConsultantService consultantService;
  @Mock private ConsultantAgencyService consultantAgencyService;
  @Mock private MessageServiceProvider messageServiceProvider;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private AgencyService agencyService;
  @Mock private UserHelper userHelper;
  @Mock private UserAgencyService userAgencyService;
  @Mock private RocketChatCredentialsProvider rocketChatCredentialsProvider;

  private Path askerFile;
  private Path askerWithoutSessionFile;
  private Path protocolFile;

  @BeforeEach
  void setUp() throws IOException {
    askerFile = Files.createTempFile("asker-import", ".csv");
    askerWithoutSessionFile = Files.createTempFile("asker-no-session", ".csv");
    protocolFile = Files.createTempFile("asker-protocol", "");

    ReflectionTestUtils.setField(askerImportService, "importFilenameAsker", askerFile.toString());
    ReflectionTestUtils.setField(
        askerImportService,
        "importFilenameAskerWithoutSession",
        askerWithoutSessionFile.toString());
    ReflectionTestUtils.setField(askerImportService, "protocolFilename", protocolFile.toString());
    ReflectionTestUtils.setField(askerImportService, "ROCKET_CHAT_SYSTEM_USER_USERNAME", "sysuser");
    ReflectionTestUtils.setField(askerImportService, "ROCKET_CHAT_SYSTEM_USER_PASSWORD", "syspass");
  }

  @AfterEach
  void tearDown() {
    // Best-effort cleanup — Windows may hold the file open if production code's FileReader
    // is not closed in an early-exit path; silent failure is acceptable here.
    try {
      Files.deleteIfExists(askerFile);
    } catch (IOException ignored) {
    }
    try {
      Files.deleteIfExists(askerWithoutSessionFile);
    } catch (IOException ignored) {
    }
    try {
      Files.deleteIfExists(protocolFile);
    } catch (IOException ignored) {
    }
  }

  // ---------------------------------------------------------------------------
  // startImportForAskersWithoutSession — file read error
  // ---------------------------------------------------------------------------

  @Test
  void startImportForAskersWithoutSession_Should_ReturnEarly_When_FileDoesNotExist() {
    ReflectionTestUtils.setField(
        askerImportService, "importFilenameAskerWithoutSession", "/no/such/file.csv");

    askerImportService.startImportForAskersWithoutSession();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // ---------------------------------------------------------------------------
  // startImportForAskersWithoutSession — username validation
  // ---------------------------------------------------------------------------

  @Test
  void startImportForAskersWithoutSession_Should_SkipRecord_When_UsernameIsInvalid()
      throws IOException {
    writeAskerWithoutSessionCsv("1,ab,,42,password123\r\n");
    when(userHelper.isUsernameValid("ab")).thenReturn(false);

    askerImportService.startImportForAskersWithoutSession();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // ---------------------------------------------------------------------------
  // startImportForAskersWithoutSession — agency lookup
  // ---------------------------------------------------------------------------

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_AgencyIsNull()
      throws IOException {
    writeAskerWithoutSessionCsv("1,validuser,,42,password123\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(null);

    askerImportService.startImportForAskersWithoutSession();

    verify(identityUsernameAvailability, never()).isUsernameAvailable(anyString());
  }

  // ---------------------------------------------------------------------------
  // startImportForAskersWithoutSession — username availability
  // ---------------------------------------------------------------------------

  @Test
  void startImportForAskersWithoutSession_Should_SkipRecord_When_UsernameAlreadyTaken()
      throws IOException {
    writeAskerWithoutSessionCsv("1,takenuser,,42,password123\r\n");
    when(userHelper.isUsernameValid("takenuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("takenuser")).thenReturn(false);

    askerImportService.startImportForAskersWithoutSession();

    verify(identityClient, never()).createKeycloakUser(any(), anyString(), anyString());
  }

  // ---------------------------------------------------------------------------
  // startImportForAskersWithoutSession — happy path (email present)
  // ---------------------------------------------------------------------------

  @Test
  void startImportForAskersWithoutSession_Should_CreateUser_When_AllChecksPass() throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);

    String keycloakResponse = "kc-user-id";
    when(identityClient.createKeycloakUser(any(UserDTO.class), anyString(), anyString()))
        .thenReturn(keycloakResponse);

    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));

    User dbUser = new User();
    dbUser.setUserId("db-user-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);

    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(userService.saveUser(any(User.class))).thenReturn(dbUser);

    askerImportService.startImportForAskersWithoutSession();

    verify(identityClient).createKeycloakUser(any(), anyString(), anyString());
    verify(userService).createUser(anyString(), any(), anyString(), anyString(), anyBoolean());
    verify(userAgencyService).saveUserAgency(any());
  }

  // ---------------------------------------------------------------------------
  // startImportForAskersWithoutSession — dummy email when email is blank
  // ---------------------------------------------------------------------------

  @Test
  void startImportForAskersWithoutSession_Should_SetDummyEmail_When_EmailIsBlank()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);

    String keycloakResponse = "kc-user-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString()))
        .thenReturn(keycloakResponse);
    when(userHelper.getDummyEmail("kc-user-id")).thenReturn("dummy@example.com");
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User dbUser = new User();
    dbUser.setUserId("db-user-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(userService.saveUser(any(User.class))).thenReturn(dbUser);

    askerImportService.startImportForAskersWithoutSession();

    verify(userHelper).getDummyEmail("kc-user-id");
    verify(identityClient).updateDummyEmail(eq("kc-user-id"), any(UserDTO.class));
  }

  // ---------------------------------------------------------------------------
  // startImportForAskersWithoutSession — RC login token null
  // ---------------------------------------------------------------------------

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_RcTokenIsNull()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String keycloakResponse = "kc-user-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString()))
        .thenReturn(keycloakResponse);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User dbUser = new User();
    dbUser.setUserId("db-user-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse(null, null));

    askerImportService.startImportForAskersWithoutSession();

    verify(userAgencyService, never()).saveUserAgency(any());
  }

  // ---------------------------------------------------------------------------
  // startImport — file read error
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_ReturnEarly_When_FileDoesNotExist() {
    ReflectionTestUtils.setField(askerImportService, "importFilenameAsker", "/no/such/file.csv");

    askerImportService.startImport();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // ---------------------------------------------------------------------------
  // startImport — RC system user login fails
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_ReturnEarly_When_RcSystemLoginFails() throws Exception {
    writeAskerCsv("1,someuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse(null, null));

    askerImportService.startImport();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // ---------------------------------------------------------------------------
  // startImport — username validation
  // ---------------------------------------------------------------------------

  @Test
  void startImport_Should_SkipRecord_When_UsernameIsInvalid() throws Exception {
    writeAskerCsv("1,x,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("x")).thenReturn(false);

    askerImportService.startImport();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-03
  // ---------------------------------------------------------------------------

  // --- startImportForAskersWithoutSession — invalid email ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_EmailIsInvalid()
      throws IOException {
    writeAskerWithoutSessionCsv("1,validuser,not-an-email,42,password123\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);

    askerImportService.startImportForAskersWithoutSession();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // --- startImportForAskersWithoutSession — dbUser.getUserId() null ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_DbUserIdIsNull()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User noIdUser = new User();
    // userId stays null
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(noIdUser);

    askerImportService.startImportForAskersWithoutSession();

    verify(rocketChatService, never()).loginUserFirstTime(anyString(), anyString());
  }

  // --- startImportForAskersWithoutSession — updatedUser.getUserId() null ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_UpdatedUserIdIsNull()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User dbUser = new User();
    dbUser.setUserId("db-user-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    User noIdUpdated = new User();
    // userId stays null
    when(userService.saveUser(any(User.class))).thenReturn(noIdUpdated);

    askerImportService.startImportForAskersWithoutSession();

    verify(userAgencyService, never()).saveUserAgency(any());
  }

  // --- startImportForAskersWithoutSession — RocketChatLoginException ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnRocketChatLoginException_When_LoginFails()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User dbUser = new User();
    dbUser.setUserId("db-user-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenThrow(new RocketChatLoginException("rc login failed"));

    askerImportService.startImportForAskersWithoutSession();

    verify(userAgencyService, never()).saveUserAgency(any());
  }

  // --- startImportForAskersWithoutSession — CustomValidationHttpStatusException ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnCustomValidation_When_KeycloakRejectsUser()
      throws IOException {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    when(identityClient.createKeycloakUser(any(), anyString(), anyString()))
        .thenThrow(
            new CustomValidationHttpStatusException(
                de.caritas.cob.userservice.api.exception.httpresponses.customheader
                    .HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE,
                org.springframework.http.HttpStatus.CONFLICT));

    askerImportService.startImportForAskersWithoutSession();

    verify(userService, never())
        .createUser(anyString(), any(), anyString(), anyString(), anyBoolean());
  }

  // --- startImportForAskersWithoutSession — empty password → getRandomPassword ---

  @Test
  void startImportForAskersWithoutSession_Should_CallGetRandomPassword_When_PasswordIsBlank()
      throws IOException {
    writeAskerWithoutSessionCsv("1,validuser,valid@example.com,42,\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(false);
    when(userHelper.getRandomPassword()).thenReturn("generated-pw");

    askerImportService.startImportForAskersWithoutSession();

    verify(userHelper).getRandomPassword();
  }

  // --- startImport — invalid email ---

  @Test
  void startImport_Should_BreakOnImportException_When_EmailIsInvalid() throws Exception {
    writeAskerCsv("1,validuser,not-an-email,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);

    askerImportService.startImport();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // --- startImport — agency null ---

  @Test
  void startImport_Should_BreakOnImportException_When_AgencyIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(null);

    askerImportService.startImport();

    verify(consultantService, never()).getConsultant(anyString());
  }

  // --- startImport — consultant not found ---

  @Test
  void startImport_Should_SkipRecord_When_ConsultantDoesNotExist() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.empty());

    askerImportService.startImport();

    verify(identityUsernameAvailability, never()).isUsernameAvailable(anyString());
  }

  // --- startImport — consultant not in agency ---

  @Test
  void startImport_Should_SkipRecord_When_ConsultantNotInAgency() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = new Consultant();
    consultant.setId("cons-id");
    consultant.setConsultantAgencies(new HashSet<>());
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));

    askerImportService.startImport();

    verify(identityUsernameAvailability, never()).isUsernameAvailable(anyString());
  }

  // --- startImport — username already taken ---

  @Test
  void startImport_Should_SkipRecord_When_UsernameAlreadyTakenInKeycloak() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(false);

    askerImportService.startImport();

    verify(identityClient, never()).createKeycloakUser(any(), anyString(), anyString());
  }

  // --- startImport — dbUser.getUserId() null ---

  @Test
  void startImport_Should_BreakOnImportException_When_DbUserIdIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User noIdUser = new User();
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(noIdUser);

    askerImportService.startImport();

    verify(sessionService, never()).initializeSession(any(), any(), anyBoolean());
  }

  // --- startImport — session.getId() null ---

  @Test
  void startImport_Should_BreakOnImportException_When_SessionIdIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session noIdSession = new Session();
    // id stays null
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(noIdSession);

    askerImportService.startImport();

    verify(rocketChatService, never()).loginUserFirstTime(anyString(), anyString());
  }

  // --- startImport — RocketChatCreateGroupException ---

  @Test
  void startImport_Should_BreakOnRcCreateGroupException_When_GroupCreationFails() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenThrow(new RocketChatCreateGroupException(new Exception("group fail")));

    askerImportService.startImport();

    verify(userService, never()).saveUser(any(User.class));
  }

  // --- startImport — non-team agency happy path ---

  @Test
  void startImport_Should_CompleteImport_When_NonTeamAgencyAndAllStepsSucceed() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User savedUser = new User();
    savedUser.setUserId("db-id");
    when(userService.saveUser(any(User.class))).thenReturn(savedUser);
    Session savedSession = new Session();
    savedSession.setId(1L);
    when(sessionService.saveSession(any(Session.class))).thenReturn(savedSession);
    when(consultantAgencyService.findConsultantsByAgencyId(any()))
        .thenReturn(Collections.emptyList());

    askerImportService.startImport();

    verify(rocketChatService).addUserToGroup(eq("rc-cons-id"), eq("rc-group-id"));
    verify(rocketChatService).addUserToGroup(eq("sys-user-id"), eq("rc-group-id"));
    verify(sessionDataService).saveSessionData(any(Session.class), any());
  }

  // --- startImport — team agency happy path ---

  @Test
  void startImport_Should_AddAllConsultantsToGroup_When_TeamAgency() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, true));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User savedUser = new User();
    savedUser.setUserId("db-id");
    when(userService.saveUser(any(User.class))).thenReturn(savedUser);
    Session savedSession = new Session();
    savedSession.setId(1L);
    when(sessionService.saveSession(any(Session.class))).thenReturn(savedSession);
    Consultant teamMember = new Consultant();
    teamMember.setId("other-cons-id");
    teamMember.setRocketChatId("rc-other-id");
    ConsultantAgency agency = new ConsultantAgency();
    agency.setConsultant(teamMember);
    when(consultantAgencyService.findConsultantsByAgencyId(any())).thenReturn(List.of(agency));

    askerImportService.startImport();

    verify(rocketChatService).addUserToGroup(eq("rc-other-id"), eq("rc-group-id"));
    verify(rocketChatService).addUserToGroup(eq("sys-user-id"), eq("rc-group-id"));
  }

  // --- startImport — RocketChatRemoveSystemMessagesException ---

  @Test
  void startImport_Should_BreakOnImportException_When_RemoveSystemMessagesFails() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User savedUser = new User();
    savedUser.setUserId("db-id");
    when(userService.saveUser(any(User.class))).thenReturn(savedUser);
    Session savedSession = new Session();
    savedSession.setId(1L);
    when(sessionService.saveSession(any(Session.class))).thenReturn(savedSession);
    when(consultantAgencyService.findConsultantsByAgencyId(any()))
        .thenReturn(Collections.emptyList());
    doThrow(new RocketChatRemoveSystemMessagesException("fail"))
        .when(rocketChatService)
        .removeSystemMessages(anyString(), any(), any());

    askerImportService.startImport();

    verify(sessionDataService, never()).saveSessionData(any(Session.class), any());
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private void writeAskerWithoutSessionCsv(String content) throws IOException {
    Files.writeString(askerWithoutSessionFile, content);
  }

  private void writeAskerCsv(String content) throws IOException {
    Files.writeString(askerFile, content);
  }

  private AgencyDTO agencyDTO(Long id, int consultingType, boolean teamAgency) {
    AgencyDTO dto = new AgencyDTO();
    dto.setId(id);
    dto.setConsultingType(consultingType);
    dto.setTeamAgency(teamAgency);
    return dto;
  }

  private ExtendedConsultingTypeResponseDTO extendedConsultingType(boolean formalLanguage) {
    ExtendedConsultingTypeResponseDTO dto = new ExtendedConsultingTypeResponseDTO();
    dto.setLanguageFormal(formalLanguage);
    return dto;
  }

  // ---------------------------------------------------------------------------
  // Hard paths — 2026-07-03
  // ---------------------------------------------------------------------------

  // --- startImportForAskersWithoutSession — InternalServerErrorException catch ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnInternalServerError_When_ServiceThrowsIt()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenThrow(new InternalServerErrorException("db error"));

    askerImportService.startImportForAskersWithoutSession();

    verify(rocketChatService, never()).loginUserFirstTime(anyString(), anyString());
  }

  // --- startImportForAskersWithoutSession — generic Exception catch ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnGenericException_When_UnexpectedError()
      throws IOException {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    when(identityClient.createKeycloakUser(any(), anyString(), anyString()))
        .thenThrow(new RuntimeException("unexpected"));

    askerImportService.startImportForAskersWithoutSession();

    verify(userService, never())
        .createUser(anyString(), any(), anyString(), anyString(), anyBoolean());
  }

  // --- startImport — blank email → getDummyEmail called ---

  @Test
  void startImport_Should_SetDummyEmail_When_EmailIsBlank() throws Exception {
    writeAskerCsv("1,validuser,,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(userHelper.getDummyEmail("kc-id")).thenReturn("dummy@example.com");
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    // session init returns null id → stops here, dummy email still verified
    Session noIdSession = new Session();
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(noIdSession);

    askerImportService.startImport();

    verify(userHelper).getDummyEmail("kc-id");
    verify(identityClient).updateDummyEmail(eq("kc-id"), any(UserDTO.class));
  }

  // --- startImport — blank password → getRandomPassword called ---

  @Test
  void startImport_Should_CallGetRandomPassword_When_PasswordIsBlank() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(userHelper.getRandomPassword()).thenReturn("rand-pw");
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(false);

    askerImportService.startImport();

    verify(userHelper).getRandomPassword();
  }

  // --- startImport — RC user token null in loop → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_RcUserTokenIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse(null, null));

    askerImportService.startImport();

    verify(rocketChatService, never()).createPrivateGroup(anyString(), any());
  }

  // --- startImport — createPrivateGroup returns group with null id → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_RcGroupIdIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse(null)));

    askerImportService.startImport();

    verify(userService, never()).saveUser(any(User.class));
  }

  // --- startImport — saveUser returns null userId → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_UpdatedUserIdIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    when(userService.saveUser(any(User.class))).thenReturn(new User());

    askerImportService.startImport();

    verify(sessionService, never()).saveSession(any(Session.class));
  }

  // --- startImport — saveSession returns null id → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_UpdatedSessionIdIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User savedUser = new User();
    savedUser.setUserId("db-id");
    when(userService.saveUser(any(User.class))).thenReturn(savedUser);
    when(consultantAgencyService.findConsultantsByAgencyId(any()))
        .thenReturn(Collections.emptyList());
    when(sessionService.saveSession(any(Session.class))).thenReturn(new Session());

    askerImportService.startImport();

    verify(sessionDataService, never()).saveSessionData(any(Session.class), any());
  }

  // --- startImport — team agency same consultant id (if-branch in addUserToGroup loop) ---

  @Test
  void startImport_Should_AddConsultantToGroup_When_TeamAgencyAndConsultantMatchesRecord()
      throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, true));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User savedUser = new User();
    savedUser.setUserId("db-id");
    when(userService.saveUser(any(User.class))).thenReturn(savedUser);
    Session savedSession = new Session();
    savedSession.setId(1L);
    when(sessionService.saveSession(any(Session.class))).thenReturn(savedSession);
    // Agency list contains the SAME consultant id as the record (hits if-branch)
    Consultant sameConsultant = new Consultant();
    sameConsultant.setId("cons-id");
    sameConsultant.setRocketChatId("rc-cons-id");
    ConsultantAgency sameAgency = new ConsultantAgency();
    sameAgency.setConsultant(sameConsultant);
    when(consultantAgencyService.findConsultantsByAgencyId(any())).thenReturn(List.of(sameAgency));

    askerImportService.startImport();

    verify(rocketChatService).addUserToGroup(eq("rc-cons-id"), eq("rc-group-id"));
  }

  // --- startImport — RocketChatLoginException in loop ---

  @Test
  void startImport_Should_BreakOnRcLoginException_When_UserLoginThrows() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenThrow(new RocketChatLoginException("rc login error"));

    askerImportService.startImport();

    verify(rocketChatService, never()).createPrivateGroup(anyString(), any());
  }

  // --- startImport — InternalServerErrorException | RocketChatPostWelcomeMessageException ---

  @Test
  void startImport_Should_BreakOnPostWelcomeMessageException_When_MessageServiceFails()
      throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User savedUser = new User();
    savedUser.setUserId("db-id");
    when(userService.saveUser(any(User.class))).thenReturn(savedUser);
    Session savedSession = new Session();
    savedSession.setId(1L);
    when(sessionService.saveSession(any(Session.class))).thenReturn(savedSession);
    when(consultantAgencyService.findConsultantsByAgencyId(any()))
        .thenReturn(Collections.emptyList());
    doThrow(
            new RocketChatPostWelcomeMessageException(
                "welcome fail",
                new Exception("cause"),
                de.caritas.cob.userservice.api.container.CreateEnquiryExceptionInformation.builder()
                    .build()))
        .when(messageServiceProvider)
        .postWelcomeMessageIfConfigured(anyString(), any(), any(), any());

    askerImportService.startImport();

    verify(sessionDataService, never()).saveSessionData(any(Session.class), any());
  }

  // --- startImport — generic Exception catch in loop ---

  @Test
  void startImport_Should_BreakOnGenericException_When_UnexpectedErrorInLoop() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    when(identityClient.createKeycloakUser(any(), anyString(), anyString()))
        .thenThrow(new RuntimeException("unexpected loop error"));

    askerImportService.startImport();

    verify(userService, never())
        .createUser(anyString(), any(), anyString(), anyString(), anyBoolean());
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // Branch coverage — empty-string and null-id edge cases — 2026-07-03
  // ---------------------------------------------------------------------------

  // --- startImportForAskersWithoutSession — idOld empty → null ternary branch ---

  @Test
  void startImportForAskersWithoutSession_Should_SetIdOldToNull_When_IdOldFieldIsEmpty()
      throws IOException {
    // Empty first field → (record.get(0).trim().equals("")) ? null : Long.valueOf(...)
    writeAskerWithoutSessionCsv(",validuser,valid@example.com,42,password\r\n");
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(null);

    askerImportService.startImportForAskersWithoutSession();

    // Reaches agency lookup with idOld=null — verifies the null-ternary branch executed
    verify(agencyService).getAgencyWithoutCaching(42L);
  }

  // --- startImportForAskersWithoutSession — dbUser.getUserId() == "" → ImportException ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_DbUserIdIsEmpty()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User emptyIdUser = new User();
    emptyIdUser.setUserId("");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(emptyIdUser);

    askerImportService.startImportForAskersWithoutSession();

    verify(rocketChatService, never()).loginUserFirstTime(anyString(), anyString());
  }

  // --- startImportForAskersWithoutSession — rcUserToken == "" → ImportException ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_RcTokenIsEmpty()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("", "rc-user-id"));

    askerImportService.startImportForAskersWithoutSession();

    verify(userAgencyService, never()).saveUserAgency(any());
  }

  // --- startImportForAskersWithoutSession — rcUserId == "" → ImportException ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_RcUserIdIsEmpty()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("valid-token", ""));

    askerImportService.startImportForAskersWithoutSession();

    verify(userAgencyService, never()).saveUserAgency(any());
  }

  // --- startImportForAskersWithoutSession — updatedUser.getUserId() == "" → ImportException ---

  @Test
  void startImportForAskersWithoutSession_Should_BreakOnImportException_When_UpdatedUserIdIsEmpty()
      throws Exception {
    writeAskerWithoutSessionCsv("1,newasker,valid@example.com,42,password123\r\n");
    when(userHelper.isUsernameValid("newasker")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    when(identityUsernameAvailability.isUsernameAvailable("newasker")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(true));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    User emptyIdUpdated = new User();
    emptyIdUpdated.setUserId("");
    when(userService.saveUser(any(User.class))).thenReturn(emptyIdUpdated);

    askerImportService.startImportForAskersWithoutSession();

    verify(userAgencyService, never()).saveUserAgency(any());
  }

  // --- startImport — statusCode != OK in preamble → ImportException → return early ---

  @Test
  void startImport_Should_ReturnEarly_When_RcSystemLoginStatusIsNotOk() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponseWithStatus("sys-token", "sys-user-id", HttpStatus.BAD_REQUEST));

    askerImportService.startImport();

    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  // --- startImport — idOld empty → null ternary branch ---

  @Test
  void startImport_Should_SetIdOldToNull_When_IdOldFieldIsEmpty() throws Exception {
    // Empty first field → (record.get(0).trim().equals("")) ? null : Long.valueOf(...)
    writeAskerCsv(",validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(null);

    askerImportService.startImport();

    verify(agencyService).getAgencyWithoutCaching(42L);
  }

  // --- startImport — dbUser.getUserId() == "" → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_DbUserIdIsEmpty() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User emptyIdUser = new User();
    emptyIdUser.setUserId("");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(emptyIdUser);

    askerImportService.startImport();

    verify(sessionService, never()).initializeSession(any(), any(), anyBoolean());
  }

  // --- startImport — rcUserToken == "" in loop → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_RcUserTokenIsEmpty() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("", "rc-user-id"));

    askerImportService.startImport();

    verify(rocketChatService, never()).createPrivateGroup(anyString(), any());
  }

  // --- startImport — rcUserId == "" in loop → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_RcUserIdIsEmpty() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("valid-token", ""));

    askerImportService.startImport();

    verify(rocketChatService, never()).createPrivateGroup(anyString(), any());
  }

  // --- startImport — rcGroupId == "" → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_RcGroupIdIsEmpty() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("")));

    askerImportService.startImport();

    verify(userService, never()).saveUser(any(User.class));
  }

  // --- startImport — updatedUser.getUserId() == "" → ImportException ---

  @Test
  void startImport_Should_BreakOnImportException_When_UpdatedUserIdIsEmpty() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, false));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User emptyIdUser = new User();
    emptyIdUser.setUserId("");
    when(userService.saveUser(any(User.class))).thenReturn(emptyIdUser);

    askerImportService.startImport();

    verify(sessionService, never()).saveSession(any(Session.class));
  }

  // --- startImport — agencyList == null (team agency) → skip loop, continue ---

  @Test
  void startImport_Should_SkipAgencyLoop_When_AgencyListIsNull() throws Exception {
    writeAskerCsv("1,validuser,valid@example.com,cons-id,12345,42,password\r\n");
    when(rocketChatCredentialsProvider.loginUser(anyString(), anyString()))
        .thenReturn(rcLoginResponse("sys-token", "sys-user-id"));
    when(userHelper.isUsernameValid("validuser")).thenReturn(true);
    when(agencyService.getAgencyWithoutCaching(42L)).thenReturn(agencyDTO(42L, 1, true));
    Consultant consultant = consultantInAgency("cons-id", 42L);
    consultant.setRocketChatId("rc-cons-id");
    when(consultantService.getConsultant("cons-id")).thenReturn(Optional.of(consultant));
    when(identityUsernameAvailability.isUsernameAvailable("validuser")).thenReturn(true);
    String kc = "kc-id";
    when(identityClient.createKeycloakUser(any(), anyString(), anyString())).thenReturn(kc);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(extendedConsultingType(false));
    User dbUser = new User();
    dbUser.setUserId("db-id");
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(dbUser);
    Session session = new Session();
    session.setId(1L);
    when(sessionService.initializeSession(any(), any(), anyBoolean())).thenReturn(session);
    when(rocketChatService.loginUserFirstTime(anyString(), anyString()))
        .thenReturn(rcLoginResponse("rc-token", "rc-user-id"));
    when(rocketChatService.createPrivateGroup(anyString(), any()))
        .thenReturn(Optional.of(groupResponse("rc-group-id")));
    User savedUser = new User();
    savedUser.setUserId("db-id");
    when(userService.saveUser(any(User.class))).thenReturn(savedUser);
    Session savedSession = new Session();
    savedSession.setId(1L);
    when(sessionService.saveSession(any(Session.class))).thenReturn(savedSession);
    when(consultantAgencyService.findConsultantsByAgencyId(any())).thenReturn(null);

    askerImportService.startImport();

    verify(sessionDataService).saveSessionData(any(Session.class), any());
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private Consultant consultantInAgency(String consultantId, Long agencyId) {
    Consultant consultant = new Consultant();
    consultant.setId(consultantId);
    ConsultantAgency ca = new ConsultantAgency();
    ca.setAgencyId(agencyId);
    ca.setConsultant(consultant);
    Set<ConsultantAgency> agencies = new HashSet<>();
    agencies.add(ca);
    consultant.setConsultantAgencies(agencies);
    return consultant;
  }

  private GroupResponseDTO groupResponse(String groupId) {
    GroupDTO group = new GroupDTO();
    group.setId(groupId);
    GroupResponseDTO response = new GroupResponseDTO();
    response.setGroup(group);
    return response;
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<LoginResponseDTO> rcLoginResponse(String token, String userId) {
    DataDTO data = new DataDTO();
    data.setAuthToken(token);
    data.setUserId(userId);
    LoginResponseDTO body = new LoginResponseDTO();
    body.setData(data);
    return new ResponseEntity<>(body, HttpStatus.OK);
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<LoginResponseDTO> rcLoginResponseWithStatus(
      String token, String userId, HttpStatus status) {
    DataDTO data = new DataDTO();
    data.setAuthToken(token);
    data.setUserId(userId);
    LoginResponseDTO body = new LoginResponseDTO();
    body.setData(data);
    return new ResponseEntity<>(body, status);
  }
}
