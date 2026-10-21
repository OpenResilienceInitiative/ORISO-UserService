package de.caritas.cob.userservice.api.facade.conversation;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_SUCHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.conversation.model.AnonymousUserCredentials;
import de.caritas.cob.userservice.api.conversation.service.AnonymousConversationCreatorService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUserCreatorService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUsernameRegistry;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.concurrent.atomic.AtomicReference;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class CreateAnonymousEnquiryFacadeTest {

  private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  @Mock private AnonymousUserCreatorService anonymousUserCreatorService;
  @Mock private AnonymousConversationCreatorService anonymousConversationCreatorService;
  @Mock private AnonymousUsernameRegistry usernameRegistry;
  @Mock private UserHelper userHelper;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Spy private TenantContextProvider tenantContextProvider = new TenantContextProvider();

  @org.junit.jupiter.api.BeforeEach
  void realDpaPolicyFixture() {
    createAnonymousEnquiryFacade =
        new CreateAnonymousEnquiryFacade(
            anonymousUserCreatorService,
            de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.policy(),
            anonymousConversationCreatorService,
            usernameRegistry,
            userHelper,
            consultingTypeManager,
            tenantContextProvider,
            mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class));
  }

  @Test
  void fiveActorAnonymousPathCompletesItsOwnedConversationBeforeLoginAndNeverUsesNativeCreator() {
    var flow =
        mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                .class);
    var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(flow);
    ReflectionTestUtils.setField(
        createAnonymousEnquiryFacade,
        "lifecycleEnvironment",
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS"));
    ReflectionTestUtils.setField(createAnonymousEnquiryFacade, "ordinaryAccountCreation", provider);
    var users = mock(de.caritas.cob.userservice.api.service.user.UserService.class);
    var recovery =
        mock(de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.class);
    var inactivity =
        mock(de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.class);
    var inputs =
        mock(
            de.caritas.cob.userservice.api.admin.service.consultant.validation
                .UserAccountInputValidator.class);
    var login = mock(de.caritas.cob.userservice.api.port.out.IdentityAuthentication.class);
    ReflectionTestUtils.setField(createAnonymousEnquiryFacade, "lifecycleUsers", users);
    ReflectionTestUtils.setField(createAnonymousEnquiryFacade, "lifecycleRecovery", recovery);
    ReflectionTestUtils.setField(createAnonymousEnquiryFacade, "lifecycleInactivity", inactivity);
    ReflectionTestUtils.setField(createAnonymousEnquiryFacade, "lifecycleInputs", inputs);
    ReflectionTestUtils.setField(createAnonymousEnquiryFacade, "lifecycleLogin", login);
    TenantContext.setCurrentTenant(79L);
    String encoded =
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
            .encodeUsername("Anonymous-42");
    when(usernameRegistry.generateUniqueUsername()).thenReturn(encoded);
    when(userHelper.getRandomPassword()).thenReturn("synthetic-chosen-password");
    var policy =
        new de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy(
            24, 3, java.time.Instant.now());
    when(inactivity.capture(
            79L,
            de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group.ASKER))
        .thenReturn(policy);
    var settings = new ExtendedConsultingTypeResponseDTO().languageFormal(true);
    when(consultingTypeManager.getConsultingTypeSettings(String.valueOf(CONSULTING_TYPE_ID_SUCHT)))
        .thenReturn(settings);
    var scope =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
            .CreationScope(
            "79",
            "ANONYMOUS",
            "/human-roles/user",
            new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
                .Origin(
                "ANONYMOUS",
                "anonymous:one",
                "anonymous:consulting",
                null,
                null,
                null,
                java.util.List.of()));
    when(flow.anonymousScope(any(), org.mockito.ArgumentMatchers.eq(79L))).thenReturn(scope);
    var pending =
        mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                .Pending.class);
    when(pending.accountId()).thenReturn("owned-account");
    when(pending.projection())
        .thenReturn(
            new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
                .AccountProjection(
                "owned-account",
                "anonymous-42",
                "owned@example.test",
                null,
                null,
                "79",
                "de",
                false,
                false,
                java.util.List.of(),
                false));
    var user =
        new de.caritas.cob.userservice.api.model.User(
            "owned-account", null, encoded, "owned@example.test", true);
    when(users.createNewLifecycleUser(
            any(),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            any(),
            any(),
            org.mockito.ArgumentMatchers.eq(policy)))
        .thenReturn(user);
    when(flow.createChatAccount(org.mockito.ArgumentMatchers.eq(pending), any(), any()))
        .thenReturn("@anonymous-42:example.test");
    Session ownedSession = new Session();
    ownedSession.setId(42L);
    when(anonymousConversationCreatorService.createOwnedAnonymousConversation(any(), any()))
        .thenReturn(ownedSession);
    var localFinished = new java.util.concurrent.atomic.AtomicBoolean();
    when(flow.create(org.mockito.ArgumentMatchers.eq(scope), any(), any()))
        .thenAnswer(
            call -> {
              java.util.function.Function<
                      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle
                          .OrdinaryAccountCreation.Pending,
                      Session>
                  writes = call.getArgument(2);
              var result = writes.apply(pending);
              localFinished.set(true);
              return result;
            });
    when(login.login(encoded, "synthetic-chosen-password"))
        .thenAnswer(
            call -> {
              org.junit.jupiter.api.Assertions.assertTrue(localFinished.get());
              return new de.caritas.cob.userservice.api.port.out.IdentityLogin(
                  "synthetic-user-token", 300, 600, "synthetic-refresh");
            });
    var result =
        createAnonymousEnquiryFacade.createAnonymousEnquiry(
            new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT), true);
    assertEquals(42L, result.getSessionId());
    assertEquals("synthetic-user-token", result.getAccessToken());
    verifyNoInteractions(anonymousUserCreatorService);
    verify(anonymousConversationCreatorService, org.mockito.Mockito.never())
        .createAnonymousConversation(any(), any());
    verify(inputs).validateUserDTO(any());
  }

  EasyRandom easyRandom = new EasyRandom();

  @Test
  void pausedTenantStopsAnonymousCreationBeforeIdentityRowsUsernameOrConversationWork() {
    ReflectionTestUtils.setField(
        createAnonymousEnquiryFacade,
        "tenantService",
        de.caritas.cob.userservice.api.testHelper.PausedTenantAuthorityFixture.paused(79L));
    TenantContext.setCurrentTenant(79L);
    var refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                createAnonymousEnquiryFacade.createAnonymousEnquiry(
                    new CreateAnonymousEnquiryDTO(1)));
    org.junit.jupiter.api.Assertions.assertEquals(
        org.springframework.http.HttpStatus.FORBIDDEN, refused.getStatusCode());
    org.mockito.Mockito.verifyNoInteractions(
        anonymousUserCreatorService,
        anonymousConversationCreatorService,
        usernameRegistry,
        userHelper,
        consultingTypeManager);
  }

  @Test
  void createAnonymousEnquiry_Should_ReturnMatrixOnlyResponse() {
    CreateAnonymousEnquiryDTO request = new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT);
    AnonymousUserCredentials credentials =
        AnonymousUserCredentials.builder()
            .userId("user-id")
            .accessToken("access-token")
            .refreshToken("refresh-token")
            .expiresIn(300)
            .refreshExpiresIn(600)
            .build();
    Session session = easyRandom.nextObject(Session.class);
    session.setMatrixRoomId(null);
    when(anonymousUserCreatorService.createAnonymousUser(any())).thenReturn(credentials);
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(session);

    var response = createAnonymousEnquiryFacade.createAnonymousEnquiry(request, true);

    assertEquals("access-token", response.getAccessToken());
    assertEquals(session.getId(), response.getSessionId());
  }

  @Test
  public void
      createAnonymousEnquiry_Should_ThrowBadRequestException_When_GivenConsultingTypeDoesNotSupportAnonymousConversations() {
    assertThrows(
        BadRequestException.class,
        () -> {
          CreateAnonymousEnquiryDTO anonymousEnquiryDTO =
              easyRandom.nextObject(CreateAnonymousEnquiryDTO.class);
          anonymousEnquiryDTO.setConsultingType(CONSULTING_TYPE_ID_KREUZBUND);
          var consultingTypeResponseDTO =
              easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
          consultingTypeResponseDTO.setIsAnonymousConversationAllowed(false);
          when(consultingTypeManager.getConsultingTypeSettings(
                  anonymousEnquiryDTO.getConsultingType()))
              .thenReturn(consultingTypeResponseDTO);

          createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiryDTO);

          verifyNoInteractions(anonymousUserCreatorService);
          verifyNoInteractions(anonymousConversationCreatorService);
          verifyNoInteractions(usernameRegistry);
          verifyNoInteractions(userHelper);
        });
  }

  @Test
  public void createAnonymousEnquiry_Should_ReturnValidCreateAnonymousEnquiryResponseDTO() {
    CreateAnonymousEnquiryDTO anonymousEnquiryDTO =
        easyRandom.nextObject(CreateAnonymousEnquiryDTO.class);
    anonymousEnquiryDTO.setConsultingType(CONSULTING_TYPE_ID_SUCHT);
    AnonymousUserCredentials credentials = easyRandom.nextObject(AnonymousUserCredentials.class);
    when(anonymousUserCreatorService.createAnonymousUser(any())).thenReturn(credentials);
    Session session = easyRandom.nextObject(Session.class);
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(session);
    var consultingTypeResponseDTO = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    consultingTypeResponseDTO.setIsAnonymousConversationAllowed(true);
    when(consultingTypeManager.getConsultingTypeSettings(anonymousEnquiryDTO.getConsultingType()))
        .thenReturn(consultingTypeResponseDTO);

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiryDTO);

    verify(anonymousUserCreatorService, times(1)).createAnonymousUser(any());
    verify(anonymousConversationCreatorService, times(1)).createAnonymousConversation(any(), any());
    verify(consultingTypeManager, times(1))
        .getConsultingTypeSettings(anonymousEnquiryDTO.getConsultingType());
  }

  // --- which tenant the enquiry is written in ---------------------------------------------------

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createAnonymousEnquiry_Should_WriteNothing_When_MultiTenantRouteHasNoTenant() {
    ReflectionTestUtils.setField(tenantContextProvider, "multiTenancyEnabled", true);
    TenantContext.clear();

    assertThrows(
        ForbiddenException.class,
        () ->
            createAnonymousEnquiryFacade.createAnonymousEnquiry(
                new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT)));

    verifyNoInteractions(anonymousUserCreatorService, anonymousConversationCreatorService);
  }

  @Test
  void createAnonymousEnquiry_Should_WriteInTheCallersTenant_When_OneIsSet() {
    ReflectionTestUtils.setField(tenantContextProvider, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(5L);
    var writtenIn = givenCreationRecordsItsTenant();

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiry());

    assertEquals(5L, writtenIn.get());
  }

  @Test
  void createAnonymousEnquiry_Should_Proceed_When_SingleTenantDeploymentHasNoTenant() {
    TenantContext.clear();
    var writtenIn = givenCreationRecordsItsTenant();

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiry());

    assertNull(writtenIn.get());
    verify(anonymousUserCreatorService).createAnonymousUser(any());
  }

  private CreateAnonymousEnquiryDTO anonymousEnquiry() {
    var dto = new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT);
    var settings = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    settings.setIsAnonymousConversationAllowed(true);
    when(consultingTypeManager.getConsultingTypeSettings(CONSULTING_TYPE_ID_SUCHT))
        .thenReturn(settings);
    return dto;
  }

  private AtomicReference<Long> givenCreationRecordsItsTenant() {
    var writtenIn = new AtomicReference<Long>();
    when(anonymousUserCreatorService.createAnonymousUser(any()))
        .thenAnswer(
            call -> {
              writtenIn.set(TenantContext.getCurrentTenant());
              return easyRandom.nextObject(AnonymousUserCredentials.class);
            });
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(easyRandom.nextObject(Session.class));
    return writtenIn;
  }
}
