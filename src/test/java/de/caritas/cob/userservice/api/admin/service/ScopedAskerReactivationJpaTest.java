package de.caritas.cob.userservice.api.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The actual public facade and committed JPA claim must never fall back to generic native rights. */
@DataJpaTest
@TestPropertySource(properties = {
  "spring.profiles.active=testing,scenario-c-reactivation-jpa",
  "TASK_IDENTITY_MODEL=FIVE_ACTORS"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  IdentityReactivationSagaStore.class,
  AskerUserAdminFacade.class,
  ScopedAskerReactivationJpaTest.ScenarioCConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScopedAskerReactivationJpaTest {
  private static final String ACCOUNT = "85000000-0000-4000-8000-000000000041";
  private static final String USERNAME = "soft-deleted-asker";
  private static final String EMAIL = "asker@fixture.invalid";
  @Autowired private UserRepository users;
  @Autowired private DataSource source;
  @Autowired private AskerUserAdminFacade facade;
  @Autowired private IdentityReactivator genericReactivator;
  @Autowired private IdentityReactivationRepairService genericRepair;
  @Autowired private UsernameTranscoder usernames;
  private JdbcTemplate jdbc;

  @BeforeEach
  void exactExistingSoftDeletedAsker() {
    TenantContext.setCurrentTenant(7L);
    jdbc = new JdbcTemplate(source);
    users.findById(ACCOUNT).ifPresent(users::delete);
    var user = new User(ACCOUNT, null, usernames.encodeUsername(USERNAME), EMAIL, false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(3));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    users.save(user);
    reset(genericReactivator, genericRepair);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void fiveActorsMissingScopedOwnerRefusesBeforeAllocatingALegacyClaimOrCallingGenericRepair() {
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(genericReactivator)
        .reactivateUser(anyString(), anyString(), anyString(), anyLong(), anyString());
    var request = new AskerReactivationRequestDTO();
    request.setUsername(USERNAME);
    request.setEmail(EMAIL);
    request.setTenantId(7L);
    request.setPassword("synthetic-chosen-password");

    assertThrows(ScopedIdentityCommandRequiredException.class, () -> facade.reactivateAsker(request));
    assertEquals(
        "READ_ONLY_SAFEGUARD",
        jdbc.queryForObject(
            "select deletion_lifecycle_state from user where user_id=?", String.class, ACCOUNT),
        "An unavailable scoped owner must not allocate a generic reactivation claim");
    assertNull(jdbc.queryForObject(
        "select reactivation_operation_id from user where user_id=?", String.class, ACCOUNT));
    assertNotNull(jdbc.queryForObject(
        "select delete_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT));
    verifyNoInteractions(genericReactivator, genericRepair);
  }

  @TestConfiguration
  @Profile("scenario-c-reactivation-jpa")
  static class ScenarioCConfiguration {
    @Bean
    IdentityClientConfig scopedIdentityMode() {
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(true);
      return mode;
    }

    @Bean
    IdentityReactivator genericReactivation() {
      return mock(IdentityReactivator.class);
    }

    @Bean
    IdentityReactivationRepairService genericRepair() {
      return mock(IdentityReactivationRepairService.class);
    }

    @Bean
    IdentityDeactivator unusedDeactivator() {
      return mock(IdentityDeactivator.class);
    }

    @Bean
    UserService existingUserService() {
      return mock(UserService.class);
    }

    @Bean
    AdminScope existingAdminScope() {
      return mock(AdminScope.class);
    }

    @Bean
    UsernameTranscoder usernames() {
      return new UsernameTranscoder();
    }

    @Bean
    DeletionLifecycleService existingDeletionLifecycle() {
      return new DeletionLifecycleService();
    }
  }
}
