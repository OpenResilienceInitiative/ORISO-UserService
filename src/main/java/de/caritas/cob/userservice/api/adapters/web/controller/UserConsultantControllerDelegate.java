package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.helper.EmailUrlDecoder;
import jakarta.validation.constraints.NotNull;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserConsultantControllerDelegate {

  private final @NotNull AuthenticatedUser authenticatedUser;
  private final @NotNull ConsultantAgencyService consultantAgencyService;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull ConsultantDtoMapper consultantDtoMapper;
  private final @NonNull ConsultantService consultantService;
  private final @NotNull AdminUserFacade adminUserFacade;

  ResponseEntity<LanguageResponseDTO> getLanguages(Long agencyId) {
    var languageCodes = consultantAgencyService.getLanguageCodesOfAgency(agencyId);
    var languageResponseDTO = consultantDtoMapper.languageResponseDtoOf(languageCodes);

    return new ResponseEntity<>(languageResponseDTO, HttpStatus.OK);
  }

  ResponseEntity<List<ConsultantResponseDTO>> getConsultants(Long agencyId) {
    var consultants = consultantAgencyService.getConsultantsOfAgency(agencyId);

    return isNotEmpty(consultants)
        ? new ResponseEntity<>(consultants, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  ResponseEntity<ConsultantSearchResultDTO> searchConsultants(
      String query, Integer page, Integer perPage, String field, String order) {
    var decodedInfix = determineDecodedInfix(query).trim();
    var isAscending = order.equalsIgnoreCase("asc");
    var mappedField = consultantDtoMapper.mappedFieldOf(field);
    var resultMap =
        accountManager.findConsultantsByInfix(
            decodedInfix,
            authenticatedUser.hasRestrictedAgencyPriviliges(),
            getAgenciesToFilterConsultants(),
            page - 1,
            perPage,
            mappedField,
            isAscending);

    var result =
        consultantDtoMapper.consultantSearchResultOf(resultMap, query, page, perPage, field, order);

    if (authenticatedUser.hasRestrictedAgencyPriviliges() && result.getEmbedded() != null) {
      result
          .getEmbedded()
          .forEach(
              response ->
                  removeAgenciesWithoutAccessRight(response, getAgenciesToFilterConsultants()));
    }

    return ResponseEntity.ok(result);
  }

  ResponseEntity<ConsultantResponseDTO> getConsultantPublicData(UUID consultantId) {
    var consultantIdString = consultantId.toString();
    var consultant =
        consultantService
            .getConsultant(consultantIdString)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s not found", consultantIdString));
    var onlineAgencies = consultantAgencyService.getOnlineAgenciesOfConsultant(consultantIdString);
    var consultantDto =
        consultantDtoMapper.consultantResponseDtoOf(consultant, onlineAgencies, false);

    return new ResponseEntity<>(consultantDto, HttpStatus.OK);
  }

  private String determineDecodedInfix(String query) {
    if (EmailValidator.getInstance().isValid(query)) {
      return EmailUrlDecoder.decodeEmailQuery(query);
    } else {
      return URLDecoder.decode(query, StandardCharsets.UTF_8).trim();
    }
  }

  private void removeAgenciesWithoutAccessRight(
      ConsultantAdminResponseDTO response, Collection<Long> agenciesToFilterConsultants) {
    List<AgencyAdminResponseDTO> agencies = response.getEmbedded().getAgencies();
    List<AgencyAdminResponseDTO> filteredAgencies =
        agencies.stream()
            .filter(agency -> agenciesToFilterConsultants.contains(agency.getId()))
            .collect(Collectors.toList());
    response.getEmbedded().setAgencies(filteredAgencies);
  }

  private Collection<Long> getAgenciesToFilterConsultants() {
    Collection<Long> agenciesToFilterConsultants = Lists.newArrayList();
    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      agenciesToFilterConsultants =
          adminUserFacade.findAdminUserAgencyIds(authenticatedUser.getUserId());
    }
    return agenciesToFilterConsultants;
  }
}
