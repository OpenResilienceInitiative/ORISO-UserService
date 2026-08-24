package de.caritas.cob.userservice.api.admin.facade;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO.AgencyTypeEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort.FieldEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort.OrderEnum;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.testConfig.TestAgencyControllerApi;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jeasy.random.EasyRandom;
import org.jeasy.random.EasyRandomParameters;
import org.jeasy.random.FieldPredicates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ConsultantAdminFacadeIT {

  @Autowired private ConsultantAdminFacade consultantAdminFacade;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;

  @MockitoBean private AgencyAdminService agencyAdminService;

  @MockitoBean private ConsultingTypeManager consultingTypeManager;

  @MockitoBean private AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @Autowired private EntityManager entityManager;

  private final Set<String> createdConsultantIds = new HashSet<>();

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    createdConsultantIds.forEach(
        consultantId -> {
          consultantAgencyRepository.deleteAll(
              consultantAgencyRepository.findByConsultantId(consultantId));
          consultantRepository.findById(consultantId).ifPresent(consultantRepository::delete);
        });
    createdConsultantIds.clear();
  }

  @Test
  void findFilteredConsultants_Should_retrieveDeletedAgencyRelations_When_consultantIsDeleted() {
    var consultant = givenAPersistedDeletedConsultantWithTenAgencies();

    var searchResult =
        this.consultantAdminFacade.findFilteredConsultants(
            1,
            100,
            new ConsultantFilter(),
            new Sort().field(FieldEnum.FIRST_NAME).order(OrderEnum.ASC));
    var resultConsultants =
        searchResult.getEmbedded().stream()
            .filter(
                consultantResponse ->
                    consultantResponse.getEmbedded().getId().equals(consultant.getId()))
            .collect(Collectors.toSet());

    assertThat(resultConsultants, hasSize(0));
  }

  @Test
  void
      findFilteredConsultants_Should_retrieveOnlyNonDeletedAgencyRelations_When_consultantIsNotDeleted() {
    var consultant = givenAPersistedNonDeletedConsultantWithDeletedAndNotDeletedAgencies();

    var searchResult =
        this.consultantAdminFacade.findFilteredConsultants(
            1,
            100,
            new ConsultantFilter(),
            new Sort().field(FieldEnum.FIRST_NAME).order(OrderEnum.ASC));
    var resultConsultant =
        searchResult.getEmbedded().stream()
            .filter(
                consultantResponse ->
                    consultantResponse.getEmbedded().getId().equals(consultant.getId()))
            .collect(Collectors.toSet())
            .iterator()
            .next();

    assertThat(resultConsultant.getEmbedded().getDeleteDate(), is("null"));
    assertThat(resultConsultant.getEmbedded().getAgencies(), hasSize(5));
    resultConsultant
        .getEmbedded()
        .getAgencies()
        .forEach(agency -> assertThat(agency.getDeleteDate(), is("null")));
  }

  private Consultant givenAPersistedNonDeletedConsultantWithDeletedAndNotDeletedAgencies() {
    var parameters = baseConsultantParameters().excludeField(FieldPredicates.named("deleteDate"));
    var consultant = new EasyRandom(parameters).nextObject(Consultant.class);
    consultant.setLanguages(null);
    consultant.setConsultantTopics(null);
    consultant = consultantRepository.save(consultant);
    createdConsultantIds.add(consultant.getId());
    var consultantAgencies = buildPersistedAgenciesForConsultant(20, 5, consultant);
    consultant.setConsultantAgencies(consultantAgencies);
    mockAgencyServiceResponse(consultantAgencies);

    return consultant;
  }

  private void mockAgencyServiceResponse(Set<ConsultantAgency> consultantAgencies) {
    var mockedAgencies =
        consultantAgencies.stream()
            .map(
                consultantAgency ->
                    new AgencyAdminResponseDTO()
                        .id(consultantAgency.getAgencyId())
                        .deleteDate(String.valueOf(consultantAgency.getDeleteDate())))
            .collect(Collectors.toList());
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(mockedAgencies);
  }

  private EasyRandomParameters baseConsultantParameters() {
    return new EasyRandomParameters()
        .stringLengthRange(1, 17)
        .excludeField(FieldPredicates.named("consultantAgencies"))
        .excludeField(FieldPredicates.named("languages"))
        .excludeField(FieldPredicates.named("consultantMobileTokens"))
        .excludeField(FieldPredicates.named("appointments"))
        .excludeField(FieldPredicates.named("sessions"))
        .excludeField(FieldPredicates.named("consultantTopics"));
  }

  private Set<ConsultantAgency> buildPersistedAgenciesForConsultant(
      int amount, int notDeletedAmount, Consultant consultant) {
    var consultantAgencies =
        new EasyRandom()
            .objects(ConsultantAgency.class, amount)
            .peek(
                agencyRelation -> {
                  agencyRelation.setId(null);
                  agencyRelation.setConsultant(consultant);
                })
            .collect(Collectors.toList());
    for (int i = 0; i < notDeletedAmount; i++) {
      consultantAgencies.get(i).setDeleteDate(null);
    }
    consultantAgencyRepository.saveAll(consultantAgencies);

    return new HashSet<>(consultantAgencies);
  }

  private Consultant givenAPersistedDeletedConsultantWithTenAgencies() {
    var consultant = new EasyRandom(baseConsultantParameters()).nextObject(Consultant.class);
    consultant.setLanguages(null);
    consultant.setConsultantTopics(null);
    consultant.setId(UUID.randomUUID().toString());
    consultant = consultantRepository.save(consultant);
    createdConsultantIds.add(consultant.getId());
    var consultantAgencies = buildPersistedAgenciesForConsultant(10, 0, consultant);
    consultant.setConsultantAgencies(consultantAgencies);
    mockAgencyServiceResponse(consultantAgencies);

    return consultant;
  }

  @Test
  void findFilteredConsultants_Should_retrieveConsultantAfterAddingRelationToAgency() {

    var consultantId = "id";
    givenConsultantWithoutAgency(consultantId);
    when(consultingTypeManager.isConsultantBoundedToAgency(anyInt())).thenReturn(false);
    when(consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(getExtendedConsultingTypeResponse());

    var agencyId = 999L;
    CreateConsultantAgencyDTO consultantAgencyDto = new CreateConsultantAgencyDTO();
    consultantAgencyDto.agencyId(agencyId);

    ConsultantFilter consultantFilter = new ConsultantFilter();
    consultantFilter.setAgencyId(agencyId);
    when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(new TestAgencyControllerApi(new ApiClient()));

    var searchResult =
        this.consultantAdminFacade.findFilteredConsultants(
            1, 100, consultantFilter, new Sort().field(FieldEnum.FIRST_NAME).order(OrderEnum.ASC));

    assertThat(searchResult.getEmbedded(), hasSize(0));

    consultantAdminFacade.createNewConsultantAgency(consultantId, consultantAgencyDto);

    searchResult =
        this.consultantAdminFacade.findFilteredConsultants(
            1, 100, consultantFilter, new Sort().field(FieldEnum.FIRST_NAME).order(OrderEnum.ASC));
    assertThat(searchResult.getEmbedded(), hasSize(greaterThanOrEqualTo(1)));
  }

  private ExtendedConsultingTypeResponseDTO getExtendedConsultingTypeResponse() {
    ExtendedConsultingTypeResponseDTO e = new ExtendedConsultingTypeResponseDTO();
    e.setRoles(new RolesDTO());
    return e;
  }

  private void givenConsultantWithoutAgency(String id) {
    Consultant newConsultant = new Consultant();
    newConsultant.setStatus(ConsultantStatus.CREATED);
    newConsultant.setLastName("lastName");
    newConsultant.setWalkThroughEnabled(false);
    newConsultant.setFirstName("firstName");
    newConsultant.setEmail("email@email.com");
    newConsultant.setMatrixUserId("@consultant:matrix.example");
    newConsultant.setEncourage2fa(false);
    newConsultant.setUsername("username");
    newConsultant.setId(id);
    newConsultant.setNotifyEnquiriesRepeating(false);
    newConsultant.setNotifyNewChatMessageFromAdviceSeeker(false);
    newConsultant.setMagicLinkLoginEnabled(false);
    newConsultant.setLanguageCode(LanguageCode.de);
    newConsultant.setLanguages(null);

    consultantRepository.save(newConsultant);
    createdConsultantIds.add(id);
  }

  @Test
  void testConsultantAgencyForDeletionFiltering() {
    List<AgencyAdminResponseDTO> result = new ArrayList<AgencyAdminResponseDTO>();
    AgencyAdminResponseDTO agency1 = new AgencyAdminResponseDTO();
    agency1.setId(110L);
    result.add(agency1);
    AgencyAdminResponseDTO agency2 = new AgencyAdminResponseDTO();
    agency2.setId(121L);
    result.add(agency2);
    when(this.agencyAdminService.retrieveAllAgencies()).thenReturn(result);

    List<CreateConsultantAgencyDTO> newList = new ArrayList<CreateConsultantAgencyDTO>();
    CreateConsultantAgencyDTO consultantAgency1 = new CreateConsultantAgencyDTO();
    consultantAgency1.setAgencyId(110L);
    newList.add(consultantAgency1);

    String consultanId = "45816eb6-984b-411f-a818-996cd16e1f2a";
    List<Long> filteredList =
        consultantAdminFacade.filterAgencyListForDeletion(consultanId, newList);
    assertThat(filteredList.size(), is(1));

    CreateConsultantAgencyDTO consultantAgency2 = new CreateConsultantAgencyDTO();
    consultantAgency2.setAgencyId(121L);
    newList.add(consultantAgency2);

    filteredList = consultantAdminFacade.filterAgencyListForDeletion(consultanId, newList);
    assertThat(filteredList.size(), is(0));
  }

  @Test
  void testConsultantAgencyForCreationFiltering() {
    List<AgencyAdminResponseDTO> result = new ArrayList<AgencyAdminResponseDTO>();
    AgencyAdminResponseDTO agency1 = new AgencyAdminResponseDTO();
    agency1.setId(110L);
    result.add(agency1);
    AgencyAdminResponseDTO agency2 = new AgencyAdminResponseDTO();
    agency2.setId(121L);
    result.add(agency2);
    when(this.agencyAdminService.retrieveAllAgencies()).thenReturn(result);

    List<CreateConsultantAgencyDTO> newList = new ArrayList<CreateConsultantAgencyDTO>();
    CreateConsultantAgencyDTO consultantAgency1 = new CreateConsultantAgencyDTO();
    consultantAgency1.setAgencyId(110L);
    newList.add(consultantAgency1);

    String consultantId = "45816eb6-984b-411f-a818-996cd16e1f2a";
    consultantAdminFacade.filterAgencyListForCreation(consultantId, newList);
    assertThat(newList.size(), is(0));

    CreateConsultantAgencyDTO consultantAgency2 = new CreateConsultantAgencyDTO();
    consultantAgency2.setAgencyId(122L);
    newList.add(consultantAgency2);

    consultantAdminFacade.filterAgencyListForCreation(consultantId, newList);
    assertThat(newList.size(), is(1));
  }

  // ---------------------------------------------------------------------------
  // changeAgencyType (regression test for #1069)
  // ---------------------------------------------------------------------------

  /**
   * Reproduces the original bug behind issue #1069: converting a team agency to a default (single)
   * agency used to raise a {@code LazyInitializationException}. {@link
   * de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade#changeAgencyType} is
   * deliberately NOT annotated {@code @Transactional} (matching production), and this test class
   * has no class-level {@code @Transactional} either, so — just like on a real request, since
   * {@code spring.jpa.open-in-view=false} — every repository call below opens and closes its own
   * Hibernate session. Before the fix, {@code noOtherTeamAgency()} touched a lazy collection on a
   * consultant loaded by an already-closed prior repository call and blew up with a 500.
   */
  @Test
  void
      changeAgencyType_Should_notThrowLazyInitializationException_When_ConvertingTeamAgencyToDefaultAgency() {
    var consultantId = UUID.randomUUID().toString();
    givenConsultantWithoutAgency(consultantId);
    var consultant = consultantRepository.findById(consultantId).orElseThrow();
    consultant.setTeamConsultant(true);
    consultantRepository.save(consultant);

    var convertedAgencyId = 5551L;
    var otherTeamAgencyId = 5552L;

    consultantAgencyRepository.saveAll(
        List.of(
            ConsultantAgency.builder()
                .consultant(consultant)
                .agencyId(convertedAgencyId)
                .status(ConsultantAgencyStatus.IN_PROGRESS)
                .build(),
            ConsultantAgency.builder()
                .consultant(consultant)
                .agencyId(otherTeamAgencyId)
                .status(ConsultantAgencyStatus.IN_PROGRESS)
                .build()));

    when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(
            new TeamAwareAgencyControllerApi(new ApiClient(), Map.of(otherTeamAgencyId, true)));

    var agencyTypeDTO = new AgencyTypeDTO().agencyType(AgencyTypeEnum.DEFAULT_AGENCY);

    assertDoesNotThrow(
        () -> consultantAdminFacade.changeAgencyType(convertedAgencyId, agencyTypeDTO));

    var reloadedConsultant = consultantRepository.findById(consultantId).orElseThrow();
    // The consultant still has agency 5552, which IS a team agency, so the team-consultant flag
    // must be kept.
    assertThat(reloadedConsultant.isTeamConsultant(), is(true));
  }

  @Test
  void changeAgencyType_Should_RemoveTeamConsultantFlag_When_ConvertedAgencyWasTheOnlyTeamAgency() {
    var consultantId = UUID.randomUUID().toString();
    givenConsultantWithoutAgency(consultantId);
    var consultant = consultantRepository.findById(consultantId).orElseThrow();
    consultant.setTeamConsultant(true);
    consultantRepository.save(consultant);

    var convertedAgencyId = 5561L;
    consultantAgencyRepository.save(
        ConsultantAgency.builder()
            .consultant(consultant)
            .agencyId(convertedAgencyId)
            .status(ConsultantAgencyStatus.IN_PROGRESS)
            .build());

    var agencyTypeDTO = new AgencyTypeDTO().agencyType(AgencyTypeEnum.DEFAULT_AGENCY);

    assertDoesNotThrow(
        () -> consultantAdminFacade.changeAgencyType(convertedAgencyId, agencyTypeDTO));

    var reloadedConsultant = consultantRepository.findById(consultantId).orElseThrow();
    assertThat(reloadedConsultant.isTeamConsultant(), is(false));
  }

  /** Test double returning a deterministic {@code teamAgency} flag per agency id. */
  private static final class TeamAwareAgencyControllerApi extends AgencyControllerApi {

    private final Map<Long, Boolean> teamAgencyById;

    private TeamAwareAgencyControllerApi(ApiClient apiClient, Map<Long, Boolean> teamAgencyById) {
      super(apiClient);
      this.teamAgencyById = teamAgencyById;
    }

    @Override
    public List<AgencyResponseDTO> getAgenciesByIds(List<Long> agencyIds)
        throws RestClientException {
      return agencyIds.stream()
          .map(
              id ->
                  new AgencyResponseDTO().id(id).teamAgency(teamAgencyById.getOrDefault(id, false)))
          .collect(Collectors.toList());
    }
  }
}
