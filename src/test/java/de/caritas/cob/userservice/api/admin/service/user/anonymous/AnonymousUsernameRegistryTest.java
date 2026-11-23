package de.caritas.cob.userservice.api.admin.service.user.anonymous;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUsernameRegistry;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.GeneratedPseudonyms;
import de.caritas.cob.userservice.api.service.identity.GuestIdentityCatalog;
import de.caritas.cob.userservice.api.service.identity.GuestIdentitySuggestion;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Anonymous guests get an animal User-ID from the one global identity catalog, never a counter name
 * such as {@code anon_7} (ORISO-Frontend#2346). The namespace is global: a name is free only when
 * the database (across tenants), Keycloak and Matrix all agree.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class AnonymousUsernameRegistryTest {

  private static final String CHOSEN = "katze_sam_4821";

  @Mock private UserService userService;
  @Mock private ConsultantService consultantService;
  @Mock private IdentityUsernameAvailability identityUsernameAvailability;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private GuestIdentityCatalog guestIdentityCatalog;

  private final UsernameTranscoder transcoder = new UsernameTranscoder();
  private AnonymousUsernameRegistry registry;

  @BeforeEach
  void setUp() {
    registry =
        new AnonymousUsernameRegistry(
            userService,
            consultantService,
            identityUsernameAvailability,
            matrixSynapseService,
            guestIdentityCatalog,
            new GeneratedPseudonyms());
    when(identityUsernameAvailability.isUsernameAvailable(anyString())).thenReturn(true);
    when(userService.findUserByUsername(any())).thenReturn(Optional.empty());
    when(consultantService.getConsultantByUsername(any())).thenReturn(Optional.empty());
    when(guestIdentityCatalog.next(any()))
        .thenReturn(suggestion("eule_robin_1111"), suggestion("biene_noa_2222"));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void generateUniqueUsername_Should_ReturnAnAnimalHandle_NeverACounterName() {
    var username = transcoder.decodeUsername(registry.generateUniqueUsername());

    assertThat(username).isEqualTo("eule_robin_1111").doesNotStartWith("anon_");
  }

  @Test
  void generateUniqueUsername_Should_SkipAHandleTakenInAnotherTenant() {
    TenantContext.setCurrentTenant(83L);
    // Simulates the Hibernate tenant filter: the first handle is visible only cross-tenant.
    when(userService.findUserByUsername(any()))
        .thenAnswer(
            call ->
                TenantContext.isTechnicalOrSuperAdminContext()
                        && "eule_robin_1111".equals(call.getArgument(0))
                    ? Optional.of(USER)
                    : Optional.empty());

    var username = transcoder.decodeUsername(registry.generateUniqueUsername());

    assertThat(username).isEqualTo("biene_noa_2222");
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(83L);
  }

  @Test
  void generateUniqueUsername_Should_CheckAcrossTenants_AndRestoreTheCallersTenantData() {
    var callers = new TenantData(null, "synthetic");
    TenantContext.setCurrentTenantData(callers);
    var lookedUpIn = new AtomicReference<Long>();
    when(userService.findUserByUsername(any()))
        .thenAnswer(
            call -> {
              lookedUpIn.set(TenantContext.getCurrentTenant());
              return Optional.empty();
            });

    registry.generateUniqueUsername();

    assertThat(lookedUpIn.get()).isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
    assertThat(TenantContext.getCurrentTenantData()).isSameAs(callers);
  }

  @Test
  void generateUniqueUsername_Should_SkipAHandleStillPresentInKeycloak() {
    when(identityUsernameAvailability.isUsernameAvailable("eule_robin_1111")).thenReturn(false);

    assertThat(transcoder.decodeUsername(registry.generateUniqueUsername()))
        .isEqualTo("biene_noa_2222");
  }

  @Test
  void generateUniqueUsername_Should_SkipAHandleOwnedByAConsultant() {
    when(consultantService.getConsultantByUsername("eule_robin_1111"))
        .thenReturn(Optional.of(CONSULTANT));

    assertThat(transcoder.decodeUsername(registry.generateUniqueUsername()))
        .isEqualTo("biene_noa_2222");
  }

  @Test
  void generateUniqueUsername_Should_SkipAHandleOrphanedInMatrix_InEitherLocalpartForm() {
    when(matrixSynapseService.userExists(transcoder.encodeUsername("eule_robin_1111")))
        .thenReturn(true);

    assertThat(transcoder.decodeUsername(registry.generateUniqueUsername()))
        .isEqualTo("biene_noa_2222");
  }

  @Test
  void generateUniqueUsername_Should_FailRetryably_When_NoFreeHandleIsFound() {
    when(identityUsernameAvailability.isUsernameAvailable(anyString())).thenReturn(false);

    assertThatThrownBy(registry::generateUniqueUsername)
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void claimChosenUsername_Should_KeepTheHandleTheGuestRolled() {
    var username = transcoder.decodeUsername(registry.claimChosenUsername(CHOSEN));

    assertThat(username).isEqualTo(CHOSEN);
    verify(guestIdentityCatalog, never()).next(any());
  }

  @Test
  void claimChosenUsername_Should_RefuseANameTheGeneratorCannotProduce() {
    // Anonymity rests on the dice: a typed name such as a real name must never become the login.
    for (var typed : new String[] {"max_mustermann", "anon_7", "katze_sam_12", "Katze_Sam_4821"}) {
      assertThatThrownBy(() -> registry.claimChosenUsername(typed))
          .as(typed)
          .isInstanceOf(BadRequestException.class);
    }
  }

  @Test
  void claimChosenUsername_Should_ReportACollision_So_TheGuestCanRollAgain() {
    when(identityUsernameAvailability.isUsernameAvailable(CHOSEN)).thenReturn(false);

    assertThatThrownBy(() -> registry.claimChosenUsername(CHOSEN))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void claimChosenUsername_Should_ReportACollision_When_TheHandleLivesInAnotherTenant() {
    TenantContext.setCurrentTenant(83L);
    when(userService.findUserByUsername(any()))
        .thenAnswer(
            call ->
                TenantContext.isTechnicalOrSuperAdminContext() && CHOSEN.equals(call.getArgument(0))
                    ? Optional.of(USER)
                    : Optional.empty());

    assertThatThrownBy(() -> registry.claimChosenUsername(CHOSEN))
        .isInstanceOf(ConflictException.class);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(83L);
  }

  @Test
  void removeRegistryIdByUsername_Should_AcceptAnyName_WithoutFailingTheDeletionWorkflow() {
    registry.removeRegistryIdByUsername(transcoder.encodeUsername(CHOSEN));
    registry.removeRegistryIdByUsername(transcoder.encodeUsername("anon_12"));
    registry.removeRegistryIdByUsername(null);
  }

  private static GuestIdentitySuggestion suggestion(String username) {
    return new GuestIdentitySuggestion(username, username, "owl.svg");
  }
}
