package de.caritas.cob.userservice.api.admin.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({AccountSearch.class, AccountSearchStore.class, AccountSearchNames.class})
class UserAdminSearchMariaDbIT {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    r.add("spring.datasource.username", () -> "root");
    r.add("spring.datasource.password", () -> "root");
    r.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    r.add("spring.liquibase.enabled", () -> "true");
    r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    r.add("spring.liquibase.contexts", () -> "dev,seed");
    r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    r.add("spring.jpa.defer-datasource-initialization", () -> "false");
    r.add("spring.sql.init.mode", () -> "never");
    r.add("multitenancy.enabled", () -> "false");
  }

  @Autowired AccountSearch search;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccountInviteRepository invites;
  @MockitoBean AgencyService agencies;
  @MockitoBean TenantService tenants;
  static final long TENANT = 771001L;
  static final long OTHER = 771002L;
  static final long AGENCY = 771101L;
  static final long HIDDEN = 771102L;
  String prefix;

  @BeforeEach
  void setup() {
    prefix = UUID.randomUUID().toString().substring(0, 8);
    when(agencies.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            call -> {
              List<Long> ids = call.getArgument(0);
              return ids.stream()
                  .map(id -> new AgencyDTO().id(id).name(id.equals(AGENCY) ? "Cáritas" : "Hidden"))
                  .toList();
            });
    when(tenants.getRestrictedTenantData(anySet()))
        .thenAnswer(
            call -> {
              Set<Long> ids = call.getArgument(0);
              return ids.stream()
                  .map(
                      id ->
                          new RestrictedTenantDTO()
                              .id(id)
                              .name(id.equals(TENANT) ? "Träger Alpha" : "Other"))
                  .toList();
            });
  }

  @Test
  void wordsAcrossFieldsAccentWildcardsAndUnknownToken() {
    String target = consultant("Max", "Müller", TENANT, null);
    consultant("Max", "Mu\u0308ller", TENANT, null);
    consultant("Max", "Other", OTHER, null);
    assertThat(search.consultants("MAX MULLER", TENANT, null, page(0, 20)).getTotalElements())
        .isEqualTo(2);
    assertThat(search.consultants("Max absent", TENANT, null, page(0, 20))).isEmpty();
    String literal = consultant("100%", "Under_score!", TENANT, null);
    consultant("100x", "UnderXscore!", TENANT, null);
    assertThat(
            search
                .consultants("100% Under_score!", TENANT, null, page(0, 20))
                .map(Consultant.ConsultantBase::getId))
        .containsExactly(literal);
    jdbc.update(
        "UPDATE consultant SET internal_display_name='Alias' WHERE consultant_id=?", target);
    assertThat(
            search
                .consultants("Alias", TENANT, null, page(0, 20))
                .map(Consultant.ConsultantBase::getId))
        .containsExactly(target);
  }

  @Test
  void ownerNameMatchIsBeforeDatabasePageAndActiveAgencyScopeNeverBroadens() {
    for (int i = 0; i < 23; i++) {
      String id = consultant("Max", "Mustermann", TENANT, null);
      relation(id, AGENCY, TENANT, false);
      relation(id, AGENCY, TENANT, false);
      relation(id, HIDDEN, TENANT, false);
    }
    String hidden = consultant("Max", "Mustermann", TENANT, null);
    relation(hidden, AGENCY, TENANT, true);
    String other = consultant("Max", "Mustermann", OTHER, null);
    relation(other, AGENCY, OTHER, false);
    var first = search.consultants("max caritas", TENANT, Set.of(AGENCY), page(0, 20));
    var second = search.consultants("max caritas", TENANT, Set.of(AGENCY), page(1, 20));
    assertThat(
            search
                .consultants("Max Mustermann", TENANT, Set.of(AGENCY), page(0, 20))
                .getTotalElements())
        .isEqualTo(23);
    assertThat(first.getTotalElements()).isEqualTo(23);
    assertThat(second.getTotalElements()).isEqualTo(23);
    assertThat(second).hasSize(3);
    assertThat(first.map(Consultant.ConsultantBase::getId).getContent())
        .doesNotContainAnyElementsOf(second.map(Consultant.ConsultantBase::getId).getContent());
    assertThat(search.consultants("max hidden", TENANT, Set.of(AGENCY), page(0, 20))).isEmpty();
    verify(agencies, atLeastOnce()).getAgenciesWithoutCaching(List.of(AGENCY));
    verify(agencies, never()).getAgenciesWithoutCaching(argThat(ids -> ids.contains(HIDDEN)));
    assertThat(
            search.consultants("max alpha", TENANT, Set.of(AGENCY), page(0, 20)).getTotalElements())
        .isZero();
    verifyNoInteractions(tenants);
    clearInvocations(agencies, tenants);
    assertThat(search.consultants("max", TENANT, Set.of(), page(0, 20))).isEmpty();
    verifyNoInteractions(agencies, tenants);
  }

  @Test
  void agencyNameOnlyMatchBeyondInitialEligiblePageIsFound() {
    for (int i = 0; i < 23; i++) {
      String id = consultant("Max", "Before " + i, TENANT, null);
      relation(id, HIDDEN, TENANT, false);
    }
    String target = consultant("Max", "Zeta", TENANT, null);
    relation(target, AGENCY, TENANT, false);
    var result =
        search.consultants("max caritas", TENANT, null, PageRequest.of(0, 20, Sort.by("lastName")));
    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.map(Consultant.ConsultantBase::getId)).containsExactly(target);
  }

  @Test
  void lastUpdatedSortKeepsDatedAndTiedPagesStable() {
    String first = UUID.randomUUID().toString();
    String second = UUID.randomUUID().toString();
    String third = UUID.randomUUID().toString();
    var created = LocalDateTime.of(2026, 10, 1, 10, 0);
    // The real schema stamps every UPDATE. Seed deliberate dates at INSERT instead.
    Map.of(first, created, second, created.plusDays(1), third, created)
        .forEach(
            (id, updated) ->
                jdbc.update(
                    "INSERT INTO consultant(consultant_id,username,first_name,last_name,email,tenant_id,create_date,update_date) VALUES (?,?,?,?,?,?,?,?)",
                    id,
                    id,
                    "Max",
                    "Mustermann",
                    id + "@example.invalid",
                    TENANT,
                    created,
                    updated));
    var expected = java.util.stream.Stream.of(first, third).sorted().toList();
    var order = Sort.by("lastUpdated");
    for (int index = 0; index < 3; index++) {
      var result = search.consultants("*", TENANT, null, PageRequest.of(index, 1, order));
      assertThat(result.getTotalElements()).isEqualTo(3);
      assertThat(result.map(Consultant.ConsultantBase::getId))
          .containsExactly(index < 2 ? expected.get(index) : second);
    }
  }

  @Test
  void blankAndSentinelSkipNameResolutionAndPreserveEligibility() {
    consultant("Max", "Mustermann", TENANT, null);
    consultant("Deleted", "User", TENANT, LocalDateTime.now());
    for (String raw : Arrays.asList(null, "", " \t", "*"))
      assertThat(search.consultants(raw, TENANT, null, page(0, 20)).getTotalElements())
          .isEqualTo(1);
    verifyNoInteractions(agencies, tenants);
  }

  @Test
  void adminWordsNamesTypeAndTenantIsolation() {
    String target = admin("Max", "Mustermann", TENANT, Admin.AdminType.AGENCY);
    admin("Max", "Mustermann", OTHER, Admin.AdminType.AGENCY);
    admin("Max", "Mustermann", TENANT, Admin.AdminType.TENANT);
    jdbc.update(
        "INSERT INTO admin_agency(id,admin_id,agency_id) VALUES (NEXT VALUE FOR sequence_admin_agency,?,?)",
        target,
        AGENCY);
    for (String query : List.of("Max Mustermann", "max alpha", String.valueOf(TENANT)))
      assertThat(
              search
                  .admins(query, Admin.AdminType.AGENCY, TENANT, Set.of(AGENCY), page(0, 10))
                  .map(Admin.AdminBase::getId))
          .containsExactly(target);
    assertThat(search.admins("Max absent", Admin.AdminType.AGENCY, TENANT, null, page(0, 10)))
        .isEmpty();
    assertThat(
            search.admins(
                "max caritas", Admin.AdminType.AGENCY, TENANT, Set.of(AGENCY), page(0, 10)))
        .isEmpty();
    verifyNoInteractions(agencies);
    String accented = admin("André", "Müller", TENANT, Admin.AdminType.AGENCY);
    assertThat(
            search
                .admins("ANDRE MULLER", Admin.AdminType.AGENCY, TENANT, null, page(0, 10))
                .map(Admin.AdminBase::getId))
        .containsExactly(accented);
    assertThat(
            search
                .admins(
                    target.toUpperCase(Locale.ROOT),
                    Admin.AdminType.AGENCY,
                    TENANT,
                    null,
                    page(0, 10))
                .map(Admin.AdminBase::getId))
        .containsExactly(target);
  }

  @Test
  void invitationsWordsAccentNumericScopeAndCountsBeforePaging() {
    for (int i = 0; i < 23; i++) invites.save(invite(TENANT, AGENCY, "Max", "Müller"));
    invites.save(invite(OTHER, AGENCY, "Max", "Müller"));
    invites.save(invite(TENANT, HIDDEN, "Max", "Müller"));
    var first =
        invites.findAllByFiltersWithinAgencies(
            TENANT, null, null, "max muller", null, Set.of(AGENCY), page(0, 20));
    var second =
        invites.findAllByFiltersWithinAgencies(
            TENANT, null, null, "max muller", null, Set.of(AGENCY), page(1, 20));
    assertThat(first.getTotalElements()).isEqualTo(23);
    assertThat(second.getTotalElements()).isEqualTo(23);
    assertThat(second).hasSize(3);
    assertThat(
            invites
                .findAllByFilters(TENANT, null, null, "max " + TENANT, TENANT, page(0, 100))
                .getTotalElements())
        .isEqualTo(24);
    assertThat(invites.findAllByFilters(TENANT, null, null, "max absent", null, page(0, 100)))
        .isEmpty();
  }

  private PageRequest page(int index, int size) {
    return PageRequest.of(index, size, Sort.by("id"));
  }

  private String consultant(String first, String last, Long tenant, LocalDateTime deleted) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO consultant(consultant_id,username,first_name,last_name,email,tenant_id,delete_date,create_date) VALUES (?,?,?,?,?,?,?,?)",
        id,
        prefix + id,
        first,
        last,
        id + "@example.invalid",
        tenant,
        deleted,
        LocalDateTime.now());
    return id;
  }

  private String admin(String first, String last, Long tenant, Admin.AdminType type) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO admin(admin_id,username,first_name,last_name,email,tenant_id,type,create_date) VALUES (?,?,?,?,?,?,?,?)",
        id,
        prefix + id,
        first,
        last,
        id + "@example.invalid",
        tenant,
        type.name(),
        LocalDateTime.now());
    return id;
  }

  private void relation(String consultant, Long agency, Long tenant, boolean deleted) {
    jdbc.update(
        "INSERT INTO consultant_agency(id,consultant_id,agency_id,tenant_id,delete_date) VALUES (NEXT VALUE FOR sequence_consultant_agency,?,?,?,?)",
        consultant,
        agency,
        tenant,
        deleted ? LocalDateTime.now() : null);
  }

  private AccountInvite invite(Long tenant, Long agency, String first, String last) {
    return AccountInvite.builder()
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(tenant)
        .agencyId(agency)
        .firstName(first)
        .lastName(last)
        .recipientEmail(UUID.randomUUID() + "@example.invalid")
        .tokenHash(UUID.randomUUID().toString())
        .createDate(LocalDateTime.now())
        .expiresAt(LocalDateTime.now().plusDays(1))
        .build();
  }
}
