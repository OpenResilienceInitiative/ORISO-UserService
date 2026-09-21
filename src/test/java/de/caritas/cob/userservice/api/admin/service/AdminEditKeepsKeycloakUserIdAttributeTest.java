package de.caritas.cob.userservice.api.admin.service;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Editing an admin or consultant in the Admin UI goes {@code UpdateAdminService} / {@code
 * ConsultantUpdateService} → real {@link KeycloakService} → Keycloak's user update. Keycloak
 * replaces the whole attribute map on that call, so the representation the services send must still
 * carry the {@code userId} attribute — it feeds the custom {@code userId} JWT claim the
 * AgencyService uses to scope a Beratungsstellen-Admin to their own agencies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminEditKeepsKeycloakUserIdAttributeTest {

  private static final String ADMIN_ID = "8ed43c2c-1f51-4169-a7d9-c75de7eaf830";
  private static final String ENCODED_USERNAME = "enc.nbswy3dp"; // base32("hello")

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private UserAccountInputValidator userAccountInputValidator;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private KeycloakClient keycloakClient;
  @Mock private KeycloakMapper keycloakMapper;
  @Mock private UserHelper userHelper;
  @Mock private KeycloakAuthClient keycloakAuthClient;
  @Mock private UsersResource usersResource;
  @Mock private UserResource userResource;

  @Mock private AdminRepository adminRepository;
  @Mock private RetrieveAdminService retrieveAdminService;

  @Mock private ConsultantService consultantService;
  @Mock private ConsultantPublicSlugService consultantPublicSlugService;
  @Mock private MatrixUserClient matrixUserClient;
  @Mock private AppointmentService appointmentService;
  @Mock private SessionRepository sessionRepository;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private ConsultantTopicAgencyCompatibilityValidator topicAgencyCompatibilityValidator;

  private KeycloakService keycloakService;
  private UpdateAdminService updateAdminService;
  private ConsultantUpdateService consultantUpdateService;

  @BeforeEach
  void setUp() {
    keycloakService =
        new KeycloakService(
            authenticatedUser,
            userAccountInputValidator,
            identityClientConfig,
            keycloakClient,
            keycloakMapper,
            userHelper,
            keycloakAuthClient);
    setField(keycloakService, "multiTenancyEnabled", true);
    setField(keycloakService, "genericKeycloakError", "keycloak error");

    updateAdminService =
        new UpdateAdminService(
            keycloakService, userAccountInputValidator, adminRepository, retrieveAdminService);
    consultantUpdateService =
        new ConsultantUpdateService(
            keycloakService,
            keycloakService,
            consultantService,
            consultantPublicSlugService,
            userAccountInputValidator,
            matrixUserClient,
            appointmentService,
            sessionRepository,
            eventNotificationService,
            topicAgencyCompatibilityValidator,
            mock(AccountInviteRepository.class));

    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(usersResource.get(ADMIN_ID)).thenReturn(userResource);
    when(usersResource.search(any(), any(), any())).thenReturn(List.of());
  }

  private void givenKeycloakHoldsUserWithIdentityAttributes(String email) {
    var stored = new UserRepresentation();
    stored.setId(ADMIN_ID);
    stored.setUsername("hello");
    stored.setEmail(email);
    stored.setAttributes(
        new LinkedHashMap<>(
            Map.of(
                "userId", singletonList(ADMIN_ID),
                "locale", singletonList("de"),
                "tenantId", singletonList("2"),
                "username", singletonList("hello"),
                "userName", singletonList("hello"))));
    when(userResource.toRepresentation()).thenReturn(stored);
  }

  private UserRepresentation representationSentToKeycloak() {
    var captor = ArgumentCaptor.forClass(UserRepresentation.class);
    verify(userResource).update(captor.capture());
    return captor.getValue();
  }

  private Admin storedAdmin(Long tenantId) {
    return Admin.builder()
        .id(ADMIN_ID)
        .username(ENCODED_USERNAME)
        .tenantId(tenantId)
        .email("old@example.org")
        .firstName("Old")
        .lastName("Name")
        .build();
  }

  @Test
  void updateAgencyAdmin_Should_sendUserIdAndLocaleAlongWithTheNewProfile() {
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("new@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.getFirstName(), is("New"));
    assertThat(sent.getEmail(), is("new@example.org"));
    assertThat(sent.getUsername(), is("hello"));
    assertThat(sent.getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
    assertThat(sent.getAttributes().get("locale"), is(singletonList("de")));
    assertThat(sent.getAttributes().get("tenantId"), is(singletonList("2")));
    assertThat(sent.getAttributes().get("username"), is(singletonList("hello")));
  }

  @Test
  void updateAgencyAdmin_Should_keepUserId_When_adminHasNoTenant() {
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(null));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
    assertThat(sent.getAttributes().get("tenantId"), is(singletonList("2")));
  }

  @Test
  void patchAgencyAdmin_Should_sendUserIdAlongWithTheNewProfile() {
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var patch = new PatchAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.patchAgencyAdmin(ADMIN_ID, patch);

    var sent = representationSentToKeycloak();
    assertThat(sent.getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
    assertThat(sent.getAttributes().get("locale"), is(singletonList("de")));
  }

  @Test
  void updateTenantAdmin_Should_sendUserIdAndMovedTenantId() {
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.TENANT))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateTenantAdminDTO()
            .firstname("New")
            .lastname("Name")
            .email("old@example.org")
            .tenantId(5);

    updateAdminService.updateTenantAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
    assertThat(sent.getAttributes().get("tenantId"), is(singletonList("5")));
  }

  @Test
  void patchTenantAdmin_Should_sendUserIdAlongWithTheNewProfile() {
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.TENANT))
        .thenReturn(storedAdmin(2L));
    var patch = new PatchAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.patchTenantAdmin(ADMIN_ID, patch);

    assertThat(
        representationSentToKeycloak().getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
  }

  @Test
  void updateAgencyAdmin_Should_keepUserId_When_multiTenancyIsDisabled() {
    setField(keycloakService, "multiTenancyEnabled", false);
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
    assertThat(sent.getAttributes().get("tenantId"), is(singletonList("2")));
  }

  @Test
  void updateConsultant_Should_sendUserIdAlongWithTheNewProfile() {
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setId(ADMIN_ID);
    consultant.setUsername(ENCODED_USERNAME);
    consultant.setTenantId(2L);
    consultant.setFirstName("Old");
    consultant.setLastName("Name");
    consultant.setEmail("old@example.org");
    consultant.setMatrixUserId(null);
    when(consultantService.getConsultant(ADMIN_ID)).thenReturn(Optional.of(consultant));
    when(consultantService.saveConsultant(any())).thenReturn(consultant);
    when(sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of());
    var update =
        new UpdateAdminConsultantDTO()
            .firstname("New")
            .lastname("Name")
            .email("old@example.org")
            .absent(false)
            .formalLanguage(false)
            .languages(List.of("de"))
            .topicIds(List.of());

    consultantUpdateService.updateConsultant(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.getFirstName(), is("New"));
    assertThat(sent.getUsername(), is("hello"));
    assertThat(sent.getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
    assertThat(sent.getAttributes().get("locale"), is(singletonList("de")));
    assertThat(sent.getAttributes().get("tenantId"), is(singletonList("2")));
  }

  @Test
  void updateConsultant_Should_keepUserId_When_multiTenancyIsDisabled() {
    setField(keycloakService, "multiTenancyEnabled", false);
    givenKeycloakHoldsUserWithIdentityAttributes("old@example.org");
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setId(ADMIN_ID);
    consultant.setUsername("plainname");
    consultant.setTenantId(null);
    consultant.setFirstName("Old");
    consultant.setLastName("Name");
    consultant.setEmail("old@example.org");
    consultant.setMatrixUserId(null);
    when(consultantService.getConsultant(ADMIN_ID)).thenReturn(Optional.of(consultant));
    when(consultantService.saveConsultant(any())).thenReturn(consultant);
    when(sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of());
    var update =
        new UpdateAdminConsultantDTO()
            .firstname("New")
            .lastname("Name")
            .email("new@example.org")
            .absent(false)
            .formalLanguage(false)
            .languages(List.of("de"))
            .topicIds(List.of());

    consultantUpdateService.updateConsultant(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.getEmail(), is("new@example.org"));
    assertThat(sent.getUsername(), is("plainname"));
    assertThat(sent.getAttributes().get("userId"), is(singletonList(ADMIN_ID)));
    assertThat(sent.getAttributes().get("tenantId"), is(singletonList("2")));
    assertThat(sent.getAttributes().get("username"), is(singletonList("plainname")));
  }

  @Test
  void updateConsultant_Should_notTouchKeycloak_When_identityDataIsUnchanged() {
    // guards the caller contract: no update call means nothing can be wiped
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setId(ADMIN_ID);
    consultant.setFirstName("Same");
    consultant.setLastName("Name");
    consultant.setEmail("same@example.org");
    consultant.setAbsent(false);
    consultant.setMatrixUserId(null);
    when(consultantService.getConsultant(ADMIN_ID)).thenReturn(Optional.of(consultant));
    when(consultantService.saveConsultant(any())).thenReturn(consultant);
    var update =
        new UpdateAdminConsultantDTO()
            .firstname("Same")
            .lastname("Name")
            .email("same@example.org")
            .absent(false)
            .formalLanguage(false)
            .languages(List.of("de"))
            .topicIds(List.of());

    consultantUpdateService.updateConsultant(ADMIN_ID, update);

    verify(userResource, org.mockito.Mockito.never()).update(any());
  }

  @Test
  void updateAgencyAdmin_Should_notRelyOnAnyStoredAttribute_When_keycloakHoldsNone() {
    var stored = new UserRepresentation();
    stored.setId(ADMIN_ID);
    stored.setEmail("old@example.org");
    when(userResource.toRepresentation()).thenReturn(stored);
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.getAttributes().get("username"), is(singletonList("hello")));
    assertThat(sent.getAttributes().get("tenantId"), is(singletonList("2")));
    assertThat(sent.getAttributes().containsKey("userId"), is(false));
  }
}
