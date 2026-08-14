package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hibernate.search.util.impl.CollectionHelper.asSet;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.util.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
class ConsultantAgencyRelationCreatorServiceIT {

  private final EasyRandom easyRandom = new EasyRandom();

  @Autowired private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private UserAgencyRepository userAgencyRepository;

  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;

  @Autowired private SessionRepository sessionRepository;

  @MockitoBean private AgencyService agencyService;

  @MockitoBean private KeycloakService keycloakService;

  @MockitoBean private ConsultingTypeManager consultingTypeManager;

  @Test
  void createNewConsultantAgency_ShouldPersistRelation_WhenParamsAreValid() {

    Consultant consultant = createConsultantWithoutAgencyAndSession();

    CreateConsultantAgencyDTO createConsultantAgencyDTO = new CreateConsultantAgencyDTO();
    createConsultantAgencyDTO.setAgencyId(15L);
    createConsultantAgencyDTO.setRoleSetKey("valid-role-set");

    when(keycloakService.findAllByUserId(consultant.getId()))
        .thenReturn(List.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));

    AgencyDTO agencyDTO = new AgencyDTO();
    agencyDTO.setId(15L);
    agencyDTO.setTeamAgency(false);
    agencyDTO.setConsultingType(0);
    when(agencyService.getAgency(15L)).thenReturn(agencyDTO);
    when(agencyService.getAgenciesWithoutCaching(List.of(15L))).thenReturn(List.of(agencyDTO));

    createSessionWithoutConsultant(agencyDTO.getId(), SessionStatus.NEW);

    final var consultingTypeResponse =
        easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    when(consultingTypeManager.getConsultingTypeSettings(0)).thenReturn(consultingTypeResponse);

    this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
        consultant.getId(), createConsultantAgencyDTO);

    List<ConsultantAgency> result =
        this.consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultant.getId());

    assertThat(result, notNullValue());
    assertThat(result, hasSize(1));
    assertThat(
        result.getFirst().getStatus(),
        is(de.caritas.cob.userservice.api.model.ConsultantAgencyStatus.CREATED));
  }

  @Test
  void createNewConsultantAgency_ShouldMarkTeamConsultant_WhenTeamAgencyIsAssigned() {

    Consultant consultant = createConsultantWithoutAgencyAndSession();

    CreateConsultantAgencyDTO createConsultantAgencyDTO = new CreateConsultantAgencyDTO();
    createConsultantAgencyDTO.setAgencyId(15L);
    createConsultantAgencyDTO.setRoleSetKey("valid-role-set");

    when(keycloakService.findAllByUserId(consultant.getId()))
        .thenReturn(List.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));
    ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO =
        new ExtendedConsultingTypeResponseDTO();
    AgencyDTO agencyDTO = new AgencyDTO();
    agencyDTO.setId(15L);
    agencyDTO.setTeamAgency(true);
    agencyDTO.setConsultingType(0);
    when(agencyService.getAgency(15L)).thenReturn(agencyDTO);
    when(agencyService.getAgenciesWithoutCaching(List.of(15L))).thenReturn(List.of(agencyDTO));
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(extendedConsultingTypeResponseDTO);

    createSessionWithoutConsultant(agencyDTO.getId(), SessionStatus.IN_PROGRESS);

    this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
        consultant.getId(), createConsultantAgencyDTO);

    List<ConsultantAgency> result =
        this.consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultant.getId());

    assertThat(result, notNullValue());
    assertThat(result, hasSize(1));
    assertThat(
        this.consultantRepository
            .findByIdAndDeleteDateIsNull(consultant.getId())
            .get()
            .isTeamConsultant(),
        is(true));
  }

  @Test
  void createNewConsultantAgency_Should_updateKeycloakRoles_When_ParamsAreValid() {
    var roleSetName = "consultant";
    var createConsultantAgencyDTO = new CreateConsultantAgencyDTO();
    createConsultantAgencyDTO.setAgencyId(15L);
    createConsultantAgencyDTO.setRoleSetKey(roleSetName);

    int consultingType = 0;
    var agencyDTO = new AgencyDTO();
    agencyDTO.setId(15L);
    agencyDTO.setTeamAgency(false);
    agencyDTO.setConsultingType(consultingType);
    when(agencyService.getAgency(15L)).thenReturn(agencyDTO);
    when(agencyService.getAgenciesWithoutCaching(List.of(15L))).thenReturn(List.of(agencyDTO));

    var consultant = createConsultantWithoutAgencyAndSession();
    when(keycloakService.findAllByUserId(consultant.getId()))
        .thenReturn(List.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));
    var roles = givenRoleSets(consultingType, roleSetName);

    consultantAgencyRelationCreatorService.createNewConsultantAgency(
        consultant.getId(), createConsultantAgencyDTO);

    verify(keycloakService)
        .ensureRoles(
            eq(consultant.getId()),
            argThat(roleNames -> Set.copyOf(roleNames).equals(Set.copyOf(roles))));
    var result =
        consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultant.getId());

    assertThat(result, notNullValue());
    assertThat(result, hasSize(1));
  }

  @SuppressWarnings("SameParameterValue")
  private List<String> givenRoleSets(int consultingTypeId, String roleSetName) {
    var roleSets = new LinkedHashMap<String, List<String>>();
    var roles = List.of("consultant", "u25-consultant");
    roleSets.put(roleSetName, roles);

    var roleConsultant =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    roleConsultant.setRoleSets(roleSets);

    var rolesDTO =
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO();
    rolesDTO.setConsultant(roleConsultant);

    final var consultingTypeResponse =
        easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    consultingTypeResponse.setRoles(rolesDTO);
    when(consultingTypeManager.getConsultingTypeSettings(consultingTypeId))
        .thenReturn(consultingTypeResponse);

    return roles;
  }

  private Consultant createConsultantWithoutAgencyAndSession() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setConsultantAgencies(null);
    consultant.setSessions(null);
    consultant.setConsultantMobileTokens(null);
    consultant.setConsultantTopics(null);
    consultant.setTenantId(null);
    // Required legacy model field; this slice removes its behavior, the schema cleanup follows.
    consultant.setMatrixUserId("legacy-id");
    consultant.setDeleteDate(null);
    consultant.setLanguages(null);
    consultant.setAppointments(null);
    return this.consultantRepository.save(consultant);
  }

  private Session createSessionWithoutConsultant(Long agencyId, SessionStatus sessionStatus) {

    User user = easyRandom.nextObject(User.class);
    user.setSessions(null);
    user.setUserMobileTokens(null);
    user.setUserAgencies(null);
    this.userRepository.save(user);

    UserAgency userAgency = new UserAgency();
    userAgency.setAgencyId(agencyId);
    userAgency.setUser(user);
    this.userAgencyRepository.save(userAgency);

    Session session = new Session();
    session.setStatus(sessionStatus);
    session.setPostcode("12345");
    session.setId(1L);
    session.setConsultant(null);
    session.setUser(user);
    session.setAgencyId(agencyId);
    session.setLanguageCode(LanguageCode.de);
    session.setTeamSession(true);
    session.setSessionTopics(Lists.newArrayList());
    session.setIsConsultantDirectlySet(false);

    return this.sessionRepository.save(session);
  }

  @Test
  void
      createConsultantAgencyRelations_Should_throwBadRequestException_When_consultantDoesNotExist() {
    assertThrows(
        BadRequestException.class,
        () -> {
          this.consultantAgencyRelationCreatorService.createConsultantAgencyRelations(
              "invalid", asSet(1L), asSet("role"), null);
        });
  }

  @Test
  void
      createNewConsultantAgency_Should_throwBadRequestException_When_consultantHasNotExpectedRole() {
    assertThrows(
        BadRequestException.class,
        () -> {
          Consultant consultant = createConsultantWithoutAgencyAndSession();

          CreateConsultantAgencyDTO createConsultantAgencyDTO = new CreateConsultantAgencyDTO();
          when(keycloakService.findAllByUserId(any())).thenReturn(List.of());

          this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
              consultant.getId(), createConsultantAgencyDTO);
        });
  }

  @Test
  void
      createNewConsultantAgency_Should_throwBadRequestException_When_agencyServiceReturnesNullForAgency() {
    assertThrows(
        BadRequestException.class,
        () -> {
          Consultant consultant = createConsultantWithoutAgencyAndSession();

          CreateConsultantAgencyDTO createConsultantAgencyDTO =
              new CreateConsultantAgencyDTO().roleSetKey("valid role set");
          when(keycloakService.findAllByUserId(any()))
              .thenReturn(List.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));
          when(this.agencyService.getAgency(any())).thenReturn(null);

          this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
              consultant.getId(), createConsultantAgencyDTO);
        });
  }

  @Test
  void
      createNewConsultantAgency_Should_throwInternalServerErrorException_When_agencyServiceThrowsAgencyServiceHelperException() {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          Consultant consultant = createConsultantWithoutAgencyAndSession();

          CreateConsultantAgencyDTO createConsultantAgencyDTO =
              new CreateConsultantAgencyDTO().roleSetKey("valid role set");
          when(keycloakService.findAllByUserId(any()))
              .thenReturn(List.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));
          when(agencyService.getAgency(any())).thenThrow(new InternalServerErrorException(""));

          this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
              consultant.getId(), createConsultantAgencyDTO);
        });
  }

  @Test
  void
      createNewConsultantAgency_Should_throwBadRequestException_When_agencyTypeIsU25AndConsultantHasAnotherConsultingTypeAssigned() {
    assertThrows(
        BadRequestException.class,
        () -> {
          AgencyDTO emigrationAgency = new AgencyDTO().consultingType(17);

          AgencyDTO agencyDTO = new AgencyDTO().consultingType(1).id(2L);

          when(agencyService.getAgency(1731L)).thenReturn(emigrationAgency);
          when(agencyService.getAgency(2L)).thenReturn(agencyDTO);
          when(keycloakService.findAllByUserId(any()))
              .thenReturn(List.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));
          when(consultingTypeManager.isConsultantBoundedToAgency(1)).thenReturn(true);

          CreateConsultantAgencyDTO createConsultantAgencyDTO =
              new CreateConsultantAgencyDTO().roleSetKey("valid role set").agencyId(2L);

          String consultantIdWIthEmigrationAgency = "0b3b1cc6-be98-4787-aa56-212259d811b9";
          this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
              consultantIdWIthEmigrationAgency, createConsultantAgencyDTO);
        });
  }

  @Test
  void
      createNewConsultantAgency_Should_throwBadRequestException_When_agencyTypeIsKreuzbundAndConsultantHasAnotherConsultingTypeAssigned() {
    assertThrows(
        BadRequestException.class,
        () -> {
          AgencyDTO emigrationAgency = new AgencyDTO().consultingType(17);

          AgencyDTO agencyDTO = new AgencyDTO().consultingType(15).id(2L);

          when(agencyService.getAgency(1731L)).thenReturn(emigrationAgency);
          when(agencyService.getAgency(2L)).thenReturn(agencyDTO);
          when(keycloakService.findAllByUserId(any()))
              .thenReturn(List.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));
          when(consultingTypeManager.isConsultantBoundedToAgency(15)).thenReturn(true);

          CreateConsultantAgencyDTO createConsultantAgencyDTO =
              new CreateConsultantAgencyDTO().roleSetKey("valid role set").agencyId(2L);

          this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
              "0b3b1cc6-be98-4787-aa56-212259d811b9", createConsultantAgencyDTO);
        });
  }

  @Test
  void
      createConsultantAgencyRelations_Should_throwBadRequestException_When_ConsultantHasNotRequestedRole() {
    final var consultant = createConsultantWithoutAgencyAndSession();
    try {
      consultantAgencyRelationCreatorService.createConsultantAgencyRelations(
          consultant.getId(), Set.of(), Set.of(), null);
      fail("There was no BadRequestException");
    } catch (Exception e) {
      assertThat(e, instanceOf(BadRequestException.class));
      assertThat(
          e.getMessage(),
          is("Consultant with id " + consultant.getId() + " does not have the role set []"));
    }
  }
}
