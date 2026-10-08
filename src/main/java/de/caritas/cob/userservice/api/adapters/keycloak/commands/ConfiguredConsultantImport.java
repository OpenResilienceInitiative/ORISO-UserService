package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.auth.ConsultantImportTaskAuthorization;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Captures the configured file after authenticating its sole machine importer. */
@Component
public final class ConfiguredConsultantImport {
  private final String filename;
  private final ConsultantImportTaskAuthorization guard;
  private final boolean multitenancy;

  public ConfiguredConsultantImport(
      @Value("${consultant.import.filename}") String filename,
      @Value("${identity.consultant-import.client-id:}") String client,
      @Value("${identity.consultant-import.service-subject:}") String subject,
      @Value("${task.identity.audience:userservice}") String audience,
      @Value("${multitenancy.enabled}") boolean multitenancy) {
    this.multitenancy = multitenancy;
    this.filename = filename;
    this.guard = new ConsultantImportTaskAuthorization(client, subject, audience);
  }

  public VerifiedFile capture() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!guard.permits(auth))
      throw new AccessDeniedException(
          "Configured consultant import requires its verified importer");
    try {
      var path = Path.of(filename).toRealPath();
      byte[] bytes = Files.readAllBytes(path);
      String fingerprint =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      try (var parser =
          CSVFormat.DEFAULT.parse(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
        return new VerifiedFile(
            List.copyOf(parser.getRecords()), fingerprint, auth.getName(), multitenancy);
      }
    } catch (Exception error) {
      throw new IllegalStateException(
          "Configured consultant import file could not be captured", error);
    }
  }

  public static final class VerifiedFile {
    private final List<CSVRecord> records;
    private final String fingerprint;
    private final String actor;
    private final boolean multitenancy;

    private VerifiedFile(
        List<CSVRecord> records, String fingerprint, String actor, boolean multitenancy) {
      this.records = records;
      this.fingerprint = fingerprint;
      this.actor = actor;
      this.multitenancy = multitenancy;
    }

    public List<CSVRecord> records() {
      return records;
    }

    public IdentityCreationOrigin authorize(
        CSVRecord row,
        ImportRecord parsed,
        Collection<String> roles,
        Collection<AgencyDTO> agencies) {
      validateRow(row, parsed, agencies);
      if (parsed.getConsultantId() != null || !row.get(0).isBlank())
        throw new AccessDeniedException("New import row cannot claim an existing account");
      String provenance =
          "csv:"
              + fingerprint
              + ":"
              + row.getRecordNumber()
              + ":"
              + Integer.toUnsignedString(actor.hashCode());
      return IdentityCreationOrigin.configuredImport(parsed, roles, provenance);
    }

    public IdentityCommandAuthorization existingRowCapability(
        CSVRecord row,
        ImportRecord parsed,
        de.caritas.cob.userservice.api.model.Consultant target,
        Collection<AgencyDTO> agencies,
        String operation,
        Collection<String> additions) {
      validateRow(row, parsed, agencies);
      if (target == null
          || parsed.getConsultantId() == null
          || !Objects.equals(row.get(0).trim(), parsed.getConsultantId())
          || !Objects.equals(target.getId(), parsed.getConsultantId())
          || !Objects.equals(target.getTenantId(), parsed.getTenantId())
          || !Objects.equals(
              new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                  .decodeUsername(target.getUsername()),
              parsed.getUsername()))
        throw new AccessDeniedException(
            "Existing import row cannot name another persisted consultant");
      return IdentityCommandAuthorization.importedExisting(target, operation, additions);
    }

    private void validateRow(CSVRecord row, ImportRecord parsed, Collection<AgencyDTO> agencies) {
      if (records.stream().noneMatch(candidate -> candidate == row)
          || parsed == null
          || agencies == null
          || agencies.isEmpty()
          || parsed.getUsername() == null
          || parsed.getUsername().isBlank()
          || !Objects.equals(row.get(2).trim(), parsed.getUsername())
          || !Objects.equals(row.get(3).trim(), parsed.getFirstName())
          || !Objects.equals(row.get(4).trim(), parsed.getLastName())
          || !Objects.equals(
              row.get(5).replaceAll("\\s", "").split(",", 2)[0].toLowerCase(Locale.ROOT),
              parsed.getEmail())
          || !Objects.equals(row.get(8), parsed.getAgenciesAndRoleSets())
          || (multitenancy
              && (row.get(9).isBlank()
                  || !Objects.equals(Long.valueOf(row.get(9)), parsed.getTenantId())))
          || (!multitenancy && parsed.getTenantId() != null)
          || agencies.stream()
              .anyMatch(
                  agency ->
                      agency == null
                          || agency.getId() == null
                          || (agency.getTenantId() != null
                              && !Objects.equals(agency.getTenantId(), parsed.getTenantId())))
          || !agencies.stream()
              .map(AgencyDTO::getId)
              .collect(java.util.stream.Collectors.toSet())
              .equals(
                  Arrays.stream(row.get(8).split(","))
                      .map(value -> Long.valueOf(value.split(";", 2)[0]))
                      .collect(java.util.stream.Collectors.toSet())))
        throw new AccessDeniedException(
            "Import row exceeds its captured file or verified agencies");
    }

    @Override
    public String toString() {
      return "VerifiedConfiguredImport[redacted]";
    }
  }
}
