package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE;
import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.CREATED_IDENTITY_WITHOUT_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.CREATED_IDENTITY_WITH_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_SUCHT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ERROR;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_SUCHT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.identity.IdentityProvisioningException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserVerifier;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.email.WelcomeEmailService;
import de.caritas.cob.userservice.api.service.provisioning.ProvisioningCompensator;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.RegistrationStatisticsEvent;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
public class CreateUserFacadeTest {
  private final de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy
      inactivityPolicy =
          new de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy(
              24, 7, java.time.Instant.parse("2026-10-06T00:00:00Z"));

  @org.mockito.Mock
  private de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      inactivityEnrollment;

  @org.mockito.Mock private ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;

  @org.mockito.Mock
  private de.caritas.cob.userservice.api.service.agencyinvitelink.TemporaryAccountLinkResolver
      temporaryAccountLinkResolver;

  @org.mockito.Mock
  private de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsService
      temporaryAccountSettings;

  @org.mockito.Mock
  private de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher lifecycleEvents;

  @org.junit.jupiter.api.BeforeEach
  void recoveryPolicyFixture() {
    TenantContext.setCurrentTenant(1L);
    org.mockito.Mockito.lenient()
        .when(tenantService.getRestrictedTenantDataFresh(org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(
            invocation ->
                new de.caritas.cob.userservice.tenantservice.generated.web.model
                        .RestrictedTenantDTO()
                    .id(invocation.getArgument(0))
                    .settings(
                        new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                            .featureAskerEmailEnabled(true)));

    createUserFacade =
        new CreateUserFacade(
            chatRecoveryEnrollmentPolicyService,
            inactivityEnrollment,
            de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.policy(),
            userVerifier,
            identityClient,
            identityAccountRemover,
            identityPasswordUpdater,
            identityDummyEmailUpdater,
            userService,
            consultingTypeManager,
            agencyVerifier,
            createNewSessionFacade,
            statisticsService,
            topicService,
            welcomeEmailService,
            matrixSynapseService,
            sessionService,
            provisioningCompensator,
            tenantService,
            agencyService,
            applicationSettingsService,
            groupInviteRegistration,
            mock(de.caritas.cob.userservice.api.service.AskerEmailCollectionPolicy.class),
            mock(de.caritas.cob.userservice.api.service.SessionDataService.class),
            temporaryAccountLinkResolver,
            temporaryAccountSettings,
            lifecycleEvents,
            anonymousConversationCreatorService,
            identityAuthentication);

    org.mockito.Mockito.lenient()
        .when(consultingTypeManager.getConsultingTypeSettings(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());
    org.mockito.Mockito.lenient()
        .when(chatRecoveryEnrollmentPolicyService.forNewAsker(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
    org.mockito.Mockito.lenient()
        .when(
            chatRecoveryEnrollmentPolicyService.forNewConsultant(
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
    org.mockito.Mockito.lenient()
        .when(
            chatRecoveryEnrollmentPolicyService.forExistingIdentity(
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RecoveryPolicySnapshot("RECOVERY_KEY", 0));
    org.mockito.Mockito.lenient()
        .when(
            inactivityEnrollment.capture(
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.any(
                    de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group
                        .class)))
        .thenReturn(inactivityPolicy);
  }

  @org.junit.jupiter.api.AfterEach
  void clearIdentityTenant() {
    TenantContext.clear();
  }

  private CreateUserFacade createUserFacade;
  @Mock private IdentityClient identityClient;
  @Mock private IdentityAccountRemover identityAccountRemover;
  @Mock private IdentityPasswordUpdater identityPasswordUpdater;
  @Mock private IdentityDummyEmailUpdater identityDummyEmailUpdater;
  @Mock private UserService userService;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private AgencyVerifier agencyVerifier;
  @Mock private CreateNewSessionFacade createNewSessionFacade;
  @Mock private UserVerifier userVerifier;
  @Mock private StatisticsService statisticsService;
  @Mock private TopicService topicService;

  @Mock private TenantService tenantService;

  @Mock private AgencyService agencyService;

  @Mock private ApplicationSettingsService applicationSettingsService;

  @Mock private CreateSessionFacade createSessionFacade;

  @Mock private SessionService sessionService;

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private WelcomeEmailService welcomeEmailService;
  @Mock private GroupInviteRegistration groupInviteRegistration;

  @Mock
  private de.caritas.cob.userservice.api.conversation.service.AnonymousConversationCreatorService
      anonymousConversationCreatorService;

  @Mock
  private de.caritas.cob.userservice.api.port.out.IdentityAuthentication identityAuthentication;

  @Spy
  private ProvisioningCompensator provisioningCompensator =
      new ProvisioningCompensator(new SimpleMeterRegistry());

  /** #1662: a temporary account never saves an e-mail address, also not at registration. */
  @Test
  void aTemporaryRegistrationWithAnEmailAddressIsRefusedBeforeAnyIdentityEffects() {
    var dto =
        UserDTO.builder()
            .username("guest-login")
            .password("pw")
            .postcode("12345")
            .agencyId(700L)
            .consultingType("1")
            .termsAccepted("true")
            .email("guest@example.org")
            .build();
    dto.setTemporary(true);
    var settings =
        new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
            .featureAskerEmailEnabled(true)
            .featureAskerEmailCollectionAgencyCounsellingEnabled(true)
            .featureAskerEmailCollectionSelfHelpEnabled(true);
    org.mockito.Mockito.lenient()
        .when(tenantService.getRestrictedTenantDataFresh(42L))
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));
    var policy =
        new de.caritas.cob.userservice.api.service.AskerEmailCollectionPolicy(
            tenantService,
            mock(de.caritas.cob.userservice.api.port.out.SessionRepository.class),
            mock(de.caritas.cob.userservice.api.port.out.UserChatRepository.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade, "emailCollectionPolicy", policy);
    var group = new Chat();
    group.setConversationType(ConversationType.SELF_HELP);
    when(groupInviteRegistration.resolveInvitation(dto))
        .thenReturn(
            Optional.of(
                new GroupInviteRegistration.Invitation(
                    group, de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)));
    TenantContext.setCurrentTenant(42L);
    try {
      assertThrows(
          de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
    } finally {
      TenantContext.clear();
    }
    org.mockito.Mockito.verifyNoInteractions(
        identityClient, matrixSynapseService, userService, createNewSessionFacade);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void registrationCollectionUsesTheVerifiedPurposeBeforeAnyIdentityEffects(boolean groupSignup) {
    var dto =
        UserDTO.builder()
            .username("new-login")
            .password("pw")
            .postcode("12345")
            .agencyId(700L)
            .consultingType("1")
            .termsAccepted("true")
            .email("new@example.org")
            .build();
    var settings =
        new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
            .featureAskerEmailEnabled(true)
            .featureAskerEmailCollectionAgencyCounsellingEnabled(groupSignup)
            .featureAskerEmailCollectionSelfHelpEnabled(!groupSignup);
    when(tenantService.getRestrictedTenantDataFresh(42L))
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));
    var policy =
        new de.caritas.cob.userservice.api.service.AskerEmailCollectionPolicy(
            tenantService,
            mock(de.caritas.cob.userservice.api.port.out.SessionRepository.class),
            mock(de.caritas.cob.userservice.api.port.out.UserChatRepository.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade, "emailCollectionPolicy", policy);
    if (groupSignup) {
      var group = new Chat();
      group.setConversationType(ConversationType.SELF_HELP);
      when(groupInviteRegistration.resolveInvitation(dto))
          .thenReturn(
              Optional.of(
                  new GroupInviteRegistration.Invitation(
                      group, de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)));
    }
    TenantContext.setCurrentTenant(42L);
    try {
      assertThrows(
          de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
    } finally {
      TenantContext.clear();
    }
    org.mockito.Mockito.verifyNoInteractions(
        identityClient, matrixSynapseService, userService, createNewSessionFacade);
  }

  private static final String SIGNUP_PASSWORD = "Sichere-Passphrase9!";

  private UserDTO signupWithAlias(String password, String alias) {
    var dto =
        UserDTO.builder()
            .username("new-login")
            .password(password)
            .postcode("12345")
            .agencyId(700L)
            .consultingType("1")
            .termsAccepted("true")
            .displayName(alias)
            .build();
    var names = mock(de.caritas.cob.userservice.api.service.SessionDataService.class);
    when(names.validateRegistrationNames(
            org.mockito.ArgumentMatchers.eq("new-login"),
            org.mockito.ArgumentMatchers.eq(alias),
            org.mockito.ArgumentMatchers.eq(42L),
            any()))
        .thenReturn(alias);
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade, "sessionDataService", names);
    return dto;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {SIGNUP_PASSWORD, "  " + SIGNUP_PASSWORD + "\t"})
  void signupAliasContainingThePasswordIsRefusedBeforeIdentityOrAccountEffects(String password) {
    var dto = signupWithAlias(password, "Name " + SIGNUP_PASSWORD);
    TenantContext.setCurrentTenant(42L);
    try {
      assertThrows(
          BadRequestException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
    } finally {
      TenantContext.clear();
    }
    org.mockito.Mockito.verifyNoInteractions(
        identityClient, matrixSynapseService, userService, createNewSessionFacade);
  }

  enum AliasCase {
    SAFE_ALIAS,
    ABSENT_ALIAS,
    GROUP_INVITE,
    DIRECT_LINK
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(AliasCase.class)
  void signupWithoutAPasswordBearingPersonalAliasReachesIdentityCreation(AliasCase aliasCase) {
    var alias =
        switch (aliasCase) {
          case SAFE_ALIAS -> "Sonnenblume";
          case ABSENT_ALIAS -> null;
          case GROUP_INVITE, DIRECT_LINK -> "Name " + SIGNUP_PASSWORD;
        };
    var dto = signupWithAlias(SIGNUP_PASSWORD, alias);
    if (aliasCase == AliasCase.GROUP_INVITE) {
      var group = new Chat();
      group.setConversationType(ConversationType.SELF_HELP);
      when(groupInviteRegistration.resolveInvitation(dto))
          .thenReturn(
              Optional.of(
                  new GroupInviteRegistration.Invitation(
                      group, de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)));
    }
    if (aliasCase == AliasCase.DIRECT_LINK) {
      var link =
          de.caritas.cob.userservice.api.model.AgencyInviteLink.builder()
              .id(77L)
              .chatType("CHAT")
              .temporaryAccount(false)
              .build();
      when(temporaryAccountLinkResolver.resolveEntry(dto))
          .thenReturn(
              Optional.of(
                  new de.caritas.cob.userservice.api.service.agencyinvitelink
                      .TemporaryAccountLinkResolver.Entry(link, link.effectiveGuestAccountMode())));
    }
    var reachedIdentity = new IllegalStateException("identity creation reached");
    when(identityClient.createUser(dto)).thenThrow(reachedIdentity);
    TenantContext.setCurrentTenant(42L);
    try {
      var thrown =
          assertThrows(
              IllegalStateException.class,
              () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
      org.assertj.core.api.Assertions.assertThat(thrown).isSameAs(reachedIdentity);
    } finally {
      TenantContext.clear();
    }
    var expectedAlias = aliasCase == AliasCase.SAFE_ALIAS ? alias : null;
    org.assertj.core.api.Assertions.assertThat(dto.getDisplayName()).isEqualTo(expectedAlias);
  }

  @Test
  void customSignupAliasIsRefusedBeforeIdentityOrAccountEffectsWhenTypingIsDisabled() {
    var dto =
        UserDTO.builder()
            .username("new-login")
            .password("pw")
            .postcode("12345")
            .agencyId(700L)
            .consultingType("1")
            .termsAccepted("true")
            .displayName("Freely typed name")
            .build();
    when(tenantService.getRestrictedTenantDataFresh(42L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(42L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDisplayNameEditable(false)));
    var names =
        new de.caritas.cob.userservice.api.service.SessionDataService(
            mock(de.caritas.cob.userservice.api.port.out.SessionDataRepository.class),
            mock(de.caritas.cob.userservice.api.helper.SessionDataProvider.class),
            sessionService,
            mock(de.caritas.cob.userservice.api.helper.AuthenticatedUser.class),
            tenantService,
            new de.caritas.cob.userservice.api.service.GeneratedPseudonyms(),
            mock(de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade, "sessionDataService", names);
    TenantContext.setCurrentTenant(42L);
    try {
      assertThrows(
          de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
    } finally {
      TenantContext.clear();
    }
    org.mockito.Mockito.verifyNoInteractions(
        identityClient, matrixSynapseService, userService, createNewSessionFacade);
  }

  @Test
  void legacyGroupSignupAliasIsIgnoredBeforeIdentityAndCannotRenameASharedRoom() {
    var dto =
        UserDTO.builder()
            .username("new-login")
            .password("pw")
            .postcode("12345")
            .agencyId(700L)
            .consultingType("1")
            .termsAccepted("true")
            .displayName("legacy ignored alias")
            .build();
    var group = new Chat();
    group.setConversationType(ConversationType.SELF_HELP);
    when(groupInviteRegistration.resolveInvitation(dto))
        .thenReturn(
            Optional.of(
                new GroupInviteRegistration.Invitation(
                    group, de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)));
    when(identityClient.createUser(dto))
        .thenAnswer(
            call -> {
              org.assertj.core.api.Assertions.assertThat(
                      ((UserDTO) call.getArgument(0)).getDisplayName())
                  .isNull();
              throw new BadRequestException("test stops before external identity effects");
            });
    TenantContext.setCurrentTenant(42L);
    try {
      assertThrows(
          BadRequestException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
    } finally {
      TenantContext.clear();
    }
    org.mockito.Mockito.verifyNoInteractions(
        matrixSynapseService, userService, createNewSessionFacade);
  }

  @Test
  void verifiedDirectLinkSignupKeepsItsNoSessionAliasBoundary() {
    var dto =
        UserDTO.builder()
            .username("direct-login")
            .password("pw")
            .postcode("12345")
            .agencyId(700L)
            .consultingType("1")
            .termsAccepted("true")
            .directLinkToken("verified-fixture")
            .displayName("legacy ignored alias")
            .build();
    var link =
        de.caritas.cob.userservice.api.model.AgencyInviteLink.builder()
            .id(77L)
            .chatType("CHAT")
            .temporaryAccount(false)
            .build();
    when(temporaryAccountLinkResolver.resolveEntry(dto))
        .thenReturn(
            Optional.of(
                new de.caritas.cob.userservice.api.service.agencyinvitelink
                    .TemporaryAccountLinkResolver.Entry(link, link.effectiveGuestAccountMode())));
    var names = mock(de.caritas.cob.userservice.api.service.SessionDataService.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade, "sessionDataService", names);
    when(identityClient.createUser(dto))
        .thenAnswer(
            call -> {
              org.assertj.core.api.Assertions.assertThat(
                      ((UserDTO) call.getArgument(0)).getDisplayName())
                  .isNull();
              throw new BadRequestException("fixture stops before external effects");
            });
    TenantContext.setCurrentTenant(42L);
    try {
      assertThrows(
          BadRequestException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
    } finally {
      TenantContext.clear();
    }
    verify(names)
        .validateRegistrationNames(
            "direct-login", "legacy ignored alias", 42L, ConversationType.AGENCY_COUNSELLING);
    org.mockito.Mockito.verifyNoInteractions(
        createNewSessionFacade, matrixSynapseService, userService);
  }

  @Test
  void verifiedDirectLinkUsesRegisteredAgencyCollectionBeforeIdentityEffects() {
    var dto =
        UserDTO.builder()
            .username("direct-login")
            .password("pw")
            .postcode("12345")
            .agencyId(700L)
            .consultingType("1")
            .termsAccepted("true")
            .directLinkToken("verified-fixture")
            .email("new@example.org")
            .build();
    var link =
        de.caritas.cob.userservice.api.model.AgencyInviteLink.builder()
            .id(77L)
            .chatType("VIDEO")
            .temporaryAccount(false)
            .build();
    when(temporaryAccountLinkResolver.resolveEntry(dto))
        .thenReturn(
            Optional.of(
                new de.caritas.cob.userservice.api.service.agencyinvitelink
                    .TemporaryAccountLinkResolver.Entry(link, link.effectiveGuestAccountMode())));
    when(tenantService.getRestrictedTenantDataFresh(42L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(42L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureAskerEmailEnabled(true)
                        .featureAskerEmailCollectionAgencyCounsellingEnabled(false)));
    var policy =
        new de.caritas.cob.userservice.api.service.AskerEmailCollectionPolicy(
            tenantService,
            mock(de.caritas.cob.userservice.api.port.out.SessionRepository.class),
            mock(de.caritas.cob.userservice.api.port.out.UserChatRepository.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade, "emailCollectionPolicy", policy);
    TenantContext.setCurrentTenant(42L);
    try {
      assertThrows(
          de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(dto));
    } finally {
      TenantContext.clear();
    }
    org.mockito.Mockito.verifyNoInteractions(
        identityClient, createNewSessionFacade, matrixSynapseService, userService);
  }

  @Test
  void disabledEmailCollectionStopsRegistrationBeforeIdentityProvisioning() {
    TenantContext.setCurrentTenant(79L);
    var request = new UserDTO();
    request.setEmail("new@example.org");
    org.mockito.Mockito.lenient()
        .when(tenantService.getRestrictedTenantDataFresh(79L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(79L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureAskerEmailEnabled(false)));
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> createUserFacade.createUserAccountWithInitializedConsultingType(request))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
      org.mockito.Mockito.verifyNoInteractions(identityClient, userService, matrixSynapseService);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void singleTenantRegistrationEnforcesConfiguredEmailPolicyBeforeProvisioning() {
    TenantContext.clear();
    var request = new UserDTO();
    request.setEmail("new@example.org");
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(
            new RestrictedTenantDTO()
                .id(42L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureAskerEmailEnabled(false)));
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> createUserFacade.createUserAccountWithInitializedConsultingType(request))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
      org.mockito.Mockito.verifyNoInteractions(identityClient, userService, matrixSynapseService);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void pausedTenantStopsRegisteredCreationBeforeIdentityRowsOrChatWork() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade,
        "tenantService",
        de.caritas.cob.userservice.api.testHelper.PausedTenantAuthorityFixture.paused(79L));
    TenantContext.setCurrentTenant(79L);
    try {
      var refused =
          assertThrows(
              org.springframework.web.server.ResponseStatusException.class,
              () ->
                  createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
      org.junit.jupiter.api.Assertions.assertEquals(
          org.springframework.http.HttpStatus.FORBIDDEN, refused.getStatusCode());
      org.mockito.Mockito.verifyNoInteractions(
          identityClient,
          userService,
          matrixSynapseService,
          createNewSessionFacade,
          sessionService,
          welcomeEmailService,
          inactivityEnrollment);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void unavailableRecoveryPolicyStopsBeforeProvisioning() {
    org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
        .when(chatRecoveryEnrollmentPolicyService)
        .forNewAsker(any());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
    org.mockito.Mockito.verifyNoInteractions(identityClient, matrixSynapseService, userService);
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_throwExpectedStatusException_When_UsernameIsAlreadyExisting() {
    doThrow(new CustomValidationHttpStatusException(USERNAME_NOT_AVAILABLE))
        .when(userVerifier)
        .checkIfUsernameIsAvailable(any());

    try {
      this.createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(
          e.getCustomHttpHeaders().get("X-Reason").get(0),
          Matchers.is(USERNAME_NOT_AVAILABLE.name()));
    }
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_ThrowBadRequest_When_ProvidedConsultingTypeDoesNotMatchAgency() {
    assertThrows(
        BadRequestException.class,
        () -> {
          doNothing().when(userVerifier).checkIfUsernameIsAvailable(any());
          doThrow(new BadRequestException(ERROR))
              .when(agencyVerifier)
              .checkIfConsultingTypeMatchesToAgency(any());

          createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
        });
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_throwConflictException_When_usernameIsNotAvailable() {
    doThrow(new CustomValidationHttpStatusException(USERNAME_NOT_AVAILABLE, HttpStatus.CONFLICT))
        .when(userVerifier)
        .checkIfUsernameIsAvailable(any());

    try {
      this.createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(
          e.getCustomHttpHeaders().get("X-Reason").get(0),
          Matchers.is(USERNAME_NOT_AVAILABLE.name()));
      assertThat(e.getHttpStatus(), is(HttpStatus.CONFLICT));
    }
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_AbortBeforeDependentWrites_When_IdentityProviderReturnsNoUserId() {
    PlainCredentialsHolder.set("plain-user", null);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITHOUT_USER_ID);

    assertThrows(
        IdentityProvisioningException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateIdentity_When_DatabaseUserCreationFails()
          throws Exception {
    PlainCredentialsHolder.set("plain-user", "plain-password");
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenThrow(new IllegalArgumentException("database write failed"));

    assertThrows(
        IllegalArgumentException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    org.mockito.ArgumentCaptor<RecoveryPolicySnapshot> snapshotCaptor =
        org.mockito.ArgumentCaptor.forClass(RecoveryPolicySnapshot.class);
    verify(userService, times(1))
        .createUser(any(), any(), any(), any(), anyBoolean(), any(), snapshotCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3), snapshotCaptor.getValue());
    verify(identityAccountRemover).rollbackUser(USER_ID);
    verify(matrixSynapseService, never()).createUser(any(), any(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_ProvisionMatrixAndInitializeSession_When_ConsultingTypeIsKreuzbundAndNoTenantIsSet()
          throws Exception {
    // Covers the Kreuzbund path without a tenant context; the sibling test below covers the
    // tenant-specific path. The assertions verify Matrix provisioning, session initialization,
    // and credential cleanup.
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();

    PlainCredentialsHolder.set("plain-user", "plain-password");
    try {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

      verify(identityClient).createUser(any());
      verify(matrixSynapseService).createUser(any(), any(), any());
      verify(createNewSessionFacade)
          .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
      // The plaintext password must not survive the registration.
      assertThat(PlainCredentialsHolder.get(), nullValue());
    } finally {
      PlainCredentialsHolder.clear();
    }
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_CallNecessaryMethods_When_EverythingSucceeds()
          throws Exception {
    TenantContext.setCurrentTenant(1L);
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO());
    when(agencyService.getAgencyWithoutCaching(Mockito.anyLong())).thenReturn(new AgencyDTO());
    givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);
    TenantContext.clear();
    verify(identityClient, times(1)).createUser(any(UserDTO.class));
    verify(identityClient, times(1)).updateRole(any(), any(UserRole.class));
    verify(identityPasswordUpdater, times(1)).updatePassword(anyString(), anyString());
    verify(createNewSessionFacade, times(1))
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(statisticsService, times(1)).fireEvent(any());
    verify(matrixSynapseService, never()).deactivateUser(anyString());
    verify(sessionService, never()).deleteSession(any(Session.class));
    verify(userService, never()).deleteUser(any(User.class));
    verify(identityAccountRemover, never()).rollbackUser(anyString());
    verify(welcomeEmailService, times(1)).sendWelcomeEmail(any(User.class), anyString());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_CallNecessaryMethods_When_EverythingSucceeds() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    createUserFacade.updateIdentityAndCreateAccount(
        USER_ID,
        USER_DTO_SUCHT,
        RegistrationType.REGISTERED,
        new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));

    verify(identityClient, times(1)).updateRole(any(), any(UserRole.class));
    verify(identityPasswordUpdater, times(1)).updatePassword(anyString(), anyString());
  }

  @Test
  public void
      updateIdentityAndCreateAccount_Should_AbortBeforeDatabaseWrite_When_PasswordUpdateFails() {
    RuntimeException identityFailure = new RuntimeException("password update failed");
    doThrow(identityFailure).when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () ->
                createUserFacade.updateIdentityAndCreateAccount(
                    USER_ID,
                    USER_DTO_SUCHT,
                    RegistrationType.REGISTERED,
                    new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3)));

    assertThat(propagated, is(identityFailure));
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
  }

  @Test
  public void
      updateIdentityAndCreateAccount_Should_AbortBeforeDatabaseWrite_When_RoleUpdateFails() {
    RuntimeException identityFailure = new RuntimeException("role update failed");
    doThrow(identityFailure).when(identityClient).updateRole(anyString(), any(UserRole.class));

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () ->
                createUserFacade.updateIdentityAndCreateAccount(
                    USER_ID,
                    USER_DTO_SUCHT,
                    RegistrationType.REGISTERED,
                    new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3)));

    assertThat(propagated, is(identityFailure));
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateIdentity_When_RoleUpdateFails() {
    PlainCredentialsHolder.set("plain-user", "plain-password");
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    RuntimeException identityFailure = new RuntimeException("role update failed");
    doThrow(identityFailure).when(identityClient).updateRole(anyString(), any(UserRole.class));

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(propagated, is(identityFailure));
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
    verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_PropagateException_When_CreateDbUserFails() {
    // The database user creation failure is not wrapped/rolled back on this code path; the original
    // exception propagates to the caller.
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          when(consultingTypeManager.getConsultingTypeSettings(any()))
              .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
          doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());
          when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
              .thenThrow(new IllegalArgumentException());

          createUserFacade.updateIdentityAndCreateAccount(
              USER_ID,
              USER_DTO_SUCHT,
              RegistrationType.REGISTERED,
              new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
        });
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_LeaveTheGroupBeforeDeletingTheUser_When_GroupJoinFails()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    Chat group =
        Chat.builder()
            .id(4711L)
            .topic("group")
            .initialStartDate(LocalDateTime.now())
            .startDate(LocalDateTime.now())
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(groupInviteRegistration.resolveInvitation(any()))
        .thenReturn(
            Optional.of(
                new GroupInviteRegistration.Invitation(
                    group, de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)));
    // The membership row may exist although the call failed, so compensation must still leave.
    RuntimeException joinFailure = new RuntimeException("membership write failed");
    doThrow(joinFailure).when(groupInviteRegistration).join(group, user);

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(propagated, is(joinFailure));
    // The membership references the user row, and the user row references the identity.
    var compensation = inOrder(groupInviteRegistration, userService, identityAccountRemover);
    compensation.verify(groupInviteRegistration).leave(group, user);
    compensation.verify(userService).deleteUser(user);
    compensation.verify(identityAccountRemover).rollbackUser(USER_ID);
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_JoinTheGroupWithoutARegistrationEvent_When_InvitedToAGroup()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    Chat group =
        Chat.builder()
            .id(4711L)
            .topic("group")
            .initialStartDate(LocalDateTime.now())
            .startDate(LocalDateTime.now())
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(groupInviteRegistration.resolveInvitation(any()))
        .thenReturn(
            Optional.of(
                new GroupInviteRegistration.Invitation(
                    group, de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)));
    // Without it the event would fail to build and be swallowed, hiding a regression.

    Long sessionId =
        createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    assertThat(sessionId, nullValue());
    verify(groupInviteRegistration).join(group, user);
    // The registration event contract requires a session id, which a group join does not have.
    verify(statisticsService, never()).fireEvent(any());
    verify(groupInviteRegistration, never()).leave(any(), any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_RejectATemporaryAccount_When_NoGroupInviteBacksIt() {
    // The deletion job removes temporary accounts, so only the invite flow may ask for one.
    USER_DTO_SUCHT.setTemporary(true);
    try {
      assertThrows(
          BadRequestException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
    } finally {
      USER_DTO_SUCHT.setTemporary(false);
    }

    verify(identityClient, never()).createUser(any());
    verify(userService, never()).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_StoreATemporaryAccount_When_RegisteringThroughAGroupInvite()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    Chat group =
        Chat.builder()
            .id(4711L)
            .topic("group")
            .initialStartDate(LocalDateTime.now())
            .startDate(LocalDateTime.now())
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(groupInviteRegistration.resolveInvitation(any()))
        .thenReturn(
            Optional.of(
                new GroupInviteRegistration.Invitation(
                    group, de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)));

    USER_DTO_SUCHT.setTemporary(true);
    try {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } finally {
      USER_DTO_SUCHT.setTemporary(false);
    }

    assertThat(user.isTemporaryAccount(), is(true));
    // #1662: "join without account" lets the guest keep the account later
    assertThat(
        user.getGuestAccountModeAtEntry(),
        is(de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES));
    verify(groupInviteRegistration).join(group, user);
  }

  @ParameterizedTest
  @ValueSource(strings = {"COUNSELLOR", "CALL"})
  void
      createUserAccountWithInitializedConsultingType_Should_CopyTheLifetimesAndOpenNoEnquiry_When_RegisteringThroughADirectLink(
          String linkKind) throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    var link =
        de.caritas.cob.userservice.api.model.AgencyInviteLink.builder()
            .id(9L)
            .linkKind(linkKind)
            .chatType("AUDIO")
            .temporaryAccount(true)
            .guestAccountMode(de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES)
            .build();
    // The link stores GUEST_CHOOSES, but the Träger's cap admits only TEMPORARY now: the account
    // must keep the resolver's capped mode, not reread the stored one.
    when(temporaryAccountLinkResolver.resolveEntry(any()))
        .thenReturn(
            Optional.of(
                new de.caritas.cob.userservice.api.service.agencyinvitelink
                    .TemporaryAccountLinkResolver.Entry(
                    link, de.caritas.cob.userservice.api.model.GuestAccountMode.TEMPORARY)));
    var snapshot =
        de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot.builder()
            .medium(
                de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.AUDIO)
            .inviteLinkId(9L)
            .callDeleteAfterMinutes(15)
            .callMaxLifetimeHours(24)
            .chatInactivityDays(30)
            .build();
    when(temporaryAccountSettings.snapshotForNewAccount(
            de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.AUDIO, 9L))
        .thenReturn(snapshot);

    USER_DTO_SUCHT.setTemporary(true);
    Long sessionId;
    try {
      sessionId = createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } finally {
      USER_DTO_SUCHT.setTemporary(false);
    }

    assertThat(sessionId, nullValue());
    assertThat(user.isTemporaryAccount(), is(true));
    assertThat(user.getTemporaryAccountSnapshot(), is(snapshot));
    // #1662: the mode the guest entered under, as the link resolver capped it
    assertThat(
        user.getGuestAccountModeAtEntry(),
        is(de.caritas.cob.userservice.api.model.GuestAccountMode.TEMPORARY));
    org.mockito.Mockito.verifyNoInteractions(createNewSessionFacade);
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_OpenNoEnquiryAndKeepTheAccount_When_RegisteringPersistentlyThroughADirectLink()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    var link =
        de.caritas.cob.userservice.api.model.AgencyInviteLink.builder()
            .id(9L)
            .chatType("CHAT")
            .temporaryAccount(false)
            .build();
    when(temporaryAccountLinkResolver.resolveEntry(any()))
        .thenReturn(
            Optional.of(
                new de.caritas.cob.userservice.api.service.agencyinvitelink
                    .TemporaryAccountLinkResolver.Entry(link, link.effectiveGuestAccountMode())));

    Long sessionId =
        createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    assertThat(sessionId, nullValue());
    assertThat(user.isTemporaryAccount(), is(false));
    assertThat(user.getTemporaryAccountSnapshot(), nullValue());
    assertThat(user.getGuestAccountModeAtEntry(), nullValue());
    org.mockito.Mockito.verifyNoInteractions(createNewSessionFacade, temporaryAccountSettings);
  }

  @Test
  void registrationWithAnAddonReferenceAnnouncesTheCommittedAccount() throws Exception {
    givenBasicRegistrationStubs();
    User user = givenAFullyPersistedUser();
    user.setUserId(USER_ID);
    var registration = registrationWithReference("ref_42-A");
    PlainCredentialsHolder.set("plainuser", "plainpw");

    createUserFacade.createUserAccountWithInitializedConsultingType(registration);

    var order = inOrder(createNewSessionFacade, lifecycleEvents, welcomeEmailService);
    order
        .verify(createNewSessionFacade)
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    order.verify(lifecycleEvents).userRegistered(1L, USER_ID, "ref_42-A");
    order.verify(welcomeEmailService).sendWelcomeEmail(any(), any());
  }

  @Test
  void registrationWithoutAnAddonReferenceAnnouncesNothing() throws Exception {
    givenBasicRegistrationStubs();
    givenAFullyPersistedUser().setUserId(USER_ID);
    PlainCredentialsHolder.set("plainuser", "plainpw");

    createUserFacade.createUserAccountWithInitializedConsultingType(
        registrationWithReference(null));

    org.mockito.Mockito.verifyNoInteractions(lifecycleEvents);
  }

  @Test
  void failedRegistrationAnnouncesNothing() throws Exception {
    lenient().when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    givenMatrixProvisioningSucceeds();
    givenAFullyPersistedUser().setUserId(USER_ID);
    PlainCredentialsHolder.set("plainuser", "plainpw");
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new IllegalStateException("session failed"));

    assertThrows(
        IllegalStateException.class,
        () ->
            createUserFacade.createUserAccountWithInitializedConsultingType(
                registrationWithReference("ref_42-A")));

    org.mockito.Mockito.verifyNoInteractions(lifecycleEvents);
  }

  private static UserDTO registrationWithReference(String registrationRef) {
    var registration =
        new UserDTO(
            USER_DTO_SUCHT.getUsername(),
            USER_DTO_SUCHT.getPostcode(),
            USER_DTO_SUCHT.getAgencyId(),
            USER_DTO_SUCHT.getPassword(),
            USER_DTO_SUCHT.getEmail(),
            USER_DTO_SUCHT.getTermsAccepted(),
            USER_DTO_SUCHT.getConsultingType());
    registration.setRegistrationRef(registrationRef);
    return registration;
  }

  private User givenAFullyPersistedUser() {
    User user = new User();
    user.setUsername("dbUser");
    user.setTenantId(1L);
    user.setCreateDate(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);
    return user;
  }

  private void givenBasicRegistrationStubs() throws Exception {
    lenient().when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    givenMatrixProvisioningSucceeds();
  }

  private void givenMatrixProvisioningSucceeds() throws Exception {
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@registered:matrix.example.org");
    lenient()
        .when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(matrixResponseBody));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CreateMatrixUser_When_PlainUsernameAvailableFromThreadLocal()
          throws Exception {
    givenBasicRegistrationStubs();
    User user = givenAFullyPersistedUser();
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@plainuser:matrix.example.org");
    try {
      PlainCredentialsHolder.set("plainuser", "plainpw");
      when(matrixSynapseService.createUser(eq("plainuser"), anyString(), eq("plainuser")))
          .thenReturn(ResponseEntity.ok(matrixResponseBody));

      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } finally {
      PlainCredentialsHolder.clear();
    }

    verify(matrixSynapseService, times(1))
        .createUser(eq("plainuser"), anyString(), eq("plainuser"));
    assertThat(user.getMatrixUserId(), is("@plainuser:matrix.example.org"));
    verify(userService, times(2)).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_UseDecodedDbUsername_When_PlainCredentialsNotAvailable()
          throws Exception {
    PlainCredentialsHolder.clear();
    givenBasicRegistrationStubs();
    User user = givenAFullyPersistedUser();
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@dbUser:matrix.example.org");
    when(matrixSynapseService.createUser(eq("dbUser"), anyString(), eq("dbUser")))
        .thenReturn(ResponseEntity.ok(matrixResponseBody));

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    verify(matrixSynapseService, times(1)).createUser(eq("dbUser"), anyString(), eq("dbUser"));
    assertThat(user.getMatrixUserId(), is("@dbUser:matrix.example.org"));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_PlainUsernameNotResolvable()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    // userService.createUser/saveUser left unstubbed -> null user, no username to resolve

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(matrixSynapseService, never()).createUser(any(), any(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_MatrixUserCreationThrows()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    User user = givenAFullyPersistedUser();
    when(matrixSynapseService.createUser(any(), any(), any()))
        .thenThrow(new MatrixCreateUserException("boom"));

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_MatrixResponseBodyIsNull()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    User user = givenAFullyPersistedUser();
    when(matrixSynapseService.createUser(any(), any(), any())).thenReturn(ResponseEntity.ok(null));

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(user.getMatrixUserId(), nullValue());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
    verify(userService, times(1)).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_Compensate_When_InitializeNewSessionThrows()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new RuntimeException("Matrix room initialization failed"));
    User user = givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();

    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(exception.getMessage(), is("Matrix room initialization failed"));
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
  }

  @Test
  void failedDatabaseDeletionStillDiscardsTheUncompletedLifecycle() throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new RuntimeException("Matrix room initialization failed"));
    User user = givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();
    doThrow(new IllegalStateException("database unavailable")).when(userService).deleteUser(user);

    assertThrows(
        RuntimeException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(inactivityEnrollment).discardUncompletedCreation(USER_ID, inactivityPolicy);
    verify(identityAccountRemover).rollbackUser(USER_ID);
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAllCreatedResources_When_AllSessionPathsFail()
          throws Exception {
    PlainCredentialsHolder.set("plainuser", "plainpw");
    User user =
        new User(USER_ID, null, USER_DTO_SUCHT.getUsername(), USER_DTO_SUCHT.getEmail(), false);
    Session partialSession = new Session();
    partialSession.setId(42L);
    var matrixResponse = new MatrixCreateUserResponseDTO();
    matrixResponse.setUserId("@plainuser:matrix.example.org");
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_SUCHT);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any(User.class))).thenReturn(user);
    when(matrixSynapseService.createUser(eq("plainuser"), anyString(), eq("plainuser")))
        .thenReturn(ResponseEntity.ok(matrixResponse));
    when(matrixSynapseService.deactivateUser("@plainuser:matrix.example.org")).thenReturn(true);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new InternalServerErrorException("session initialization failed"));
    when(sessionService.getSessionsForUser(user)).thenReturn(List.of(partialSession));

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    var compensationOrder =
        inOrder(matrixSynapseService, sessionService, userService, identityAccountRemover);
    compensationOrder.verify(sessionService).deleteSession(partialSession);
    compensationOrder.verify(matrixSynapseService).deactivateUser("@plainuser:matrix.example.org");
    compensationOrder.verify(userService).deleteUser(user);
    compensationOrder.verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_SucceedOnReplayAfterFirstAttemptWasCompensated()
          throws Exception {
    User firstUser = new User("first-id", null, "username", USER_DTO_SUCHT.getEmail(), false);
    User replayUser = new User("replay-id", null, "username", USER_DTO_SUCHT.getEmail(), false);
    Session partialSession = new Session();
    partialSession.setId(42L);
    var firstIdentity =
        new de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity("first-id");
    var replayIdentity =
        new de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity("replay-id");
    var firstMatrixResponse = new MatrixCreateUserResponseDTO();
    firstMatrixResponse.setUserId("@first:matrix.example.org");
    var replayMatrixResponse = new MatrixCreateUserResponseDTO();
    replayMatrixResponse.setUserId("@replay:matrix.example.org");
    var replayRegistration =
        new NewRegistrationResponseDto().sessionId(99L).status(HttpStatus.CREATED);

    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_SUCHT);
    when(identityClient.createUser(any())).thenReturn(firstIdentity, replayIdentity);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(firstUser, replayUser);
    when(userService.saveUser(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(
            ResponseEntity.ok(firstMatrixResponse), ResponseEntity.ok(replayMatrixResponse));
    when(matrixSynapseService.deactivateUser("@first:matrix.example.org")).thenReturn(true);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new InternalServerErrorException("first attempt failed"))
        .thenReturn(replayRegistration);
    when(sessionService.getSessionsForUser(firstUser)).thenReturn(List.of(partialSession));

    PlainCredentialsHolder.set("first", "password");
    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
    PlainCredentialsHolder.set("replay", "password");
    Long replaySessionId =
        createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    assertThat(replaySessionId, is(99L));
    verify(sessionService).deleteSession(partialSession);
    verify(matrixSynapseService).deactivateUser("@first:matrix.example.org");
    verify(matrixSynapseService, never()).deactivateUser("@replay:matrix.example.org");
    verify(userService).deleteUser(firstUser);
    verify(userService, never()).deleteUser(replayUser);
    verify(identityAccountRemover).rollbackUser("first-id");
    verify(identityAccountRemover, never()).rollbackUser("replay-id");
    verify(identityClient, times(2)).createUser(any(UserDTO.class));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_UseDefaultTenantName_When_NoCurrentTenantIsSet()
          throws Exception {
    TenantContext.clear();
    givenBasicRegistrationStubs();
    givenAFullyPersistedUser();
    ArgumentCaptor<RegistrationStatisticsEvent> eventCaptor =
        ArgumentCaptor.forClass(RegistrationStatisticsEvent.class);

    var withoutCollectedEmail = new UserDTO();
    org.springframework.beans.BeanUtils.copyProperties(USER_DTO_SUCHT, withoutCollectedEmail);
    withoutCollectedEmail.setEmail(null);
    createUserFacade.createUserAccountWithInitializedConsultingType(withoutCollectedEmail);

    verify(tenantService, never()).getRestrictedTenantData(any(Long.class));
    verify(statisticsService).fireEvent(eventCaptor.capture());
    assertThat(
        eventCaptor.getValue().getPayload().orElse(""),
        containsString("\"tenantName\":\"Default Tenant\""));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_UseDefaultTenantName_When_TenantServiceReturnsNull()
          throws Exception {
    TenantContext.setCurrentTenant(1L);
    try {
      givenBasicRegistrationStubs();
      givenAFullyPersistedUser();
      when(tenantService.getRestrictedTenantData((Long) 1L)).thenReturn(null);
      ArgumentCaptor<RegistrationStatisticsEvent> eventCaptor =
          ArgumentCaptor.forClass(RegistrationStatisticsEvent.class);

      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

      verify(tenantService, times(1)).getRestrictedTenantData((Long) 1L);
      verify(statisticsService).fireEvent(eventCaptor.capture());
      assertThat(
          eventCaptor.getValue().getPayload().orElse(""),
          containsString("\"tenantName\":\"Default Tenant\""));
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_RegistrationIsAnonymous() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID,
            USER_DTO_SUCHT,
            RegistrationType.ANONYMOUS,
            new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
    verify(userService, times(1)).saveUser(any());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_AbortBeforeDatabaseWrite_When_UserIdIsNullAndRoleIsUser() {

    assertThrows(
        InternalServerErrorException.class,
        () ->
            createUserFacade.updateIdentityAndCreateAccount(
                null,
                USER_DTO_SUCHT,
                RegistrationType.REGISTERED,
                new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3)));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
  }

  @Test
  void updateIdentityAndCreateAccount_Should_CallUpdateDummyEmail_When_EmailIsBlank() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(identityClient).updateRole(anyString(), any(UserRole.class));
    doNothing()
        .when(identityPasswordUpdater)
        .updatePassword(anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
    when(identityDummyEmailUpdater.updateDummyEmail(
            anyString(), any(IdentityDummyEmailUpdate.class)))
        .thenReturn("dummy@example.com");
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(new User());
    UserDTO userDtoWithBlankEmail =
        UserDTO.builder()
            .email("")
            .username(USER_DTO_SUCHT.getUsername())
            .postcode(USER_DTO_SUCHT.getPostcode())
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    createUserFacade.updateIdentityAndCreateAccount(
        USER_ID,
        userDtoWithBlankEmail,
        RegistrationType.REGISTERED,
        new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));

    verify(identityDummyEmailUpdater, times(1))
        .updateDummyEmail(eq(USER_ID), any(IdentityDummyEmailUpdate.class));
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_PostcodeIsAnonymousPlaceholder() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);
    UserDTO anonymousPostcodeDto =
        UserDTO.builder()
            .email(USER_DTO_SUCHT.getEmail())
            .username("regularUsername")
            .postcode("00000")
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID,
            anonymousPostcodeDto,
            RegistrationType.REGISTERED,
            new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
    verify(userService)
        .createUser(
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            eq(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3)));

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_KeepPrivacyConfirmations_When_OnlyTheUsernameLooksAnonymous() {
    // ORISO-UserService#1697: the User-ID text does not decide; the regular postcode does.
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    UserDTO anonymousUsernameDto =
        UserDTO.builder()
            .email(USER_DTO_SUCHT.getEmail())
            .username("Anonymous-1234")
            .postcode(USER_DTO_SUCHT.getPostcode())
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID,
            anonymousUsernameDto,
            RegistrationType.REGISTERED,
            new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));

    assertThat(result.getTermsAndConditionsConfirmation(), notNullValue());
    assertThat(result.getDataPrivacyConfirmation(), notNullValue());
    verify(userService, never()).saveUser(any());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_AnimalGuestRegistersWithLiveChatPostcode() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);
    UserDTO anonymousUsernameDto =
        UserDTO.builder()
            .email(USER_DTO_SUCHT.getEmail())
            .username("katze_sam_4821")
            .postcode("00000")
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID,
            anonymousUsernameDto,
            RegistrationType.REGISTERED,
            new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));

    verify(userService)
        .createUser(
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            eq(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3)));

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
  }

  // ---------------------------------------------------------------------------
  // Live-chat guest through the same entry (ADR-038, #1745)
  // ---------------------------------------------------------------------------

  private static final String GUEST_HANDLE = "katze_sam_4821";
  private static final String GUEST_USERNAME =
      new de.caritas.cob.userservice.api.helper.UsernameTranscoder().encodeUsername(GUEST_HANDLE);
  private static final de.caritas.cob.userservice.api.port.out.IdentityLogin GUEST_LOGIN =
      new de.caritas.cob.userservice.api.port.out.IdentityLogin("access", 300, 1800, "refresh");

  private UserDTO guestRegistration() {
    return UserDTO.builder()
        .consultingType("1")
        .username(GUEST_USERNAME)
        .password("generated-password")
        .postcode("00000")
        .termsAccepted("true")
        .mainTopicId(11L)
        .build();
  }

  private User givenAGuestCanBeProvisioned() throws Exception {
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    User user = givenAFullyPersistedUser();
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@" + GUEST_HANDLE + ":matrix.example.org");
    lenient()
        .when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(matrixResponseBody));
    lenient().when(matrixSynapseService.deactivateUser(anyString())).thenReturn(true);
    return user;
  }

  @Test
  void aGuestIsCreatedThroughTheRegistrationEntryAndSignedIn() throws Exception {
    User user = givenAGuestCanBeProvisioned();
    var waiting = new Session();
    waiting.setId(77L);
    var guest = guestRegistration();
    when(identityAuthentication.login(GUEST_USERNAME, "generated-password"))
        .thenReturn(GUEST_LOGIN);
    when(anonymousConversationCreatorService.createAnonymousConversation(guest, user))
        .thenReturn(waiting);

    var created =
        createUserFacade.createUserAccountWithInitializedConsultingType(
            guest, RegistrationType.ANONYMOUS);

    assertThat(created.userId(), is(USER_ID));
    assertThat(created.sessionId(), is(77L));
    assertThat(created.login(), is(GUEST_LOGIN));
    // The same role, password and chat identity steps as a Mailberatung registration.
    verify(identityClient).updateRole(USER_ID, UserRole.USER);
    verify(identityPasswordUpdater).updatePassword(USER_ID, "generated-password");
    verify(matrixSynapseService).createUser(eq(GUEST_HANDLE), anyString(), eq(GUEST_HANDLE));
    verify(provisioningCompensator)
        .begin(
            de.caritas.cob.userservice.api.service.provisioning.ProvisioningWorkflow
                .LIVE_CHAT_GUEST);
    assertThat(user.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(user.getDataPrivacyConfirmation(), nullValue());
    // Nothing of a registered counselling request: no agency, enquiry, statistic or welcome mail.
    verify(agencyVerifier, never()).checkIfConsultingTypeMatchesToAgency(any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(statisticsService, never()).fireEvent(any());
    verify(welcomeEmailService, never()).sendWelcomeEmail(any(), any());
    verify(identityAccountRemover, never()).rollbackUser(anyString());
    verify(matrixSynapseService, never()).deactivateUser(anyString());
  }

  @Test
  void aGuestNameComesFromTheRegistrationNotFromLeftoverRequestCredentials() throws Exception {
    User user = givenAGuestCanBeProvisioned();
    var guest = guestRegistration();
    when(identityAuthentication.login(any(), any())).thenReturn(GUEST_LOGIN);
    when(anonymousConversationCreatorService.createAnonymousConversation(guest, user))
        .thenReturn(new Session());
    PlainCredentialsHolder.set("someone-else", "other-password");

    createUserFacade.createUserAccountWithInitializedConsultingType(
        guest, RegistrationType.ANONYMOUS);

    verify(matrixSynapseService).createUser(eq(GUEST_HANDLE), anyString(), eq(GUEST_HANDLE));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void aTypedGuestNameIsRefusedBeforeAnythingIsCreated() {
    // The real name rule, not a stub: a typed name is refused, a rolled handle is accepted.
    var names =
        new de.caritas.cob.userservice.api.service.SessionDataService(
            mock(de.caritas.cob.userservice.api.port.out.SessionDataRepository.class),
            mock(de.caritas.cob.userservice.api.helper.SessionDataProvider.class),
            sessionService,
            mock(de.caritas.cob.userservice.api.helper.AuthenticatedUser.class),
            tenantService,
            new de.caritas.cob.userservice.api.service.GeneratedPseudonyms(),
            mock(de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    org.springframework.test.util.ReflectionTestUtils.setField(
        createUserFacade, "sessionDataService", names);
    var typed = guestRegistration();
    typed.setUsername(
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
            .encodeUsername("max_mustermann"));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class,
        () ->
            createUserFacade.createUserAccountWithInitializedConsultingType(
                typed, RegistrationType.ANONYMOUS));

    verify(identityClient, never()).createUser(any());
    org.mockito.Mockito.verifyNoInteractions(
        matrixSynapseService, identityAuthentication, anonymousConversationCreatorService);
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> names.requireGeneratedUsername(GUEST_USERNAME));
  }

  @Test
  void noHalfCreatedGuestRemainsWhenTheIdentityProviderFails() {
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    doThrow(new RuntimeException("identity provider down"))
        .when(identityPasswordUpdater)
        .updatePassword(anyString(), anyString());

    assertThrows(
        RuntimeException.class,
        () ->
            createUserFacade.createUserAccountWithInitializedConsultingType(
                guestRegistration(), RegistrationType.ANONYMOUS));

    verify(identityAccountRemover).rollbackUser(USER_ID);
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(
        matrixSynapseService, identityAuthentication, anonymousConversationCreatorService);
  }

  @Test
  void noHalfCreatedGuestRemainsWhenTheGuestCannotBeSignedIn() throws Exception {
    User user = givenAGuestCanBeProvisioned();
    when(identityAuthentication.login(any(), any()))
        .thenThrow(new InternalServerErrorException("login refused"));

    assertThrows(
        InternalServerErrorException.class,
        () ->
            createUserFacade.createUserAccountWithInitializedConsultingType(
                guestRegistration(), RegistrationType.ANONYMOUS));

    // No waiting place was opened, and all three stores are cleaned — the chat identity too,
    // which the former guest-only rollback left behind.
    org.mockito.Mockito.verifyNoInteractions(anonymousConversationCreatorService);
    verify(matrixSynapseService).deactivateUser("@" + GUEST_HANDLE + ":matrix.example.org");
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
  }

  @Test
  void noHalfCreatedGuestRemainsWhenTheWaitingPlaceCannotBeOpened() throws Exception {
    User user = givenAGuestCanBeProvisioned();
    var staleSession = new Session();
    when(identityAuthentication.login(any(), any())).thenReturn(GUEST_LOGIN);
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenThrow(new InternalServerErrorException("Could not create the live-chat session"));
    when(sessionService.getSessionsForUser(user)).thenReturn(java.util.List.of(staleSession));

    assertThrows(
        InternalServerErrorException.class,
        () ->
            createUserFacade.createUserAccountWithInitializedConsultingType(
                guestRegistration(), RegistrationType.ANONYMOUS));

    verify(sessionService).deleteSession(staleSession);
    verify(matrixSynapseService).deactivateUser("@" + GUEST_HANDLE + ":matrix.example.org");
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
  }
}
