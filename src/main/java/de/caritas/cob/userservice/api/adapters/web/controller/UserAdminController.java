package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminAgencyRelationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeletionPauseRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.RootDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAdminResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserIdentitiesDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UseradminApi;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller to handle all session admin requests. */
@RestController
@Validated
@RequiredArgsConstructor
@Api(tags = "admin-user-controller")
public class UserAdminController implements UseradminApi {

  private final @NonNull UserAdminQueryControllerDelegate queryDelegate;
  private final @NonNull UserAdminConsultantControllerDelegate consultantDelegate;
  private final @NonNull UserAdminAskerControllerDelegate askerDelegate;
  private final @NonNull UserAdminAccountControllerDelegate accountDelegate;

  /**
   * Creates the root hal based navigation entity.
   *
   * @return an entity containing the available navigation hal links
   */
  @Override
  public ResponseEntity<RootDTO> getRoot() {
    return queryDelegate.getRoot();
  }

  /**
   * Entry point to retrieve sessions.
   *
   * @param page Number of page where to start in the query (1 = first page) (required)
   * @param perPage Number of items which are being returned (required)
   * @param sessionFilter The filters to restrict results (optional)
   * @return an entity containing the filtered sessions
   */
  @Override
  public ResponseEntity<SessionAdminResultDTO> getSessions(
      Integer page, Integer perPage, SessionFilter sessionFilter) {
    return queryDelegate.getSessions(page, perPage, sessionFilter);
  }

  /**
   * Entry point to create a new consultant.
   *
   * @param createConsultantDTO (required)
   * @return {@link ConsultantAdminResponseDTO}
   */
  @Override
  public ResponseEntity<ConsultantAdminResponseDTO> createConsultant(
      CreateConsultantDTO createConsultantDTO) {
    return consultantDelegate.createConsultant(createConsultantDTO);
  }

  /**
   * Grants an existing admin user a full functional consultant identity (multi-identity
   * foundation). Mirrors {@link #createConsultant} and returns the created consultant identity.
   *
   * <p>Mapped to both {@code /useradmin/admins/{adminId}/grant-consultant-identity} (direct) and
   * the {@code /service}-prefixed variant (via API gateway) so internal service calls work without
   * relying on the gateway to strip the {@code /service} prefix.
   *
   * @param adminId the Keycloak id of the existing admin user (required)
   * @param grantConsultantIdentityDTO the consultant-specific attributes (required)
   * @return {@link ConsultantAdminResponseDTO}
   */
  @PostMapping(
      value = {
        "/useradmin/admins/{adminId}/grant-consultant-identity",
        "/service/useradmin/admins/{adminId}/grant-consultant-identity"
      },
      produces = "application/hal+json",
      consumes = "application/json")
  public ResponseEntity<ConsultantAdminResponseDTO> grantConsultantIdentity(
      @PathVariable String adminId,
      @Valid @RequestBody GrantConsultantIdentityDTO grantConsultantIdentityDTO) {
    return consultantDelegate.grantConsultantIdentity(adminId, grantConsultantIdentityDTO);
  }

  /**
   * Returns which platform identities the given user currently holds (admin row, non-deleted
   * consultant row and Keycloak realm roles). Data source for the admin-panel "has rights
   * elsewhere" badge.
   *
   * <p>Mapped to both {@code /useradmin/users/{userId}/identities} (direct) and the {@code
   * /service}-prefixed variant (via API gateway).
   *
   * @param userId the Keycloak id of the user (required)
   * @return {@link UserIdentitiesDTO}
   */
  @GetMapping(
      value = {
        "/useradmin/users/{userId}/identities",
        "/service/useradmin/users/{userId}/identities"
      })
  public ResponseEntity<UserIdentitiesDTO> getUserIdentities(@PathVariable String userId) {
    return consultantDelegate.getUserIdentities(userId);
  }

  /**
   * GET /useradmin/report : Returns an generated report containing data integration violations.
   * [Authorization: Role: user-admin].
   *
   * @return generated {@link ViolationDTO} list
   */
  @Override
  public ResponseEntity<List<ViolationDTO>> generateViolationReport() {
    return queryDelegate.generateViolationReport();
  }

  /**
   * Entry point to create a new consultant [Authorization: Role: user-admin].
   *
   * @param consultantId Consultant Id (required)
   * @param createConsultantAgencyDTO (required)
   */
  @Override
  public ResponseEntity<Void> createConsultantAgency(
      @PathVariable String consultantId, CreateConsultantAgencyDTO createConsultantAgencyDTO) {
    return consultantDelegate.createConsultantAgency(consultantId, createConsultantAgencyDTO);
  }

  @Override
  public ResponseEntity<Void> setConsultantAgencies(
      String consultantId, List<CreateConsultantAgencyDTO> agencyList) {
    return consultantDelegate.setConsultantAgencies(consultantId, agencyList);
  }

  /**
   * Entry point to delete a consultant agency relation.
   *
   * @param consultantId Consultant Id (required)
   * @param agencyId Agency Id (required)
   */
  @Override
  public ResponseEntity<Void> deleteConsultantAgency(String consultantId, Long agencyId) {
    return consultantDelegate.deleteConsultantAgency(consultantId, agencyId);
  }

  /**
   * Entry point to mark a consultant for deletion.
   *
   * @param consultantId consultant id (required)
   */
  @DeleteMapping(
      value = {
        "/useradmin/consultants/{consultantId}",
        "/service/useradmin/consultants/{consultantId}"
      })
  @Override
  public ResponseEntity<Void> markConsultantForDeletion(
      @PathVariable String consultantId,
      @RequestParam(required = false, defaultValue = "false") Boolean forceDeleteSessions) {
    return consultantDelegate.markConsultantForDeletion(consultantId, forceDeleteSessions);
  }

  @PostMapping(
      value = {
        "/useradmin/consultants/{consultantId}/deletion/pause",
        "/service/useradmin/consultants/{consultantId}/deletion/pause"
      })
  public ResponseEntity<Void> pauseConsultantDeletion(
      @PathVariable String consultantId,
      @Valid @RequestBody DeletionPauseRequestDTO deletionPauseRequestDTO) {
    return consultantDelegate.pauseConsultantDeletion(consultantId, deletionPauseRequestDTO);
  }

  /**
   * Entry point to update a consultant. Accepts the full {@link UpdateAdminConsultantDTO} including
   * an optional {@code topicIds} list that fully replaces the consultant's current topics (add new
   * ids, drop removed ids).
   *
   * <p>Mapped to both {@code /useradmin/consultants/{consultantId}} (direct) and {@code
   * /service/useradmin/consultants/{consultantId}} (via API gateway) so Postman and internal
   * service calls work without relying on the gateway to strip the {@code /service} prefix.
   *
   * @param consultantId consultant id (required)
   * @param updateConsultantDTO update payload (required)
   * @return {@link ConsultantAdminResponseDTO}
   */
  @PutMapping(
      value = {
        "/useradmin/consultants/{consultantId}",
        "/service/useradmin/consultants/{consultantId}"
      },
      produces = "application/hal+json",
      consumes = "application/json")
  @Override
  public ResponseEntity<ConsultantAdminResponseDTO> updateConsultant(
      @PathVariable String consultantId, UpdateAdminConsultantDTO updateConsultantDTO) {
    return consultantDelegate.updateConsultant(consultantId, updateConsultantDTO);
  }

  /**
   * Entry point to get a specific consultant.
   *
   * @param consultantId consultant id (required)
   * @return {@link ConsultantAdminResponseDTO}
   */
  @Override
  public ResponseEntity<ConsultantAdminResponseDTO> getConsultant(
      @PathVariable String consultantId) {
    return consultantDelegate.getConsultant(consultantId);
  }

  /**
   * Entry point to retrieve consultants.
   *
   * @param page Number of page where to start in the query (1 &#x3D; first page) (required)
   * @param perPage Number of items which are being returned per page (required)
   * @param consultantFilter The filter parameters to search for. If no filter is set all consultant
   *     are being returned. (optional)
   * @return an entity containing the filtered sessions
   */
  @Override
  public ResponseEntity<ConsultantSearchResultDTO> getConsultants(
      Integer page, Integer perPage, ConsultantFilter consultantFilter, Sort sort) {
    return consultantDelegate.getConsultants(page, perPage, consultantFilter, sort);
  }

  /**
   * GET /useradmin/agencies/{agencyId}/consultants: Returns all consultants for the agency.
   *
   * @param agencyId Agency Id (required)
   * @return {@link AgencyConsultantResponseDTO}
   */
  @Override
  public ResponseEntity<AgencyConsultantResponseDTO> getAgencyConsultants(String agencyId) {
    return consultantDelegate.getAgencyConsultants(agencyId);
  }

  /**
   * GET /useradmin/consultant/{consultantId}/agencies: Returns all Agencies for the consultant with
   * given id.
   *
   * @param consultantId Consultant Id (required)
   * @return {@link ConsultantAgencyResponseDTO}s
   */
  @Override
  public ResponseEntity<ConsultantAgencyResponseDTO> getConsultantAgencies(
      @PathVariable String consultantId) {
    return consultantDelegate.getConsultantAgencies(consultantId);
  }

  /**
   * Entry point to handle consultant data when agency type changes.
   *
   * @param agencyId the id of the changed agency
   * @param agencyTypeDTO contains the target type
   */
  @Override
  public ResponseEntity<Void> changeAgencyType(Long agencyId, AgencyTypeDTO agencyTypeDTO) {
    return consultantDelegate.changeAgencyType(agencyId, agencyTypeDTO);
  }

  /**
   * Entry point to mark a asker for deletion.
   *
   * @param askerId asker id (required)
   */
  @Override
  public ResponseEntity<Void> markAskerForDeletion(String askerId) {
    return askerDelegate.markAskerForDeletion(askerId);
  }

  @PostMapping(
      value = {
        "/useradmin/askers/{askerId}/deletion/pause",
        "/service/useradmin/askers/{askerId}/deletion/pause"
      })
  public ResponseEntity<Void> pauseAskerDeletion(
      @PathVariable String askerId,
      @Valid @RequestBody DeletionPauseRequestDTO deletionPauseRequestDTO) {
    return askerDelegate.pauseAskerDeletion(askerId, deletionPauseRequestDTO);
  }

  @Override
  public ResponseEntity<AskerResponseDTO> getAsker(String askerId) {
    return askerDelegate.getAsker(askerId);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> createTenantAdmin(CreateAdminDTO createAgencyAdminDTO) {
    return accountDelegate.createTenantAdmin(createAgencyAdminDTO);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> createAgencyAdmin(final CreateAdminDTO createAdminDTO) {
    return accountDelegate.createAgencyAdmin(createAdminDTO);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> getAgencyAdmin(final String adminId) {
    return accountDelegate.getAgencyAdmin(adminId);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> getTenantAdmin(final String adminId) {
    return accountDelegate.getTenantAdmin(adminId);
  }

  @Override
  public ResponseEntity<List<AdminResponseDTO>> getTenantAdmins(final Integer tenantId) {
    return accountDelegate.getTenantAdmins(tenantId);
  }

  @Override
  public ResponseEntity<List<Long>> getAdminAgencies(@PathVariable String adminId) {
    return accountDelegate.getAdminAgencies(adminId);
  }

  @Override
  public ResponseEntity<AdminSearchResultDTO> getAgencyAdmins(
      final Integer page, final Integer perPage, final AdminFilter filter, final Sort sort) {
    return accountDelegate.getAgencyAdmins(page, perPage, filter, sort);
  }

  @Override
  public ResponseEntity<Void> deleteAgencyAdmin(final String adminId) {
    return accountDelegate.deleteAgencyAdmin(adminId);
  }

  @Override
  public ResponseEntity<Void> deleteTenantAdmin(final String adminId) {
    return accountDelegate.deleteTenantAdmin(adminId);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> updateAgencyAdmin(
      final String adminId, UpdateAgencyAdminDTO updateAgencyAdminDTO) {
    return accountDelegate.updateAgencyAdmin(adminId, updateAgencyAdminDTO);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> updateTenantAdmin(
      final String adminId, UpdateTenantAdminDTO updateTenantAdminDTO) {
    return accountDelegate.updateTenantAdmin(adminId, updateTenantAdminDTO);
  }

  @Override
  public ResponseEntity<Void> createAdminAgencyRelation(
      final String adminId, final CreateAdminAgencyRelationDTO createAdminAgencyRelationDTO) {
    return accountDelegate.createAdminAgencyRelation(adminId, createAdminAgencyRelationDTO);
  }

  @Override
  public ResponseEntity<Void> deleteAdminAgencyRelation(final String adminId, final Long agencyId) {
    return accountDelegate.deleteAdminAgencyRelation(adminId, agencyId);
  }

  @Override
  public ResponseEntity<Void> setAdminAgenciesRelation(
      final String adminId, final List<CreateAdminAgencyRelationDTO> newAdminAgencyRelationDTOs) {
    return accountDelegate.setAdminAgenciesRelation(adminId, newAdminAgencyRelationDTOs);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> patchAdminData(PatchAdminDTO patchAdminDTO) {
    return accountDelegate.patchAdminData(patchAdminDTO);
  }

  @Override
  public ResponseEntity<AdminSearchResultDTO> searchAgencyAdmins(
      String query, Integer page, Integer perPage, String field, String order) {
    return accountDelegate.searchAgencyAdmins(query, page, perPage, field, order);
  }

  @Override
  public ResponseEntity<AdminSearchResultDTO> searchTenantAdmins(
      String query, Integer page, Integer perPage, String field, String order) {
    return accountDelegate.searchTenantAdmins(query, page, perPage, field, order);
  }
}
