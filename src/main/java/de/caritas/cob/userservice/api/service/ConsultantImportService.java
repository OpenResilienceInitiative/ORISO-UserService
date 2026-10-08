package de.caritas.cob.userservice.api.service;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.exception.ImportException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class ConsultantImportService {

  @Value("${consultant.import.filename}")
  private String importFilename;

  @Value("${consultant.import.protocol.filename}")
  private String protocolFilename;

  @Value("${multitenancy.enabled}")
  private Boolean multiTenancyEnabled;

  private final @NonNull de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityCreationLocalCompletion
      localCompletion;
  private final @NonNull de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityAccountProvisioning
      identityProvisioning;
  private final @NonNull de.caritas.cob.userservice.api.adapters.keycloak.commands
          .ConfiguredConsultantImport
      configuredImport;
  private final @NonNull de.caritas.cob.userservice.api.facade.rollback.RollbackFacade
      rollbackFacade;
  private final @NonNull org.springframework.transaction.PlatformTransactionManager transactions;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull AgencyService agencyService;
  private final @NonNull UserHelper userHelper;
  private final @NonNull CreateConsultantSaga createConsultantSaga;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;

  private static final String DELIMITER = ",";
  private static final String AGENCY_ROLE_DELIMITER = ";";
  private static final String YES = "ja";
  private static final boolean FORMAL_LANGUAGE_DEFAULT = true;
  private static final boolean TEAM_CONSULTANT_DEFAULT = false;
  private static final String NEWLINE_CHAR = "\r\n";
  private String protocolFile;

  public void startImport() {

    this.protocolFile = protocolFilename + "." + System.currentTimeMillis();

    var captured = configuredImport.capture();
    List<CSVRecord> records;

    records = captured.records();

    for (CSVRecord record : records) {
      var createdAccountId = new java.util.concurrent.atomic.AtomicReference<String>();
      try {
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .executeWithoutResult(
                status -> {
                  String logMessage;
                  Consultant consultant = null;
                  Consultant existingTarget = null;
                  ImportRecord importRecord = getImportRecord(record);

                  // Check if username is valid
                  if (importRecord.getConsultantId() == null
                      && !userHelper.isUsernameValid(importRecord.getUsername())) {
                    throw new ImportException(
                        "Configured consultant import username length is invalid");
                  }

                  String[] agencyRoleSetArray =
                      importRecord.getAgenciesAndRoleSets().split(DELIMITER);

                  HashSet<String> roles = new HashSet<>();
                  HashSet<Long> agencyIds = new HashSet<>();
                  List<AgencyDTO> verifiedAgencies = new ArrayList<>();
                  List<Boolean> formalLanguageList = new ArrayList<>();
                  for (String agencyRoleSet : agencyRoleSetArray) {

                    if (!agencyRoleSet.contains(AGENCY_ROLE_DELIMITER)) {
                      throw new ImportException(
                          String.format(
                              "Consultant %s could not be imported: Invalid agency roleset %s",
                              importRecord.getUsername(), agencyRoleSet));
                    }
                    String[] agencyRoleArray = agencyRoleSet.split(AGENCY_ROLE_DELIMITER);

                    AgencyDTO agency =
                        agencyService.getPublicImportAgency(
                            Long.valueOf(agencyRoleArray[0]), importRecord.getTenantId());

                    if (agency == null) {
                      throw new ImportException(
                          String.format(
                              "Consultant %s could not be imported: Invalid agency id %s",
                              importRecord.getUsername(), agencyRoleArray[0]));
                    }

                    agencyIds.add(Long.valueOf(agencyRoleArray[0]));
                    verifiedAgencies.add(agency);

                    ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO =
                        consultingTypeManager.getConsultingTypeSettings(agency.getConsultingType());

                    if (!extendedConsultingTypeResponseDTO
                        .getRoles()
                        .getConsultant()
                        .getRoleSets()
                        .containsKey(agencyRoleArray[1])) {
                      throw new ImportException(
                          String.format(
                              "Consultant %s could not be imported: invalid role set %s for agency id %s and consulting type %s",
                              importRecord.getUsername(),
                              agencyRoleArray[1],
                              agencyRoleArray[0],
                              extendedConsultingTypeResponseDTO.getSlug()));
                    }

                    for (Map.Entry<String, List<String>> roleSet :
                        extendedConsultingTypeResponseDTO
                            .getRoles()
                            .getConsultant()
                            .getRoleSets()
                            .entrySet()) {
                      if (roleSet.getKey().equals(agencyRoleArray[1])) {
                        roles.addAll(roleSet.getValue());
                        break;
                      }
                    }

                    formalLanguageList.add(extendedConsultingTypeResponseDTO.getLanguageFormal());

                    if (isTrue(agency.getTeamAgency())) {
                      importRecord.setTeamConsultant(true);
                    }
                  }

                  if (!roles.contains("consultant")
                      || !java.util.Set.of("consultant", "group-chat-consultant").containsAll(roles)
                      || verifiedAgencies.stream()
                          .anyMatch(
                              agency ->
                                  agency.getTenantId() != null
                                      && !java.util.Objects.equals(
                                          agency.getTenantId(), importRecord.getTenantId())))
                    throw new ImportException(
                        "Configured import row exceeds its tenant or consultant role scope");

                  if (formalLanguageList.size() == 1) {
                    importRecord.setFormalLanguage(formalLanguageList.get(0));
                  } else {
                    if (formalLanguageList.contains(Boolean.TRUE)
                        && formalLanguageList.contains(Boolean.FALSE)) {
                      importRecord.setFormalLanguage(FORMAL_LANGUAGE_DEFAULT);
                    } else {
                      importRecord.setFormalLanguage(formalLanguageList.get(0));
                    }
                  }

                  if (importRecord.getConsultantId() == null) {
                    Optional<Consultant> consultantOptional =
                        consultantService.findConsultantByUsernameOrEmail(
                            importRecord.getUsername(), importRecord.getEmail());

                    if (consultantOptional.isPresent()) {
                      writeToImportLog(
                          String.format(
                              "Consultant with username %s (%s) exists and won't be " + "imported.",
                              importRecord.getUsername(), importRecord.getUsernameEncoded()));
                      return;
                    }

                  } else {

                    Optional<Consultant> currentConsultant =
                        consultantService.getConsultant(importRecord.getConsultantId());

                    if (currentConsultant.isPresent()) {
                      existingTarget = currentConsultant.get();
                      if (!java.util.Objects.equals(
                          currentConsultant.get().getTenantId(), importRecord.getTenantId()))
                        throw new ImportException(
                            "Configured import row targets an existing consultant in another tenant");
                      UsernameTranscoder usernameTranscoder = new UsernameTranscoder();
                      if (!importRecord
                          .getUsername()
                          .equals(
                              usernameTranscoder.decodeUsername(
                                  currentConsultant.get().getUsername()))) {
                        writeToImportLog(
                            String.format(
                                "Username of consultant with id %s has changed (From %s to %s). Name changing currently not implemented. Skipped entry.",
                                importRecord.getConsultantId(),
                                usernameTranscoder.decodeUsername(
                                    currentConsultant.get().getUsername()),
                                importRecord.getUsername()));
                        return;
                      }
                    } else {
                      writeToImportLog(
                          String.format(
                              "Consultant with id %s not found. Skipped entry.",
                              importRecord.getConsultantId()));
                      return;
                    }
                  }

                  logMessage = "=== BEGIN === " + importRecord.getUsername() + " ===";
                  writeToImportLog(logMessage);

                  if (importRecord.getConsultantId() == null) {
                    consultant =
                        this.createConsultantSaga.createImportedConsultant(
                            importRecord,
                            captured.authorize(record, importRecord, roles, verifiedAgencies));

                    createdAccountId.set(consultant.getId());
                    importRecord.setConsultantId(consultant.getId());
                    logMessage = "Keycloak-ID: " + consultant.getId();
                    writeToImportLog(logMessage);

                    logMessage = "Roles: " + String.join(",", roles);
                    writeToImportLog(logMessage);

                    logMessage = "Matrix-ID: " + consultant.getMatrixUserId();
                    writeToImportLog(logMessage);
                  }

                  // create relations to agencies
                  logMessage =
                      "Agencies: "
                          + agencyIds.stream()
                              .map(String::valueOf)
                              .collect(Collectors.joining(","));
                  writeToImportLog(logMessage);
                  if (createdAccountId.get() != null) {
                    this.consultantAgencyRelationCreatorService.createOwnedCreationRelations(
                        createdAccountId.get(), verifiedAgencies, roles, this::writeToImportLog);
                  } else {
                    var read =
                        captured.existingRowCapability(
                            record,
                            importRecord,
                            existingTarget,
                            verifiedAgencies,
                            "account.read",
                            roles);
                    var additions =
                        captured.existingRowCapability(
                            record,
                            importRecord,
                            existingTarget,
                            verifiedAgencies,
                            "account.roles",
                            roles);
                    this.consultantAgencyRelationCreatorService.createExistingImportedRelations(
                        importRecord.getConsultantId(),
                        verifiedAgencies,
                        roles,
                        this::writeToImportLog,
                        read,
                        additions);
                  }

                  if (createdAccountId.get() != null)
                    localCompletion.importedConsultant(consultant);
                  logMessage = "=== END === " + importRecord.getUsername() + " ===" + NEWLINE_CHAR;
                  writeToImportLog(logMessage);
                });
      } catch (ImportException wontImportException) {
        compensateCreation(createdAccountId.get(), wontImportException);
        writeToImportLog(wontImportException.getMessage());
        throw new de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException(
            "Configured consultant import contains an invalid row", wontImportException);
      } catch (Exception fileNotFoundException) {
        compensateCreation(createdAccountId.get(), fileNotFoundException);
        log.warn(
            "Configured consultant import failed ({})",
            fileNotFoundException.getClass().getSimpleName());
        if (fileNotFoundException instanceof org.springframework.web.client.RestClientException)
          throw new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_GATEWAY,
              "Consultant import dependency failed",
              fileNotFoundException);
        if (fileNotFoundException instanceof RuntimeException failure) throw failure;
        throw new de.caritas.cob.userservice.api.exception.httpresponses
            .InternalServerErrorException(
            "Configured consultant import failed", fileNotFoundException);
      }
    }
  }

  private void compensateCreation(String accountId, Exception failure) {
    if (accountId == null) return;
    try {
      var created = consultantService.getConsultant(accountId);
      if (created.isPresent()) rollbackFacade.rollbackConsultantAccount(created.get());
      else identityProvisioning.compensateForLocalRollback(accountId);
    } catch (RuntimeException compensationFailure) {
      failure.addSuppressed(compensationFailure);
    }
  }

  private void writeToImportLog(String message) {
    if (message.length() < 1) {
      return;
    }

    try {
      Files.write(
          Paths.get(protocolFile),
          (message + NEWLINE_CHAR).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private ImportRecord getImportRecord(CSVRecord record) {
    ImportRecord importRecord = new ImportRecord();
    importRecord.setConsultantId(
        (record.get(0).trim().equals(StringUtils.EMPTY)) ? null : record.get(0));
    importRecord.setIdOld(
        (record.get(1).trim().equals(StringUtils.EMPTY)) ? null : Long.valueOf(record.get(1)));
    importRecord.setUsername(StringUtils.trim(record.get(2)));
    importRecord.setUsernameEncoded(
        new UsernameTranscoder().encodeUsername(StringUtils.trim(record.get(2))));
    importRecord.setFirstName(StringUtils.trim(record.get(3)));
    importRecord.setLastName(StringUtils.trim(record.get(4)));
    String email = StringUtils.deleteWhitespace(record.get(5));
    // If there is more than one email addresses...than catch the first one
    if (email.contains(DELIMITER)) {
      email = email.substring(0, email.indexOf(DELIMITER)).trim();
    }
    if (!userHelper.isValidEmail(email)) {
      throw new ImportException(
          String.format(
              "Consultant %s could not be imported: Invalid email address",
              importRecord.getUsername()));
    }
    importRecord.setEmail(email.toLowerCase(java.util.Locale.ROOT));
    importRecord.setAbsent(record.get(6).equals(YES));
    String absenceMessage =
        (record.get(7).trim().equals(StringUtils.EMPTY)) ? null : record.get(7).trim();
    if (absenceMessage != null) {
      absenceMessage = absenceMessage.replace("<br>", "\r\n");
    }
    importRecord.setAbsenceMessage(absenceMessage);
    importRecord.setAgenciesAndRoleSets(record.get(8));

    if (isTrue(multiTenancyEnabled)) {
      importRecord.setTenantId(
          (record.get(9).trim().equals(StringUtils.EMPTY)) ? null : Long.valueOf(record.get(9)));
    }
    return importRecord;
  }

  @Getter
  @Setter
  public static class ImportRecord {

    String consultantId;
    Long tenantId;
    Long idOld;
    String username;
    String usernameEncoded;
    String firstName;
    String lastName;
    String email;
    boolean isAbsent;
    String absenceMessage;
    String agenciesAndRoleSets;
    boolean formalLanguage = FORMAL_LANGUAGE_DEFAULT;
    boolean isTeamConsultant = TEAM_CONSULTANT_DEFAULT;
  }
}
