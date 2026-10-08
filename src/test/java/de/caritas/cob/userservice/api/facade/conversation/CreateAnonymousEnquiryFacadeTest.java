package de.caritas.cob.userservice.api.facade.conversation;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_SUCHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCreationLocalCompletion
      localCompletion;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityAccountProvisioning
      identityProvisioning;

  @Mock private de.caritas.cob.userservice.api.facade.rollback.RollbackFacade rollbackFacade;
  @Mock private AnonymousUserCreatorService anonymousUserCreatorService;
  @Mock private AnonymousConversationCreatorService anonymousConversationCreatorService;
  @Mock private AnonymousUsernameRegistry usernameRegistry;
  @Mock private UserHelper userHelper;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Spy private TenantContextProvider tenantContextProvider = new TenantContextProvider();

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityAnonymousBootstrapFailure
      bootstrapFailure;

  @org.junit.jupiter.api.BeforeEach
  void realDpaPolicyFixture() {
    org.mockito.Mockito.lenient()
        .when(usernameRegistry.generateUniqueUsername())
        .thenReturn("Anonymous-test");
    org.mockito.Mockito.lenient()
        .when(userHelper.getRandomPassword())
        .thenReturn("fixture-anonymous-password");
    org.mockito.Mockito.lenient()
        .when(anonymousUserCreatorService.authenticateCreatedUser(any(), any()))
        .thenAnswer(call -> call.getArgument(1));
    createAnonymousEnquiryFacade =
        new CreateAnonymousEnquiryFacade(
            localCompletion,
            identityProvisioning,
            new de.caritas.cob.userservice.api.adapters.keycloak.commands
                .IdentityCreationLocalTransactions(),
            bootstrapFailure,
            rollbackFacade,
            anonymousUserCreatorService,
            de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.policy(),
            anonymousConversationCreatorService,
            usernameRegistry,
            userHelper,
            consultingTypeManager,
            tenantContextProvider);
  }

  @Test
  void failedDurableCompletionCleansOnlyItsCreatedSessionAndUser() {
    var credentials = AnonymousUserCredentials.builder().userId("new-user").build();
    var session = new Session();
    var user = new de.caritas.cob.userservice.api.model.User();
    user.setUserId("new-user");
    session.setUser(user);
    when(anonymousUserCreatorService.createAnonymousUser(any(), any())).thenReturn(credentials);
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(session);
    org.mockito.Mockito.doThrow(new IllegalStateException("journal unavailable"))
        .when(localCompletion)
        .anonymousSession(org.mockito.ArgumentMatchers.eq("new-user"), any());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                createAnonymousEnquiryFacade.createAnonymousEnquiry(
                    new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("journal unavailable");
    org.mockito.Mockito.verify(rollbackFacade)
        .rollBackUserAccount(
            org.mockito.ArgumentMatchers.argThat(
                rollback ->
                    rollback.getSession() == session
                        && rollback.getUser() == user
                        && "new-user".equals(rollback.getUserId())));
  }

  EasyRandom easyRandom = new EasyRandom();

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
    when(anonymousUserCreatorService.createAnonymousUser(any(), any())).thenReturn(credentials);
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
    when(anonymousUserCreatorService.createAnonymousUser(any(), any())).thenReturn(credentials);
    Session session = easyRandom.nextObject(Session.class);
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(session);
    var consultingTypeResponseDTO = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    consultingTypeResponseDTO.setIsAnonymousConversationAllowed(true);
    when(consultingTypeManager.getConsultingTypeSettings(anonymousEnquiryDTO.getConsultingType()))
        .thenReturn(consultingTypeResponseDTO);

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiryDTO);

    verify(anonymousUserCreatorService, times(1)).createAnonymousUser(any(), any());
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
    verify(anonymousUserCreatorService).createAnonymousUser(any(), any());
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
    when(anonymousUserCreatorService.createAnonymousUser(any(), any()))
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
