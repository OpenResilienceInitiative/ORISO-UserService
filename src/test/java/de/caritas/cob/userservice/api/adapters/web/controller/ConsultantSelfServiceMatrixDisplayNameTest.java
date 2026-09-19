package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.facade.userdata.AgencyAdminDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.MatrixRealNameGuard;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.IdentityPolicy;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ADR-002 §2 / ORISO-UserService#1200 for the SELF-SERVICE entry point.
 *
 * <p>{@code PUT /users/consultants} funnels into the same {@link ConsultantUpdateService} as the
 * admin path, but it is a separate entry point with its own DTO and its own mapping — so the
 * invariant is asserted through the real delegate, the real mapper and the real update service,
 * with only the outbound ports mocked. A counsellor renaming themselves must not publish their real
 * name to a Matrix profile that every room member can read.
 */
class ConsultantSelfServiceMatrixDisplayNameTest {

  private static final String CONSULTANT_ID = "counsellor-1";
  private static final String MATRIX_USER_ID = "@beraterin1:matrix.oriso.org";

  private final MatrixUserClient matrixUserClient = mock(MatrixUserClient.class);
  private final ConsultantService consultantService = mock(ConsultantService.class);
  private final AuthenticatedUser authenticatedUser = mock(AuthenticatedUser.class);

  private UserAccountControllerDelegate delegate;

  @BeforeEach
  void setUp() {
    var updateService =
        new ConsultantUpdateService(
            mock(IdentityClient.class),
            mock(IdentityProfileUpdater.class),
            consultantService,
            mock(ConsultantPublicSlugService.class),
            mock(UserAccountInputValidator.class),
            matrixUserClient,
            mock(AppointmentService.class),
            mock(SessionRepository.class),
            mock(EventNotificationService.class),
            mock(ConsultantTopicAgencyCompatibilityValidator.class),
            new ConsultantDisplayNameResolver());

    delegate =
        new UserAccountControllerDelegate(
            mock(UserAccountService.class),
            authenticatedUser,
            mock(DecryptionService.class),
            mock(ConsultantDataFacade.class),
            mock(IdentityPolicy.class),
            mock(IdentityManaging.class),
            mock(AccountManaging.class),
            mock(Messaging.class),
            new ConsultantDtoMapper(),
            mock(UserDtoMapper.class),
            consultantService,
            updateService,
            mock(ConsultantDataProvider.class),
            mock(AskerDataProvider.class),
            mock(VideoChatConfig.class),
            mock(KeycloakUserDataProvider.class),
            mock(AgencyAdminDataProvider.class),
            new UsernameTranscoder());
  }

  @Test
  @DisplayName("self-service rename publishes the pseudonym, never the real name")
  void updateConsultantData_Should_neverSendTheRealNameToMatrix() {
    givenStoredConsultant("Frau M.");

    delegate.updateConsultantData(selfServiceRename("Angela", "Musterfrau"));

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(matrixUserClient).updateUserDisplayName(eq(MATRIX_USER_ID), displayName.capture());
    MatrixRealNameGuard.assertNoRealNameReachedMatrix(matrixUserClient, "Angela", "Musterfrau");
    assertThat(displayName.getValue()).isEqualTo("Frau M.");
  }

  @Test
  @DisplayName("without a public display name the self-service path falls back to the username")
  void updateConsultantData_Should_fallBackToTheUsername_When_noDisplayNameIsSet() {
    givenStoredConsultant(null);

    delegate.updateConsultantData(selfServiceRename("Angela", "Musterfrau"));

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(matrixUserClient).updateUserDisplayName(eq(MATRIX_USER_ID), displayName.capture());
    MatrixRealNameGuard.assertNoRealNameReachedMatrix(matrixUserClient, "Angela", "Musterfrau");
    assertThat(displayName.getValue()).isEqualTo("beraterin1");
  }

  @Test
  @DisplayName("a Matrix outage does not fail the counsellor's own profile update")
  void updateConsultantData_Should_stillPersist_When_matrixIsDown() {
    var consultant = givenStoredConsultant("Frau M.");
    when(matrixUserClient.updateUserDisplayName(anyString(), anyString()))
        .thenThrow(new RuntimeException("synapse down"));

    var response = delegate.updateConsultantData(selfServiceRename("Angela", "Musterfrau"));

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(consultant.getFirstName()).isEqualTo("Angela");
    verify(consultantService).saveConsultant(any(Consultant.class));
  }

  private Consultant givenStoredConsultant(String publicDisplayName) {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setUsername("beraterin1");
    consultant.setDisplayName(publicDisplayName);
    consultant.setMatrixUserId(MATRIX_USER_ID);
    consultant.setFirstName("Old");
    consultant.setLastName("Name");
    consultant.setEmail("old@address.de");
    consultant.setTenantId(1L);

    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    when(consultantService.getConsultant(CONSULTANT_ID)).thenReturn(Optional.of(consultant));
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return consultant;
  }

  private UpdateConsultantDTO selfServiceRename(String firstname, String lastname) {
    return new UpdateConsultantDTO()
        .firstname(firstname)
        .lastname(lastname)
        .email("old@address.de");
  }
}
