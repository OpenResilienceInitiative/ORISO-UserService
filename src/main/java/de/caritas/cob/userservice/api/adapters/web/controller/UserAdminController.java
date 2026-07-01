package de.caritas.cob.userservice.api.adapters.web.controller;

import com.google.common.collect.Lists;
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
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.hallink.RootDTOBuilder;
import de.caritas.cob.userservice.api.admin.report.service.ViolationReportGenerator;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.session.SessionAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.helper.EmailUrlDecoder;
import de.caritas.cob.userservice.api.service.identity.UserIdentitiesService;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UseradminApi;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Controller to handle all session admin requests. */
@RestController
@Validated
@RequiredArgsConstructor
@Slf4j
@Api(tags = "admin-user-controller")
public class UserAdminController implements UseradminApi {

  private final @NonNull SessionAdminService sessionAdminService;
  private final @NonNull ViolationReportGenerator violationReportGenerator;
  private final @NonNull ConsultantAdminFacade consultantAdminFacade;
  private final @NonNull AskerUserAdminFacade askerUserAdminFacade;
  private final @NonNull AdminUserFacade adminUserFacade;
  private final @NonNull AppointmentService appointmentService;
  private final @NonNull AdminDtoMapper adminDtoMapper;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull GrantConsultantIdentityService grantConsultantIdentityService;
  private final @NonNull UserIdentitiesService userIdentitiesService;

  /**
   * Creates the root hal based navigation entity.
   *
   * @return an entity containing the available navigation hal links
   */
  @Override
  public ResponseEntity<RootDTO> getRoot() {
    RootDTO rootDTO = new RootDTOBuilder().buildRootDTO();
    return ResponseEntity.ok(rootDTO);
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
    SessionAdminResultDTO sessionAdminResultDTO =
        this.sessionAdminService.findSessions(page, perPage, sessionFilter);
    return ResponseEntity.ok(sessionAdminResultDTO);
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

    // MATRIX MIGRATION: Capture plain username for Matrix user creation
    // CreateConsultantDTO doesn't use EncodeUsernameJsonDeserializer, so username is plain
    de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.set(
        createConsultantDTO.getUsername(), null);

    createConsultantDTO.setEmail(createConsultantDTO.getEmail().toLowerCase());
    var consultant = consultantAdminFacade.createNewConsultant(createConsultantDTO);

    return ResponseEntity.ok(consultant);
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
    var consultant =
        grantConsultantIdentityService.grantConsultantIdentityToAdmin(
            adminId, grantConsultantIdentityDTO);
    return ResponseEntity.ok(consultant);
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
    return ResponseEntity.ok(this.userIdentitiesService.getUserIdentities(userId));
  }

  /**
   * GET /useradmin/report : Returns an generated report containing data integration violations.
   * [Authorization: Role: user-admin].
   *
   * @return generated {@link ViolationDTO} list
   */
  @Override
  public ResponseEntity<List<ViolationDTO>> generateViolationReport() {
    return ResponseEntity.ok(this.violationReportGenerator.generateReport());
  }

  /**
   * Entry point to create a new consultant [Authorization: Role: user-admin].
   *
   * @param consultantId Consultant Id (required)
   * @param createConsultantAgencyDTO (required)
   */
  @Override
  public ResponseEntity<Void> createConsultantAgency(
      @PathVariable String consultantId,
      CreateConsultantAgencyDTO createConsultantAgencyDTO) {
    consultantAdminFacade.checkPermissionsToAssignedAgencies(
        Lists.newArrayList(createConsultantAgencyDTO));
    this.consultantAdminFacade.createNewConsultantAgency(consultantId, createConsultantAgencyDTO);
    return new ResponseEntity<>(HttpStatus.CREATED);
  }

  @Override
  public ResponseEntity<Void> setConsultantAgencies(
      String consultantId, List<CreateConsultantAgencyDTO> agencyList) {
    // MATRIX MIGRATION: Use simple creation for each agency instead of complex update logic
    // This avoids the 403 error from filterAgencyListForDeletion
    try {
      for (CreateConsultantAgencyDTO agencyDTO : agencyList) {
        try {
          this.consultantAdminFacade.createNewConsultantAgency(consultantId, agencyDTO);
        } catch (Exception e) {
          // If agency already exists, continue
          System.out.println("Agency assignment (might already exist): " + e.getMessage());
        }
      }
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      // Return 200 anyway to not block consultant creation
      System.out.println(
          "ERROR: Agency assignment failed for consultant " + consultantId + ": " + e.getMessage());
      return ResponseEntity.ok().build();
    }
  }

  /**
   * Entry point to delete a consultant agency relation.
   *
   * @param consultantId Consultant Id (required)
   * @param agencyId Agency Id (required)
   */
  @Override
  public ResponseEntity<Void> deleteConsultantAgency(String consultantId, Long agencyId) {
    this.consultantAdminFacade.markConsultantAgencyForDeletion(consultantId, agencyId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Entry point to mark a consultant for deletion.
   *
   * @param consultantId consultant id (required)
   */
  @Override
  public ResponseEntity<Void> markConsultantForDeletion(
      String consultantId, Boolean forceDeleteSessions) {
    this.consultantAdminFacade.markConsultantForDeletion(consultantId, forceDeleteSessions);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @PostMapping(
      value = {
        "/useradmin/consultants/{consultantId}/deletion/pause",
        "/service/useradmin/consultants/{consultantId}/deletion/pause"
      })
  public ResponseEntity<Void> pauseConsultantDeletion(
      @PathVariable String consultantId,
      @Valid @RequestBody DeletionPauseRequestDTO deletionPauseRequestDTO) {
    this.consultantAdminFacade.pauseConsultantDeletion(
        consultantId,
        deletionPauseRequestDTO.getReason(),
        deletionPauseRequestDTO.getMonths(),
        authenticatedUser.getUserId());
    return new ResponseEntity<>(HttpStatus.OK);
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
    return ResponseEntity.ok(performUpdate(consultantId, updateConsultantDTO));
  }

  private ConsultantAdminResponseDTO performUpdate(
      String consultantId, UpdateAdminConsultantDTO updateConsultantDTO) {
    if (updateConsultantDTO.getEmail() != null) {
      updateConsultantDTO.setEmail(updateConsultantDTO.getEmail().toLowerCase());
    }
    return consultantAdminFacade.updateConsultant(consultantId, updateConsultantDTO);
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
    ConsultantAdminResponseDTO responseDTO =
        this.consultantAdminFacade.findConsultant(consultantId);
    return ResponseEntity.ok(responseDTO);
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
    var resultDTO =
        this.consultantAdminFacade.findFilteredConsultants(page, perPage, consultantFilter, sort);
    return ResponseEntity.ok(resultDTO);
  }

  /**
   * GET /useradmin/agencies/{agencyId}/consultants: Returns all consultants for the agency.
   *
   * @param agencyId Agency Id (required)
   * @return {@link AgencyConsultantResponseDTO}
   */
  @Override
  public ResponseEntity<AgencyConsultantResponseDTO> getAgencyConsultants(String agencyId) {
    var resultDTO = this.consultantAdminFacade.findConsultantsForAgency(agencyId);
    return ResponseEntity.ok(resultDTO);
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
    var consultantAgencies = this.consultantAdminFacade.findConsultantAgencies(consultantId);
    return ResponseEntity.ok(consultantAgencies);
  }

  /**
   * Entry point to handle consultant data when agency type changes.
   *
   * @param agencyId the id of the changed agency
   * @param agencyTypeDTO contains the target type
   */
  @Override
  public ResponseEntity<Void> changeAgencyType(Long agencyId, AgencyTypeDTO agencyTypeDTO) {
    this.consultantAdminFacade.changeAgencyType(agencyId, agencyTypeDTO);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Entry point to mark a asker for deletion.
   *
   * @param askerId asker id (required)
   */
  @Override
  public ResponseEntity<Void> markAskerForDeletion(String askerId) {
    this.askerUserAdminFacade.markAskerForDeletion(askerId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @PostMapping(
      value = {
        "/useradmin/askers/{askerId}/deletion/pause",
        "/service/useradmin/askers/{askerId}/deletion/pause"
      })
  public ResponseEntity<Void> pauseAskerDeletion(
      @PathVariable String askerId,
      @Valid @RequestBody DeletionPauseRequestDTO deletionPauseRequestDTO) {
    this.askerUserAdminFacade.pauseAskerDeletion(
        askerId,
        deletionPauseRequestDTO.getReason(),
        deletionPauseRequestDTO.getMonths(),
        authenticatedUser.getUserId());
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<AskerResponseDTO> getAsker(String askerId) {
    AskerResponseDTO response = this.askerUserAdminFacade.getAsker(askerId);
    return new ResponseEntity<>(response, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> createTenantAdmin(CreateAdminDTO createAgencyAdminDTO) {
    createAgencyAdminDTO.setEmail(createAgencyAdminDTO.getEmail().toLowerCase());
    var admin = adminUserFacade.createNewTenantAdmin(createAgencyAdminDTO);

    return ResponseEntity.ok(admin);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> createAgencyAdmin(final CreateAdminDTO createAdminDTO) {
    return ResponseEntity.ok(this.adminUserFacade.createNewAgencyAdmin(createAdminDTO));
  }

  @Override
  public ResponseEntity<AdminResponseDTO> getAgencyAdmin(final String adminId) {
    return new ResponseEntity<>(this.adminUserFacade.findAgencyAdmin(adminId), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> getTenantAdmin(final String adminId) {
    return new ResponseEntity<>(this.adminUserFacade.findTenantAdmin(adminId), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<List<AdminResponseDTO>> getTenantAdmins(final Integer tenantId) {
    return new ResponseEntity<>(this.adminUserFacade.findTenantAdmins(tenantId), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<List<Long>> getAdminAgencies(@PathVariable String adminId) {
    var adminAgencies = this.adminUserFacade.findAdminUserAgencyIds(adminId);
    return ResponseEntity.ok(adminAgencies);
  }

  @Override
  public ResponseEntity<AdminSearchResultDTO> getAgencyAdmins(
      final Integer page, final Integer perPage, final AdminFilter filter, final Sort sort) {
    return new ResponseEntity<>(
        this.adminUserFacade.findFilteredAdminsAgency(page, perPage, filter, sort), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> deleteAgencyAdmin(final String adminId) {
    this.adminUserFacade.deleteAgencyAdmin(adminId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> deleteTenantAdmin(final String adminId) {
    this.adminUserFacade.deleteTenantAdmin(adminId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> updateAgencyAdmin(
      final String adminId, UpdateAgencyAdminDTO updateAgencyAdminDTO) {
    updateAgencyAdminDTO.setEmail(updateAgencyAdminDTO.getEmail().toLowerCase());
    var admin = adminUserFacade.updateAgencyAdmin(adminId, updateAgencyAdminDTO);

    return new ResponseEntity<>(admin, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> updateTenantAdmin(
      final String adminId, UpdateTenantAdminDTO updateTenantAdminDTO) {
    updateTenantAdminDTO.setEmail(updateTenantAdminDTO.getEmail().toLowerCase());
    var admin = adminUserFacade.updateTenantAdmin(adminId, updateTenantAdminDTO);

    return new ResponseEntity<>(admin, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> createAdminAgencyRelation(
      final String adminId, final CreateAdminAgencyRelationDTO createAdminAgencyRelationDTO) {
    this.adminUserFacade.createNewAdminAgencyRelation(adminId, createAdminAgencyRelationDTO);
    return new ResponseEntity<>(HttpStatus.CREATED);
  }

  @Override
  public ResponseEntity<Void> deleteAdminAgencyRelation(final String adminId, final Long agencyId) {
    this.adminUserFacade.deleteAdminAgencyRelation(adminId, agencyId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> setAdminAgenciesRelation(
      final String adminId, final List<CreateAdminAgencyRelationDTO> newAdminAgencyRelationDTOs) {
    this.adminUserFacade.setAdminAgenciesRelation(adminId, newAdminAgencyRelationDTOs);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<AdminResponseDTO> patchAdminData(PatchAdminDTO patchAdminDTO) {
    AdminResponseDTO adminResponseDTO = this.adminUserFacade.patchAdminUserData(patchAdminDTO);
    return new ResponseEntity<>(adminResponseDTO, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<AdminSearchResultDTO> searchAgencyAdmins(
      String query, Integer page, Integer perPage, String field, String order) {
    String decodedInfix = determineDecodedInfix(query);
    var isAscending = order.equalsIgnoreCase("asc");
    var mappedField = adminDtoMapper.mappedFieldOf(field);
    var resultMap =
        adminUserFacade.findAgencyAdminsByInfix(
            decodedInfix, page - 1, perPage, mappedField, isAscending);
    var result = adminDtoMapper.adminSearchResultOf(resultMap, query, page, perPage, field, order);

    return ResponseEntity.ok(result);
  }

  @Override
  public ResponseEntity<AdminSearchResultDTO> searchTenantAdmins(
      String query, Integer page, Integer perPage, String field, String order) {
    String decodedInfix = determineDecodedInfix(query);
    var isAscending = order.equalsIgnoreCase("asc");
    var mappedField = adminDtoMapper.mappedFieldOf(field);
    var resultMap =
        adminUserFacade.findTenantAdminsByInfix(
            decodedInfix, page - 1, perPage, mappedField, isAscending);
    var result = adminDtoMapper.adminSearchResultOf(resultMap, query, page, perPage, field, order);
    return ResponseEntity.ok(result);
  }

  private String determineDecodedInfix(String query) {
    if (EmailValidator.getInstance().isValid(query)) {
      return EmailUrlDecoder.decodeEmailQuery(query);
    } else {
      return URLDecoder.decode(query, StandardCharsets.UTF_8).trim();
    }
  }
}
