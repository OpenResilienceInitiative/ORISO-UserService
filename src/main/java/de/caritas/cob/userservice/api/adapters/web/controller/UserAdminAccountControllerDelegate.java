package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminAgencyRelationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.service.helper.EmailUrlDecoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserAdminAccountControllerDelegate {

  private final @NonNull AdminUserFacade adminUserFacade;
  private final @NonNull AdminDtoMapper adminDtoMapper;

  ResponseEntity<AdminResponseDTO> createTenantAdmin(CreateAdminDTO createAdminDTO) {
    createAdminDTO.setEmail(createAdminDTO.getEmail().toLowerCase());
    return ResponseEntity.ok(adminUserFacade.createNewTenantAdmin(createAdminDTO));
  }

  ResponseEntity<AdminResponseDTO> createAgencyAdmin(CreateAdminDTO createAdminDTO) {
    return ResponseEntity.ok(adminUserFacade.createNewAgencyAdmin(createAdminDTO));
  }

  ResponseEntity<AdminResponseDTO> getAgencyAdmin(String adminId) {
    return ResponseEntity.ok(adminUserFacade.findAgencyAdmin(adminId));
  }

  ResponseEntity<AdminResponseDTO> getTenantAdmin(String adminId) {
    return ResponseEntity.ok(adminUserFacade.findTenantAdmin(adminId));
  }

  ResponseEntity<List<AdminResponseDTO>> getTenantAdmins(Integer tenantId) {
    return ResponseEntity.ok(adminUserFacade.findTenantAdmins(tenantId));
  }

  ResponseEntity<List<Long>> getAdminAgencies(String adminId) {
    return ResponseEntity.ok(adminUserFacade.findAdminUserAgencyIds(adminId));
  }

  ResponseEntity<AdminSearchResultDTO> getAgencyAdmins(
      Integer page, Integer perPage, AdminFilter filter, Sort sort) {
    return new ResponseEntity<>(
        adminUserFacade.findFilteredAdminsAgency(page, perPage, filter, sort), HttpStatus.OK);
  }

  ResponseEntity<Void> deleteAgencyAdmin(String adminId) {
    adminUserFacade.deleteAgencyAdmin(adminId);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<Void> deleteTenantAdmin(String adminId) {
    adminUserFacade.deleteTenantAdmin(adminId);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<AdminResponseDTO> updateAgencyAdmin(
      String adminId, UpdateAgencyAdminDTO updateAgencyAdminDTO) {
    updateAgencyAdminDTO.setEmail(updateAgencyAdminDTO.getEmail().toLowerCase());
    return ResponseEntity.ok(adminUserFacade.updateAgencyAdmin(adminId, updateAgencyAdminDTO));
  }

  ResponseEntity<AdminResponseDTO> updateTenantAdmin(
      String adminId, UpdateTenantAdminDTO updateTenantAdminDTO) {
    updateTenantAdminDTO.setEmail(updateTenantAdminDTO.getEmail().toLowerCase());
    return ResponseEntity.ok(adminUserFacade.updateTenantAdmin(adminId, updateTenantAdminDTO));
  }

  ResponseEntity<Void> createAdminAgencyRelation(
      String adminId, CreateAdminAgencyRelationDTO createAdminAgencyRelationDTO) {
    adminUserFacade.createNewAdminAgencyRelation(adminId, createAdminAgencyRelationDTO);
    return new ResponseEntity<>(HttpStatus.CREATED);
  }

  ResponseEntity<Void> deleteAdminAgencyRelation(String adminId, Long agencyId) {
    adminUserFacade.deleteAdminAgencyRelation(adminId, agencyId);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<Void> setAdminAgenciesRelation(
      String adminId, List<CreateAdminAgencyRelationDTO> newAdminAgencyRelationDTOs) {
    adminUserFacade.setAdminAgenciesRelation(adminId, newAdminAgencyRelationDTOs);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<AdminResponseDTO> patchAdminData(PatchAdminDTO patchAdminDTO) {
    return ResponseEntity.ok(adminUserFacade.patchAdminUserData(patchAdminDTO));
  }

  ResponseEntity<AdminSearchResultDTO> searchAgencyAdmins(
      String query, Integer page, Integer perPage, String field, String order) {
    var decodedInfix = determineDecodedInfix(query);
    var isAscending = order.equalsIgnoreCase("asc");
    var mappedField = adminDtoMapper.mappedFieldOf(field);
    var resultMap =
        adminUserFacade.findAgencyAdminsByInfix(
            decodedInfix, page - 1, perPage, mappedField, isAscending);
    return buildSearchResponse(resultMap, query, page, perPage, field, order);
  }

  ResponseEntity<AdminSearchResultDTO> searchTenantAdmins(
      String query, Integer page, Integer perPage, String field, String order) {
    var decodedInfix = determineDecodedInfix(query);
    var isAscending = order.equalsIgnoreCase("asc");
    var mappedField = adminDtoMapper.mappedFieldOf(field);
    var resultMap =
        adminUserFacade.findTenantAdminsByInfix(
            decodedInfix, page - 1, perPage, mappedField, isAscending);
    return buildSearchResponse(resultMap, query, page, perPage, field, order);
  }

  private ResponseEntity<AdminSearchResultDTO> buildSearchResponse(
      Map<String, Object> resultMap,
      String query,
      Integer page,
      Integer perPage,
      String field,
      String order) {
    return ResponseEntity.ok(
        adminDtoMapper.adminSearchResultOf(resultMap, query, page, perPage, field, order));
  }

  private String determineDecodedInfix(String query) {
    return EmailValidator.getInstance().isValid(query)
        ? EmailUrlDecoder.decodeEmailQuery(query)
        : URLDecoder.decode(query, StandardCharsets.UTF_8).trim();
  }
}
