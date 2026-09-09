package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.google.api.client.util.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.Language;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityAccountSettingsUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = "multitenancy.enabled=true")
@Transactional(propagation = Propagation.NEVER)
class ConsultantAgencyRelationCreatorServiceTenantAwareIT {

  private final EasyRandom easyRandom = new EasyRandom();

  @Autowired private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private UserAgencyRepository userAgencyRepository;

  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;

  @Autowired private SessionRepository sessionRepository;

  @MockitoBean private AgencyService agencyService;

  @MockitoBean(
      extraInterfaces = {
        IdentityAccountRemover.class,
        IdentityAuthentication.class,
        IdentityDeactivator.class,
        IdentityDummyEmailUpdater.class,
        IdentityEmailAddressUpdater.class,
        IdentityEmailOwnerLookup.class,
        IdentityLocaleLookup.class,
        IdentityPasswordUpdater.class,
        IdentityProfileLookup.class,
        IdentityProfileUpdater.class,
        IdentityRoleLookup.class,
        IdentityAccountSettingsUpdater.class,
        IdentityRoleUpdater.class,
        IdentitySecondFactor.class,
        IdentityUsernameAvailability.class
      })
  private IdentityClient identityClient;

  @MockitoBean private ConsultingTypeManager consultingTypeManager;

  @BeforeEach
  public void beforeTests() {
    TenantContext.setCurrentTenant(1L);
  }

  @AfterEach
  public void afterTests() {
    TenantContext.clear();
  }

  @Test
  void createNewConsultantAgency_ShouldPersistRelationWithTenant_WhenMultitenancyEnabled() {
    Consultant consultant = createConsultantWithoutAgencyAndSession();

    CreateConsultantAgencyDTO createConsultantAgencyDTO = new CreateConsultantAgencyDTO();
    createConsultantAgencyDTO.setAgencyId(15L);
    createConsultantAgencyDTO.setRoleSetKey("valid-role-set");

    AgencyDTO agencyDTO = new AgencyDTO();
    agencyDTO.setId(15L);
    agencyDTO.setTeamAgency(false);
    agencyDTO.setConsultingType(0);
    agencyDTO.setTenantId(1L);
    when(agencyService.getAgency(15L)).thenReturn(agencyDTO);
    when(agencyService.getAgenciesWithoutCaching(List.of(15L))).thenReturn(List.of(agencyDTO));

    Session enquirySessionWithoutConsultant =
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
    assertEquals(ConsultantAgencyStatus.CREATED, result.getFirst().getStatus());
    assertEquals(1, enquirySessionWithoutConsultant.getTenantId());

    List<ConsultantAgency> agenciesForConsultant =
        this.consultantAgencyRepository.findByConsultantId(consultant.getId());
    assertEquals(1, agenciesForConsultant.get(0).getTenantId());
  }

  @Test
  void
      createNewConsultantAgency_ShouldFinalizePersistedRelation_WhenMatrixOnlyAndMultitenancyEnabled() {
    TenantContext.setCurrentTenant(0L);
    Consultant consultant = createConsultantWithoutAgencyAndSession();
    consultant.setTenantId(83L);
    consultant.setStatus(ConsultantStatus.CREATED);
    consultantRepository.save(consultant);

    CreateConsultantAgencyDTO createConsultantAgencyDTO =
        new CreateConsultantAgencyDTO().agencyId(15L).roleSetKey("valid-role-set");
    AgencyDTO agencyDTO = new AgencyDTO().id(15L).teamAgency(false).consultingType(0).tenantId(83L);
    when(agencyService.getAgency(15L)).thenReturn(agencyDTO);
    when(agencyService.getAgenciesWithoutCaching(List.of(15L))).thenReturn(List.of(agencyDTO));
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());

    consultantAgencyRelationCreatorService.createNewConsultantAgency(
        consultant.getId(), createConsultantAgencyDTO);

    ConsultantAgency relation =
        consultantAgencyRepository
            .findByConsultantIdAndAgencyIdAndDeleteDateIsNull(consultant.getId(), 15L)
            .getFirst();
    assertEquals(ConsultantAgencyStatus.CREATED, relation.getStatus());
    assertEquals(
        ConsultantStatus.CREATED,
        consultantRepository
            .findByIdAndDeleteDateIsNull(consultant.getId())
            .orElseThrow()
            .getStatus());
  }

  private Consultant createConsultantWithoutAgencyAndSession() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setAppointments(null);
    consultant.setTenantId(1L);
    consultant.setConsultantAgencies(null);
    consultant.setSessions(null);
    consultant.setConsultantMobileTokens(null);
    consultant.setConsultantTopics(null);
    // Required legacy model field; this slice removes its behavior, the schema cleanup follows.
    consultant.setMatrixUserId("legacy-id");
    consultant.setDeleteDate(null);
    Set<Language> language = new HashSet<>();
    Language lang = new Language();
    lang.setLanguageCode(LanguageCode.de);
    lang.setConsultant(consultant);
    language.add(lang);
    consultant.setLanguages(language);
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
    session.setTeamSession(true);
    session.setSessionTopics(Lists.newArrayList());
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setTenantId(1L);

    return this.sessionRepository.save(session);
  }
}
