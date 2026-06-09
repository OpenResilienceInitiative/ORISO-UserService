package de.caritas.cob.userservice.api.adapters.web.mapping;

import static java.util.Objects.isNull;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import de.caritas.cob.userservice.api.adapters.web.controller.UserController;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantLinks;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantTopicDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.HalLink;
import de.caritas.cob.userservice.api.adapters.web.dto.HalLink.MethodEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.PaginationLinks;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UseradminApi;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ConsultantDtoMapper implements DtoMapperUtils {
  @Autowired private IdentityClient identityClient;
  @Autowired private ConsultantTopicRepository consultantTopicRepository;
  @Autowired private TopicService topicService;

  public UpdateAdminConsultantDTO updateAdminConsultantOf(
      UpdateConsultantDTO updateConsultantDTO, Consultant consultant) {
    var email = updateConsultantDTO.getEmail();

    return new UpdateAdminConsultantDTO()
        .email(isNull(email) ? null : email.toLowerCase())
        .firstname(updateConsultantDTO.getFirstname())
        .lastname(updateConsultantDTO.getLastname())
        .formalLanguage(consultant.isLanguageFormal())
        .languages(languageStringsOf(updateConsultantDTO.getLanguages()))
        .absent(consultant.isAbsent())
        .absenceMessage(consultant.getAbsenceMessage())
        .dataPrivacyConfirmation(updateConsultantDTO.getDataPrivacyConfirmation())
        .termsAndConditionsConfirmation(updateConsultantDTO.getTermsAndConditionsConfirmation());
  }

  public ConsultantResponseDTO consultantResponseDtoOf(
      Consultant consultant, List<AgencyDTO> agencies, boolean mapNames) {
    var agencyDtoList =
        agencies.stream().map(this::agencyResponseDtoOf).collect(Collectors.toList());

    var consultantResponseDto =
        new ConsultantResponseDTO()
            .consultantId(consultant.getId())
            .agencies(agencyDtoList)
            .isSupervisor(consultant.isSupervisor());

    if (mapNames) {
      consultantResponseDto.firstName(consultant.getFirstName()).lastName(consultant.getLastName());
    }

    return consultantResponseDto;
  }

  @SuppressWarnings("unchecked")
  public ConsultantSearchResultDTO consultantSearchResultOf(
      Map<String, Object> resultMap,
      String query,
      int page,
      int perPage,
      String field,
      String order) {
    var consultants = new ArrayList<ConsultantAdminResponseDTO>();

    var consultantMaps = (List<Map<String, Object>>) resultMap.get("consultants");
    var consultantIds =
        consultantMaps.stream()
            .map(consultantMap -> (String) consultantMap.get("id"))
            .collect(Collectors.toList());
    var topicIdsByConsultantId = topicIdsByConsultantId(consultantIds);
    var topicsById =
        topicIdsByConsultantId.isEmpty()
            ? Collections.<Long, TopicDTO>emptyMap()
            : topicService.getAllTopicsMap();
    consultantMaps.forEach(
        consultantMap -> {
          var response = new ConsultantAdminResponseDTO();
          var consultantDto = consultantDtoOf(consultantMap);
          consultantDto.setTopics(
              topicsOf(topicIdsByConsultantId.get(consultantDto.getId()), topicsById));
          response.setEmbedded(consultantDto);
          response.setLinks(consultantLinksOf(consultantMap));
          consultants.add(response);
        });

    var result = new ConsultantSearchResultDTO();
    result.setTotal((Integer) resultMap.get("totalElements"));
    result.setEmbedded(consultants);

    var pagination = new PaginationLinks().self(pageLinkOf(query, page, perPage, field, order));
    if (!(boolean) resultMap.get("isFirstPage")) {
      pagination.previous(pageLinkOf(query, page - 1, perPage, field, order));
    }
    if (!(boolean) resultMap.get("isLastPage")) {
      pagination.next(pageLinkOf(query, page + 1, perPage, field, order));
    }
    result.setLinks(pagination);

    return result;
  }

  private Map<String, List<Long>> topicIdsByConsultantId(Collection<String> consultantIds) {
    if (consultantIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return consultantTopicRepository.findTopicIdsByConsultantIdIn(consultantIds).stream()
        .collect(
            Collectors.groupingBy(
                row -> (String) row[0],
                Collectors.mapping(row -> (Long) row[1], Collectors.toList())));
  }

  private List<ConsultantTopicDTO> topicsOf(List<Long> topicIds, Map<Long, TopicDTO> topicsById) {
    if (topicIds == null || topicIds.isEmpty()) {
      return Collections.emptyList();
    }
    return topicIds.stream()
        .map(
            topicId -> {
              var topic = topicsById.get(topicId);
              return new ConsultantTopicDTO()
                  .id(topicId)
                  .name(topic != null ? topic.getName() : null);
            })
        .collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  public ConsultantDTO consultantDtoOf(Map<String, Object> consultantMap) {
    var consultant = new ConsultantDTO();
    consultant.setId((String) consultantMap.get("id"));
    consultant.setEmail((String) consultantMap.get("email"));
    consultant.setFirstname((String) consultantMap.get("firstName"));
    consultant.setLastname((String) consultantMap.get("lastName"));
    consultant.setUsername((String) consultantMap.get("username"));
    consultant.setStatus((String) consultantMap.get("status"));
    consultant.setAbsenceMessage((String) consultantMap.get("absenceMessage"));
    consultant.setAbsent((Boolean) consultantMap.get("isAbsent"));
    consultant.setFormalLanguage((Boolean) consultantMap.get("isLanguageFormal"));
    consultant.setTeamConsultant((Boolean) consultantMap.get("isTeamConsultant"));
    consultant.setCreateDate((String) consultantMap.get("createdAt"));
    consultant.setUpdateDate((String) consultantMap.get("updatedAt"));
    consultant.setDeleteDate((String) consultantMap.get("deletedAt"));
    consultant.setDisplayName((String) consultantMap.get("displayName"));
    consultant.setPublicName((String) consultantMap.get("displayName"));
    consultant.setVacated(consultantMap.get("deletedAt") != null);
    Long tenantId = (Long) consultantMap.get("tenantId");
    if (tenantId != null) {
      consultant.setTenantId(tenantId.intValue());
    }
    consultant.setTenantName((String) consultantMap.get("tenantName"));

    // Handle missing Keycloak users gracefully
    boolean isGroupChatConsultant = false;
    try {
      isGroupChatConsultant =
          identityClient.userHasRole(consultant.getId(), UserRole.GROUP_CHAT_CONSULTANT.getValue());
    } catch (Exception e) {
      // If user doesn't exist in Keycloak, assume they don't have the role
      log.debug("Could not check role for consultant {}: {}", consultant.getId(), e.getMessage());
    }
    consultant.setIsGroupchatConsultant(isGroupChatConsultant);

    // Get supervisor permission from database field (from map, not DTO)
    Boolean isSupervisor = (Boolean) consultantMap.get("isSupervisor");
    consultant.setIsSupervisor(isSupervisor != null && isSupervisor);
    consultant.setAdminRights(isSupervisor != null && isSupervisor);
    consultant.setRoleInOrg(
        isSupervisor != null && isSupervisor ? "Counsellor Admin" : "Counsellor");

    var agencies = new ArrayList<AgencyAdminResponseDTO>();
    var agencyMaps = (ArrayList<Map<String, Object>>) consultantMap.get("agencies");
    agencyMaps.forEach(
        agencyMap -> {
          var agency = new AgencyAdminResponseDTO();
          agency.setId((Long) agencyMap.get("id"));
          agency.setName((String) agencyMap.get("name"));
          agency.setPostcode((String) agencyMap.get("postcode"));
          agency.setCity((String) agencyMap.get("city"));
          agency.setDescription((String) agencyMap.get("description"));
          agency.setTeamAgency((Boolean) agencyMap.get("isTeamAgency"));
          agency.setOffline((Boolean) agencyMap.get("isOffline"));
          agency.setConsultingType((Integer) agencyMap.get("consultingType"));
          agencies.add(agency);
        });
    consultant.setAgencies(agencies);

    return consultant;
  }

  public ConsultantLinks consultantLinksOf(Map<String, Object> consultantMap) {
    var id = (String) consultantMap.get("id");

    return new ConsultantLinks()
        .self(consultantLinkOf(id, MethodEnum.GET))
        .update(consultantLinkOf(id, MethodEnum.PUT))
        .delete(consultantLinkOf(id, MethodEnum.DELETE))
        .agencies(consultantAgencyLinkOf(id, MethodEnum.GET))
        .addAgency(consultantAgencyLinkOf(id, MethodEnum.POST));
  }

  public HalLink consultantLinkOf(String id, MethodEnum method) {
    var userAdminApi = methodOn(UseradminApi.class);
    HttpEntity<?> httpEntity;
    switch (method) {
      case PUT:
        httpEntity = userAdminApi.updateConsultant(id, null);
        break;
      case DELETE:
        httpEntity = userAdminApi.markConsultantForDeletion(id, false);
        break;
      default:
        httpEntity = userAdminApi.getConsultant(id);
    }

    return halLinkOf(httpEntity, method);
  }

  public HalLink consultantAgencyLinkOf(String id, MethodEnum method) {
    var userAdminApi = methodOn(UseradminApi.class);
    HttpEntity<?> httpEntity;
    if (method == MethodEnum.POST) {
      httpEntity = userAdminApi.createConsultantAgency(id, null);
    } else {
      httpEntity = userAdminApi.getConsultantAgencies(id);
    }

    return halLinkOf(httpEntity, method);
  }

  public HalLink pageLinkOf(String query, int page, int perPage, String field, String order) {
    var httpEntity =
        methodOn(UserController.class).searchConsultants(query, page, perPage, field, order);

    return halLinkOf(httpEntity, MethodEnum.GET);
  }
}
