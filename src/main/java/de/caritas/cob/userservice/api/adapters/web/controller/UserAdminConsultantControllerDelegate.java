package de.caritas.cob.userservice.api.adapters.web.controller;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeletionPauseRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserIdentitiesDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.service.identity.UserIdentitiesService;
import java.util.List;
import java.util.Locale;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserAdminConsultantControllerDelegate {

  private final @NonNull ConsultantAdminFacade consultantAdminFacade;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull GrantConsultantIdentityService grantConsultantIdentityService;
  private final @NonNull UserIdentitiesService userIdentitiesService;

  ResponseEntity<ConsultantAdminResponseDTO> createConsultant(
      CreateConsultantDTO createConsultantDTO) {
    PlainCredentialsHolder.set(createConsultantDTO.getUsername(), null);
    createConsultantDTO.setEmail(createConsultantDTO.getEmail().toLowerCase(Locale.ROOT));
    return ResponseEntity.ok(consultantAdminFacade.createNewConsultant(createConsultantDTO));
  }

  ResponseEntity<ConsultantAdminResponseDTO> grantConsultantIdentity(
      String adminId, GrantConsultantIdentityDTO grantConsultantIdentityDTO) {
    return ResponseEntity.ok(
        grantConsultantIdentityService.grantConsultantIdentityToAdmin(
            adminId, grantConsultantIdentityDTO));
  }

  ResponseEntity<UserIdentitiesDTO> getUserIdentities(String userId) {
    return ResponseEntity.ok(userIdentitiesService.getUserIdentities(userId));
  }

  ResponseEntity<Void> createConsultantAgency(
      String consultantId, CreateConsultantAgencyDTO createConsultantAgencyDTO) {
    consultantAdminFacade.checkPermissionsToAssignedAgencies(
        Lists.newArrayList(createConsultantAgencyDTO));
    consultantAdminFacade.createNewConsultantAgency(consultantId, createConsultantAgencyDTO);
    return new ResponseEntity<>(HttpStatus.CREATED);
  }

  ResponseEntity<Void> setConsultantAgencies(
      String consultantId, List<CreateConsultantAgencyDTO> agencyList) {
    consultantAdminFacade.checkPermissionsToAssignedAgencies(agencyList);
    consultantAdminFacade.setConsultantAgencies(consultantId, agencyList);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<Void> deleteConsultantAgency(String consultantId, Long agencyId) {
    consultantAdminFacade.markConsultantAgencyForDeletion(consultantId, agencyId);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<Void> markConsultantForDeletion(String consultantId, Boolean forceDeleteSessions) {
    consultantAdminFacade.markConsultantForDeletion(consultantId, forceDeleteSessions);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<Void> pauseConsultantDeletion(
      String consultantId, DeletionPauseRequestDTO deletionPauseRequestDTO) {
    consultantAdminFacade.pauseConsultantDeletion(
        consultantId,
        deletionPauseRequestDTO.getReason(),
        deletionPauseRequestDTO.getMonths(),
        authenticatedUser.getUserId());
    return ResponseEntity.ok().build();
  }

  ResponseEntity<ConsultantAdminResponseDTO> updateConsultant(
      String consultantId, UpdateAdminConsultantDTO updateConsultantDTO) {
    if (updateConsultantDTO.getEmail() != null) {
      updateConsultantDTO.setEmail(updateConsultantDTO.getEmail().toLowerCase(Locale.ROOT));
    }
    return ResponseEntity.ok(
        consultantAdminFacade.updateConsultant(consultantId, updateConsultantDTO));
  }

  ResponseEntity<ConsultantAdminResponseDTO> getConsultant(String consultantId) {
    return ResponseEntity.ok(consultantAdminFacade.findConsultant(consultantId));
  }

  ResponseEntity<ConsultantSearchResultDTO> getConsultants(
      Integer page, Integer perPage, ConsultantFilter consultantFilter, Sort sort) {
    return ResponseEntity.ok(
        consultantAdminFacade.findFilteredConsultants(page, perPage, consultantFilter, sort));
  }

  ResponseEntity<AgencyConsultantResponseDTO> getAgencyConsultants(String agencyId) {
    return ResponseEntity.ok(consultantAdminFacade.findConsultantsForAgency(agencyId));
  }

  ResponseEntity<ConsultantAgencyResponseDTO> getConsultantAgencies(String consultantId) {
    return ResponseEntity.ok(consultantAdminFacade.findConsultantAgencies(consultantId));
  }

  ResponseEntity<Void> changeAgencyType(Long agencyId, AgencyTypeDTO agencyTypeDTO) {
    consultantAdminFacade.changeAgencyType(agencyId, agencyTypeDTO);
    return ResponseEntity.ok().build();
  }
}
