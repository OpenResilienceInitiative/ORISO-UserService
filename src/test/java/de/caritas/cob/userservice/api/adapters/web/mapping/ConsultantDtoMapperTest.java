package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.HalLink.MethodEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ConsultantDtoMapperTest {

  @Mock private IdentityManaging identityManager;
  @Mock private ConsultantTopicRepository consultantTopicRepository;
  @Mock private TopicService topicService;

  @BeforeEach
  void setupRequestContext() {
    var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void consultantDtoOf_Should_MapMasterTableFields() {
    // given
    ConsultantDtoMapper consultantDtoMapper = new ConsultantDtoMapper();
    ReflectionTestUtils.setField(consultantDtoMapper, "identityManager", identityManager);
    when(identityManager.hasRole("consultant-id", UserRole.GROUP_CHAT_CONSULTANT))
        .thenReturn(false);

    // when
    var consultant = consultantDtoMapper.consultantDtoOf(consultantMap());

    // then
    assertThat(consultant.getDisplayName()).isEqualTo("Public Name");
    assertThat(consultant.getPublicName()).isEqualTo("Public Name");
    assertThat(consultant.getInternalDisplayName()).isEqualTo("Internal Name");
    assertThat(consultant.getVacated()).isTrue();
    assertThat(consultant.getAdminRights()).isTrue();
    assertThat(consultant.getRoleInOrg()).isEqualTo("Counsellor Admin");
  }

  @Test
  void consultantDtoOf_Should_KeepInternalDisplayNameNull_When_AbsentFromMap() {
    ConsultantDtoMapper consultantDtoMapper = new ConsultantDtoMapper();
    ReflectionTestUtils.setField(consultantDtoMapper, "identityManager", identityManager);
    when(identityManager.hasRole("consultant-id", UserRole.GROUP_CHAT_CONSULTANT))
        .thenReturn(false);
    var map = consultantMap();
    map.remove("internalDisplayName");

    var consultant = consultantDtoMapper.consultantDtoOf(map);

    // No internal name set: the DTO keeps it null while displayName/publicName stay the
    // PUBLIC name — the fallback is the reader's concern, not the mapper's.
    assertThat(consultant.getInternalDisplayName()).isNull();
    assertThat(consultant.getDisplayName()).isEqualTo("Public Name");
    assertThat(consultant.getPublicName()).isEqualTo("Public Name");
  }

  @Test
  void consultantDtoOf_Should_MapOtherIdentityFields_WhenPresentInMap() {
    // given
    ConsultantDtoMapper consultantDtoMapper = new ConsultantDtoMapper();
    ReflectionTestUtils.setField(consultantDtoMapper, "identityManager", identityManager);
    when(identityManager.hasRole("consultant-id", UserRole.GROUP_CHAT_CONSULTANT))
        .thenReturn(false);
    var map = consultantMap();
    map.put("hasOtherIdentity", true);
    map.put("otherIdentityTypes", List.of("TENANT_ADMIN"));

    // when
    var consultant = consultantDtoMapper.consultantDtoOf(map);

    // then
    assertThat(consultant.getHasOtherIdentity()).isTrue();
    assertThat(consultant.getOtherIdentityTypes())
        .containsExactly(
            de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO.OtherIdentityTypesEnum
                .TENANT_ADMIN);
  }

  @Test
  void consultantDtoOf_Should_DefaultOtherIdentityFields_WhenAbsentFromMap() {
    // given
    ConsultantDtoMapper consultantDtoMapper = new ConsultantDtoMapper();
    ReflectionTestUtils.setField(consultantDtoMapper, "identityManager", identityManager);
    when(identityManager.hasRole("consultant-id", UserRole.GROUP_CHAT_CONSULTANT))
        .thenReturn(false);

    // when
    var consultant = consultantDtoMapper.consultantDtoOf(consultantMap());

    // then
    assertThat(consultant.getHasOtherIdentity()).isFalse();
    assertThat(consultant.getOtherIdentityTypes()).isEmpty();
  }

  private Map<String, Object> consultantMap() {
    Map<String, Object> consultantMap = new HashMap<>();
    consultantMap.put("id", "consultant-id");
    consultantMap.put("email", "consultant@example.org");
    consultantMap.put("firstName", "First");
    consultantMap.put("lastName", "Last");
    consultantMap.put("username", "consultant");
    consultantMap.put("status", "ACTIVE");
    consultantMap.put("absenceMessage", "absence");
    consultantMap.put("isAbsent", false);
    consultantMap.put("isLanguageFormal", true);
    consultantMap.put("isTeamConsultant", false);
    consultantMap.put("isSupervisor", true);
    consultantMap.put("createdAt", "2026-06-08T10:00:00");
    consultantMap.put("updatedAt", "2026-06-08T10:00:00");
    consultantMap.put("deletedAt", "2026-06-09T10:00:00");
    consultantMap.put("displayName", "Public Name");
    consultantMap.put("internalDisplayName", "Internal Name");
    consultantMap.put("tenantId", 1L);
    consultantMap.put("tenantName", "Tenant");
    consultantMap.put("agencies", new ArrayList<Map<String, Object>>());
    return consultantMap;
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  private ConsultantDtoMapper givenAMapper() {
    ConsultantDtoMapper consultantDtoMapper = new ConsultantDtoMapper();
    ReflectionTestUtils.setField(consultantDtoMapper, "identityManager", identityManager);
    ReflectionTestUtils.setField(
        consultantDtoMapper, "consultantTopicRepository", consultantTopicRepository);
    ReflectionTestUtils.setField(consultantDtoMapper, "topicService", topicService);
    return consultantDtoMapper;
  }

  @Test
  void consultantDtoOf_Should_MapCounsellorRole_When_NotSupervisor() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    when(identityManager.hasRole(anyString(), any(UserRole.class))).thenReturn(false);
    Map<String, Object> map = consultantMap();
    map.put("isSupervisor", false);
    map.put("deletedAt", null);

    var consultant = consultantDtoMapper.consultantDtoOf(map);

    assertThat(consultant.getRoleInOrg()).isEqualTo("Counsellor");
    assertThat(consultant.getAdminRights()).isFalse();
    assertThat(consultant.getIsSupervisor()).isFalse();
    assertThat(consultant.getVacated()).isFalse();
  }

  @Test
  void consultantDtoOf_Should_LeaveTenantIdNull_When_TenantIdIsNull() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    when(identityManager.hasRole(anyString(), any(UserRole.class))).thenReturn(false);
    Map<String, Object> map = consultantMap();
    map.put("tenantId", null);

    var consultant = consultantDtoMapper.consultantDtoOf(map);

    assertThat(consultant.getTenantId()).isNull();
  }

  @Test
  void consultantDtoOf_Should_SetGroupchatConsultantFalse_When_IdentityInputThrows() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    when(identityManager.hasRole(anyString(), any(UserRole.class)))
        .thenThrow(new RuntimeException("keycloak user missing"));

    var consultant = consultantDtoMapper.consultantDtoOf(consultantMap());

    assertThat(consultant.getIsGroupchatConsultant()).isFalse();
  }

  @Test
  void consultantDtoOf_Should_MapAgencies_When_AgenciesPresent() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    when(identityManager.hasRole(anyString(), any(UserRole.class))).thenReturn(false);
    Map<String, Object> map = consultantMap();
    Map<String, Object> agencyMap = new HashMap<>();
    agencyMap.put("id", 1L);
    agencyMap.put("name", "Agency Name");
    agencyMap.put("postcode", "88045");
    agencyMap.put("city", "City");
    agencyMap.put("description", "description");
    agencyMap.put("isTeamAgency", true);
    agencyMap.put("isOffline", false);
    agencyMap.put("consultingType", 1);
    ArrayList<Map<String, Object>> agencies = new ArrayList<>();
    agencies.add(agencyMap);
    map.put("agencies", agencies);

    var consultant = consultantDtoMapper.consultantDtoOf(map);

    assertThat(consultant.getAgencies()).hasSize(1);
    assertThat(consultant.getAgencies().get(0).getName()).isEqualTo("Agency Name");
    assertThat(consultant.getAgencies().get(0).getTeamAgency()).isTrue();
  }

  @Test
  void updateAdminConsultantOf_Should_MapAllFieldsAndLowercaseEmail() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    var updateConsultantDTO =
        new UpdateConsultantDTO()
            .email("Mail@Example.COM")
            .firstname("Firstname")
            .lastname("Lastname")
            .languages(List.of(LanguageCode.DE))
            .dataPrivacyConfirmation(true)
            .termsAndConditionsConfirmation(true);
    Consultant consultant =
        Consultant.builder()
            .id("consultantId")
            .matrixUserId("rcId")
            .username("username")
            .firstName("Firstname")
            .lastName("Lastname")
            .email("mail@example.com")
            .languageFormal(true)
            .absent(true)
            .absenceMessage("absence message")
            .build();

    var result = consultantDtoMapper.updateAdminConsultantOf(updateConsultantDTO, consultant);

    assertThat(result.getEmail()).isEqualTo("mail@example.com");
    assertThat(result.getFirstname()).isEqualTo("Firstname");
    assertThat(result.getLastname()).isEqualTo("Lastname");
    assertThat(result.getFormalLanguage()).isTrue();
    assertThat(result.getAbsent()).isTrue();
    assertThat(result.getAbsenceMessage()).isEqualTo("absence message");
    assertThat(result.getLanguages()).containsExactly("de");
    assertThat(result.getDataPrivacyConfirmation()).isTrue();
    assertThat(result.getTermsAndConditionsConfirmation()).isTrue();
  }

  @Test
  void updateAdminConsultantOf_Should_LeaveEmailNull_When_EmailIsNull() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    var updateConsultantDTO = new UpdateConsultantDTO().firstname("Firstname").lastname("Lastname");
    Consultant consultant =
        Consultant.builder()
            .id("consultantId")
            .matrixUserId("rcId")
            .username("username")
            .firstName("Firstname")
            .lastName("Lastname")
            .email("mail@example.com")
            .build();

    var result = consultantDtoMapper.updateAdminConsultantOf(updateConsultantDTO, consultant);

    assertThat(result.getEmail()).isNull();
  }

  @Test
  void consultantResponseDtoOf_Should_MapNames_When_MapNamesIsTrue() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    Consultant consultant =
        Consultant.builder()
            .id("consultantId")
            .matrixUserId("rcId")
            .username("username")
            .firstName("Firstname")
            .lastName("Lastname")
            .email("mail@example.com")
            .supervisor(true)
            .build();
    var agencies = List.of(new AgencyDTO().id(1L).name("Agency"));

    var result = consultantDtoMapper.consultantResponseDtoOf(consultant, agencies, true);

    assertThat(result.getConsultantId()).isEqualTo("consultantId");
    assertThat(result.getFirstName()).isEqualTo("Firstname");
    assertThat(result.getLastName()).isEqualTo("Lastname");
    assertThat(result.getIsSupervisor()).isTrue();
    assertThat(result.getAgencies()).hasSize(1);
  }

  @Test
  void consultantResponseDtoOf_Should_NotMapNames_When_MapNamesIsFalse() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    Consultant consultant =
        Consultant.builder()
            .id("consultantId")
            .matrixUserId("rcId")
            .username("username")
            .firstName("Firstname")
            .lastName("Lastname")
            .email("mail@example.com")
            .supervisor(false)
            .build();

    var result = consultantDtoMapper.consultantResponseDtoOf(consultant, List.of(), false);

    assertThat(result.getFirstName()).isNull();
    assertThat(result.getLastName()).isNull();
    assertThat(result.getIsSupervisor()).isFalse();
  }

  @Test
  void consultantResponseDtoOf_Should_MapAbsence_When_ConsultantIsAbsent() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    Consultant consultant =
        Consultant.builder()
            .id("consultantId")
            .matrixUserId("rcId")
            .username("username")
            .firstName("Firstname")
            .lastName("Lastname")
            .email("mail@example.com")
            .absent(true)
            .absenceMessage("I am out of office")
            .build();

    var result = consultantDtoMapper.consultantResponseDtoOf(consultant, List.of(), false);

    // Asserted on the wire format, not on the accessor: the generated DTO's
    // optional-string representation is not stable across environments, while
    // the JSON the browser reads is exactly the contract that matters here.
    assertThat(serialize(result))
        .contains("\"absent\":true")
        .contains("\"absenceMessage\":\"I am out of office\"");
  }

  @Test
  void consultantResponseDtoOf_Should_NotExposeAbsenceMessage_When_ConsultantIsNotAbsent() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    Consultant consultant =
        Consultant.builder()
            .id("consultantId")
            .matrixUserId("rcId")
            .username("username")
            .firstName("Firstname")
            .lastName("Lastname")
            .email("mail@example.com")
            .absent(false)
            .absenceMessage("I am out of office")
            .build();

    var result = consultantDtoMapper.consultantResponseDtoOf(consultant, List.of(), false);

    assertThat(serialize(result)).contains("\"absent\":false").doesNotContain("I am out of office");
  }

  private String serialize(Object dto) {
    return JsonMapper.builder().build().writeValueAsString(dto);
  }

  @Test
  void consultantLinkOf_Should_BuildGetLink_When_MethodIsDefault() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();

    var link = consultantDtoMapper.consultantLinkOf("consultantId", MethodEnum.GET);

    assertThat(link.getHref()).contains("consultantId");
    assertThat(link.getMethod()).isEqualTo(MethodEnum.GET);
  }

  @Test
  void consultantLinkOf_Should_BuildPutLink_When_MethodIsPut() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();

    var link = consultantDtoMapper.consultantLinkOf("consultantId", MethodEnum.PUT);

    assertThat(link.getHref()).contains("consultantId");
    assertThat(link.getMethod()).isEqualTo(MethodEnum.PUT);
  }

  @Test
  void consultantLinkOf_Should_BuildDeleteLink_When_MethodIsDelete() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();

    var link = consultantDtoMapper.consultantLinkOf("consultantId", MethodEnum.DELETE);

    assertThat(link.getHref()).contains("consultantId");
    assertThat(link.getMethod()).isEqualTo(MethodEnum.DELETE);
  }

  @Test
  void consultantAgencyLinkOf_Should_BuildPostLink_When_MethodIsPost() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();

    var link = consultantDtoMapper.consultantAgencyLinkOf("consultantId", MethodEnum.POST);

    assertThat(link.getHref()).contains("consultantId");
    assertThat(link.getMethod()).isEqualTo(MethodEnum.POST);
  }

  @Test
  void consultantAgencyLinkOf_Should_BuildGetLink_When_MethodIsGet() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();

    var link = consultantDtoMapper.consultantAgencyLinkOf("consultantId", MethodEnum.GET);

    assertThat(link.getHref()).contains("consultantId");
    assertThat(link.getMethod()).isEqualTo(MethodEnum.GET);
  }

  @Test
  void consultantLinksOf_Should_BuildAllFiveLinks() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    Map<String, Object> map = new HashMap<>();
    map.put("id", "consultantId");

    var links = consultantDtoMapper.consultantLinksOf(map);

    assertThat(links.getSelf().getHref()).contains("consultantId");
    assertThat(links.getUpdate().getMethod()).isEqualTo(MethodEnum.PUT);
    assertThat(links.getDelete().getMethod()).isEqualTo(MethodEnum.DELETE);
    assertThat(links.getAgencies().getMethod()).isEqualTo(MethodEnum.GET);
    assertThat(links.getAddAgency().getMethod()).isEqualTo(MethodEnum.POST);
  }

  @Test
  void pageLinkOf_Should_BuildSelfLink() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();

    var link = consultantDtoMapper.pageLinkOf("query", 1, 20, "LAST_NAME", "ASC");

    assertThat(link.getMethod()).isEqualTo(MethodEnum.GET);
    assertThat(link.getHref()).contains("query");
  }

  private Map<String, Object> givenAResultMap(boolean isFirstPage, boolean isLastPage) {
    Map<String, Object> resultMap = new HashMap<>();
    List<Map<String, Object>> consultantMaps = new ArrayList<>();
    consultantMaps.add(consultantMap());
    resultMap.put("consultants", consultantMaps);
    resultMap.put("totalElements", 1);
    resultMap.put("isFirstPage", isFirstPage);
    resultMap.put("isLastPage", isLastPage);
    return resultMap;
  }

  @Test
  void consultantSearchResultOf_Should_SkipTopicLookup_When_NoTopicIdsFoundForAnyConsultant() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    when(identityManager.hasRole(anyString(), any(UserRole.class))).thenReturn(false);
    when(consultantTopicRepository.findTopicIdsByConsultantIdIn(any())).thenReturn(List.of());

    var result =
        consultantDtoMapper.consultantSearchResultOf(
            givenAResultMap(true, true), "query", 0, 20, "LAST_NAME", "ASC");

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getEmbedded()).hasSize(1);
    assertThat(result.getEmbedded().get(0).getEmbedded().getTopics()).isEmpty();
    assertThat(result.getLinks().getPrevious()).isNull();
    assertThat(result.getLinks().getNext()).isNull();
  }

  @Test
  void consultantSearchResultOf_Should_EnrichTopics_When_TopicIdsFoundForConsultant() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    when(identityManager.hasRole(anyString(), any(UserRole.class))).thenReturn(false);
    List<Object[]> rows = java.util.Collections.singletonList(new Object[] {"consultant-id", 42L});
    when(consultantTopicRepository.findTopicIdsByConsultantIdIn(any())).thenReturn(rows);
    var topicDto = new TopicDTO();
    topicDto.setId(42L);
    topicDto.setName("Topic Name");
    when(topicService.getAllTopicsMap()).thenReturn(Map.of(42L, topicDto));

    var result =
        consultantDtoMapper.consultantSearchResultOf(
            givenAResultMap(true, true), "query", 0, 20, "LAST_NAME", "ASC");

    var topics = result.getEmbedded().get(0).getEmbedded().getTopics();
    assertThat(topics).hasSize(1);
    assertThat(topics.get(0).getName()).isEqualTo("Topic Name");
  }

  @Test
  void consultantSearchResultOf_Should_AddPreviousAndNextLinks_When_NotFirstOrLastPage() {
    ConsultantDtoMapper consultantDtoMapper = givenAMapper();
    when(identityManager.hasRole(anyString(), any(UserRole.class))).thenReturn(false);
    when(consultantTopicRepository.findTopicIdsByConsultantIdIn(any())).thenReturn(List.of());

    var result =
        consultantDtoMapper.consultantSearchResultOf(
            givenAResultMap(false, false), "query", 1, 20, "LAST_NAME", "ASC");

    assertThat(result.getLinks().getPrevious()).isNotNull();
    assertThat(result.getLinks().getNext()).isNotNull();
  }
}
