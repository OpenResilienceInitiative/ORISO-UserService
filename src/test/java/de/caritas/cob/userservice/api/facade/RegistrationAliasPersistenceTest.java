package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.SessionDataProvider;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.service.GeneratedPseudonyms;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.DirectSessionMatrixRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.SessionDataInitializingDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.ArrayList;
import java.util.List;

class RegistrationAliasPersistenceTest {
  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> aliases() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(null, true, null),
        org.junit.jupiter.params.provider.Arguments.of("", false, null),
        org.junit.jupiter.params.provider.Arguments.of("   ", false, null),
        org.junit.jupiter.params.provider.Arguments.of("\u2003", false, null),
        org.junit.jupiter.params.provider.Arguments.of("  Éloïse 🌻  ", true, "Éloïse 🌻"),
        org.junit.jupiter.params.provider.Arguments.of("loewe_mika_1234", false, "loewe_mika_1234"),
        org.junit.jupiter.params.provider.Arguments.of("Ж".repeat(255), true, "Ж".repeat(255)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("aliases")
  void firstOrdinarySessionStoresOptionalAliasBeforeTheNewAskerIsLoggedIn(
      String alias, boolean editable, String expected) throws Exception {
    var signup =
        new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(
                """
            {"username":"immutable-login","displayName":"  Éloïse 🌻  ","age":"17","state":"9","agencyId":700,"consultingType":"1"}
            """,
                UserDTO.class);
    signup.setDisplayName(alias);
    var user =
        User.builder()
            .userId("new-asker")
            .tenantId(42L)
            .username("immutable-login")
            .email("new@example.invalid")
            .build();
    var session =
        Session.builder()
            .id(11L)
            .user(user)
            .tenantId(42L)
            .consultingTypeId(1)
            .postcode("00000")
            .status(Session.SessionStatus.NEW)
            .sessionData(new ArrayList<>())
            .registrationType(Session.RegistrationType.REGISTERED)
            .build();
    var sessions = mock(SessionService.class);
    when(sessions.initializeSession(eq(user), eq(signup), eq(false))).thenReturn(session);
    var consulting = mock(ConsultingTypeManager.class);
    var settings =
        new ExtendedConsultingTypeResponseDTO()
            .id(1)
            .sessionDataInitializing(new SessionDataInitializingDTO().age(true).state(true));
    when(consulting.getConsultingTypeSettings(1)).thenReturn(settings);
    var tenants = mock(TenantService.class);
    lenient()
        .when(tenants.getRestrictedTenantDataFresh(42L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(42L)
                .settings(new Settings().featureDisplayNameEditable(editable)));
    var saved = new ArrayList<SessionData>();
    var dataRepository = mock(SessionDataRepository.class);
    when(dataRepository.saveAll(any()))
        .thenAnswer(
            call -> {
              Iterable<SessionData> values = call.getArgument(0);
              values.forEach(saved::add);
              return saved;
            });
    var transactionManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
    when(sessions.getSession(11L)).thenReturn(java.util.Optional.of(session));
    var data =
        new SessionDataService(
            dataRepository,
            new SessionDataProvider(consulting),
            sessions,
            mock(AuthenticatedUser.class),
            tenants,
            new GeneratedPseudonyms(),
            mock(MembersOnlyChatAccess.class),
            transactionManager);
    var agencies = mock(AgencyVerifier.class);
    when(agencies.getVerifiedAgency(700L, 1))
        .thenReturn(new AgencyDTO().id(700L).teamAgency(false));
    var facade =
        new CreateSessionFacade(
            sessions,
            mock(NewCounsellingDpaPolicy.class),
            agencies,
            data,
            mock(RollbackFacade.class),
            mock(UserAccountService.class),
            mock(ConsultantAgencyRepository.class),
            mock(AgencyPreAssignmentRoomService.class),
            mock(DirectSessionMatrixRoomService.class));

    assertThat(facade.createUserSession(signup, user, settings, List.of())).isEqualTo(11L);

    var aliases = saved.stream().filter(value -> "displayName".equals(value.getKey())).toList();
    if (expected == null) assertThat(aliases).isEmpty();
    else {
      assertThat(aliases).hasSize(1);
      assertThat(aliases.getFirst().getValue()).isEqualTo(expected);
      assertThat(aliases.getFirst().getSession()).isSameAs(session);
    }
    assertThat(saved)
        .anySatisfy(
            value -> {
              assertThat(value.getKey()).isEqualTo("age");
              assertThat(value.getValue()).isEqualTo("17");
            });
    assertThat(saved)
        .anySatisfy(
            value -> {
              assertThat(value.getKey()).isEqualTo("state");
              assertThat(value.getValue()).isEqualTo("9");
            });
    assertThat(user.getUsername()).isEqualTo("immutable-login");
  }
}
