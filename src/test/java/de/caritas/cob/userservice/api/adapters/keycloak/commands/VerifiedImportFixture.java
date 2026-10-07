package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import java.nio.file.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class VerifiedImportFixture {
  private VerifiedImportFixture() {}

  public static IdentityCreationOrigin authorize(ImportRecord row) throws Exception {
    var previous = SecurityContextHolder.getContext().getAuthentication();
    var file = Files.createTempFile("configured-import-test-", ".csv");
    try {
      var jwt =
          Jwt.withTokenValue("verified-test-token")
              .header("alg", "RS256")
              .subject("import-subject")
              .audience(List.of("userservice"))
              .claim("azp", "backend-consultant-import")
              .claim("realm_access", Map.of("roles", List.of("consultant-import")))
              .build();
      SecurityContextHolder.getContext()
          .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
      row.setConsultantId(null);
      row.setAgenciesAndRoleSets("8;standard");
      String tenant = row.getTenantId() == null ? "" : row.getTenantId().toString();
      Files.writeString(
          file,
          ",42,"
              + row.getUsername()
              + ","
              + row.getFirstName()
              + ","
              + row.getLastName()
              + ","
              + row.getEmail()
              + ",nein,,8;standard,"
              + tenant
              + "\n");
      var captured =
          new ConfiguredConsultantImport(
                  file.toString(),
                  "backend-consultant-import",
                  "import-subject",
                  "userservice",
                  row.getTenantId() != null)
              .capture();
      return captured.authorize(
          captured.records().getFirst(),
          row,
          List.of("consultant"),
          List.of(new AgencyDTO().id(8L).tenantId(row.getTenantId())));
    } finally {
      Files.deleteIfExists(file);
      SecurityContextHolder.getContext().setAuthentication(previous);
    }
  }
}
