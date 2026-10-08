package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ConfiguredConsultantImportTest {
  @TempDir Path directory;

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void capturedRowsBindTenantAccountAndInitialRolesAndCannotMintMaintenance() throws Exception {
    var file = directory.resolve("configured.csv");
    Files.writeString(file, ",42,alice,Alice,Example,alice@example.org,nein,,8;standard,7\n");
    authenticate("import-subject", "backend-consultant-import", List.of("consultant-import"));
    var captured =
        new ConfiguredConsultantImport(
                file.toString(), "backend-consultant-import", "import-subject", "userservice", true)
            .capture();
    // Altering the disk after capture cannot change the rows this authorization covers.
    Files.writeString(file, ",43,mallory,Mallory,Example,mallory@example.org,nein,,9;standard,9\n");
    var record = parsed();
    var row = captured.records().getFirst();
    var origin =
        captured.authorize(
            row, record, List.of("consultant"), List.of(new AgencyDTO().id(8L).tenantId(7L)));
    assertThat(origin.originKindForPolicy()).isEqualTo("IMPORT");
    assertThat(origin.tenantId()).isEqualTo(7L);
    assertThat(origin.provenance()).startsWith("csv:");
    assertThatThrownBy(() -> origin.command("account.roles", UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    record.setTenantId(9L);
    assertThatThrownBy(
            () ->
                captured.authorize(
                    row,
                    record,
                    List.of("consultant"),
                    List.of(new AgencyDTO().id(8L).tenantId(9L))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    record.setTenantId(7L);
    record.setUsername("mallory");
    assertThatThrownBy(
            () ->
                captured.authorize(
                    row,
                    record,
                    List.of("consultant"),
                    List.of(new AgencyDTO().id(8L).tenantId(7L))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    record.setUsername("alice");
    assertThatThrownBy(
            () ->
                captured.authorize(
                    row,
                    record,
                    List.of("consultant", "user-admin"),
                    List.of(new AgencyDTO().id(8L).tenantId(7L))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void existingRowCapabilitiesBindThePersistedUuidTenantAndConsultantAdditions() throws Exception {
    String id = UUID.randomUUID().toString();
    var file = directory.resolve("existing.csv");
    Files.writeString(file, id + ",42,alice,Alice,Example,alice@example.org,nein,,8;standard,7\n");
    authenticate("import-subject", "backend-consultant-import", List.of("consultant-import"));
    var captured =
        new ConfiguredConsultantImport(
                file.toString(), "backend-consultant-import", "import-subject", "userservice", true)
            .capture();
    var parsed = parsed();
    parsed.setConsultantId(id);
    var actual = new de.caritas.cob.userservice.api.model.Consultant();
    actual.setId(id);
    actual.setTenantId(7L);
    actual.setUsername(
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder().encodeUsername("alice"));
    var agencies = List.of(new AgencyDTO().id(8L).tenantId(7L));
    var row = captured.records().getFirst();
    var origin =
        captured.existingRowCapability(
            row,
            parsed,
            actual,
            agencies,
            "account.roles",
            List.of("consultant", "group-chat-consultant"));
    assertThat(origin.target()).isEqualTo(id);
    assertThat(origin.tenantId()).isEqualTo("7");
    assertThat(origin.originKind()).isEqualTo("IMPORT");
    assertThat(origin.roles()).containsExactly("consultant", "group-chat-consultant");
    for (String operation :
        List.of(
            "account.password",
            "account.delete",
            "account.profile",
            "account.deactivate",
            "account.create"))
      assertThatThrownBy(
              () ->
                  captured.existingRowCapability(
                      row, parsed, actual, agencies, operation, List.of("consultant")))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                captured.existingRowCapability(
                    row,
                    parsed,
                    actual,
                    agencies,
                    "account.roles",
                    List.of("consultant", "user-admin")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    actual.setId(UUID.randomUUID().toString());
    assertThatThrownBy(
            () ->
                captured.existingRowCapability(
                    row, parsed, actual, agencies, "account.read", List.of("consultant")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    actual.setId(id);
    actual.setTenantId(9L);
    assertThatThrownBy(
            () ->
                captured.existingRowCapability(
                    row, parsed, actual, agencies, "account.read", List.of("consultant")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    actual.setTenantId(7L);
    parsed.setUsername("mallory");
    assertThatThrownBy(
            () ->
                captured.existingRowCapability(
                    row, parsed, actual, agencies, "account.read", List.of("consultant")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    parsed.setUsername("alice");
    var foreignFile = directory.resolve("foreign.csv");
    Files.writeString(foreignFile, Files.readString(file));
    var foreign =
        new ConfiguredConsultantImport(
                foreignFile.toString(),
                "backend-consultant-import",
                "import-subject",
                "userservice",
                true)
            .capture();
    assertThatThrownBy(
            () ->
                captured.existingRowCapability(
                    foreign.records().getFirst(),
                    parsed,
                    actual,
                    agencies,
                    "account.read",
                    List.of("consultant")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void missingImporterFailsBeforeConfiguredFileRead() {
    var importer =
        new ConfiguredConsultantImport(
            directory.resolve("absent").toString(),
            "backend-consultant-import",
            "import-subject",
            "userservice",
            true);
    assertThatThrownBy(importer::capture)
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    authenticate("human-subject", "app", List.of("consultant-import"));
    assertThatThrownBy(importer::capture)
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private static ImportRecord parsed() {
    var record = new ImportRecord();
    record.setUsername("alice");
    record.setFirstName("Alice");
    record.setLastName("Example");
    record.setEmail("alice@example.org");
    record.setTenantId(7L);
    record.setAgenciesAndRoleSets("8;standard");
    return record;
  }

  private static void authenticate(String subject, String client, List<String> roles) {
    var jwt =
        Jwt.withTokenValue("verified-test-token")
            .header("alg", "RS256")
            .subject(subject)
            .audience(List.of("userservice"))
            .claim("azp", client)
            .claim("realm_access", Map.of("roles", roles))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }
}
