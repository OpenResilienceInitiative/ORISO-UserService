package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.actions.ActionCommandMockProvider;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.*;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.scheduler.DeleteUserAccountScheduler;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/**
 * Actual scheduled-service boundary; a missing C capability cannot authorize downstream cleanup.
 */
@org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
@org.springframework.test.context.ActiveProfiles("testing")
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace = org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.context.annotation.Import({
  de.caritas.cob.userservice.api.config.JpaAuditingConfiguration.class,
  de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter.class,
  ScheduledTaskClaimService.class,
  ScheduledDeletionContinuationJpaTest.TimeConfiguration.class
})
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class DeleteUserAccountScenarioCBoundaryTest {
  @org.springframework.beans.factory.annotation.Autowired private javax.sql.DataSource actualSource;
  @org.springframework.beans.factory.annotation.Autowired private UserRepository actualUsers;
  @org.springframework.beans.factory.annotation.Autowired private jakarta.persistence.EntityManager actualEntities;
  @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager actualTransactions;
  @org.springframework.beans.factory.annotation.Autowired private ScheduledTaskClaimService actualClaims;
  @org.springframework.beans.factory.annotation.Autowired private Clock actualClock;
  private final IdentityClientConfig config = mock(IdentityClientConfig.class);
  private final KeycloakClient nativeClient = mock(KeycloakClient.class);
  private final UserResource nativeUser = mock(UserResource.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final ActionsRegistry registry = mock(ActionsRegistry.class);
  private final WorkflowErrorMailService mail = mock(WorkflowErrorMailService.class);
  private final DeletionLifecycleService lifecycle = new DeletionLifecycleService();
  private final UserHardDeleteClaimService claim = new UserHardDeleteClaimService(users, lifecycle);
  private final ActionCommandMockProvider actions = new ActionCommandMockProvider();
  private final DeleteUserAccountService service =
      new DeleteUserAccountService(users, consultants, registry, mail, lifecycle, claim);

  private void eligiblePersistedAsker(boolean fiveActors) {
    when(config.isFiveActorMode()).thenReturn(fiveActors);
    var nativeUsers = mock(UsersResource.class);
    when(nativeClient.getUsersResource()).thenReturn(nativeUsers);
    when(nativeUsers.get("11111111-1111-4111-8111-111111111111")).thenReturn(nativeUser);
    var identity =
        new KeycloakService(
            new AuthenticatedUser(),
            mock(UserAccountInputValidator.class),
            config,
            nativeClient,
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class));
    actions.setCustomClassForAction(
        DeleteKeycloakAskerAction.class, new DeleteKeycloakAskerAction(identity));
    when(registry.buildContainerForType(AskerDeletionWorkflowDTO.class))
        .thenAnswer(ignored -> actions.getActionContainer(AskerDeletionWorkflowDTO.class));
    var user = new User();
    user.setUserId("11111111-1111-4111-8111-111111111111");
    user.setTenantId(7L);
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(4));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    when(users.findAllByDeleteDateNotNull()).thenReturn(List.of(user));
    when(users.findByUserIdForUpdate(user.getUserId())).thenReturn(Optional.of(user));
    when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void fiveActorsMissingScopedDeletionStopsBeforeOwnedDownstreamCleanup() {
    eligiblePersistedAsker(true);
    service.deleteUserAccounts();
    verifyNoInteractions(nativeClient);
    verify(mail).buildAndSendErrorMail(argThat(errors -> errors.size() == 1));
    verifyNoInteractions(
        actions.getActionMock(DeleteMatrixAskerAction.class),
        actions.getActionMock(DeleteDatabaseAskerAction.class));
  }

  @Test
  void legacyModeRetainsItsExistingDeletionWorkflow() {
    eligiblePersistedAsker(false);
    service.deleteUserAccounts();
    verify(nativeUser).remove();
    verify(actions.getActionMock(DeleteDatabaseAskerAction.class)).execute(any());
    verifyNoInteractions(mail);
  }

  @Test
  void fiveActorsMissingScopedConsultantDeletionStopsBeforeOwnedDownstreamCleanup() {
    consultantDeletion(true);
  }

  @Test
  void legacyModeRetainsItsExistingConsultantDeletionWorkflow() {
    consultantDeletion(false);
  }

  @Test
  void fiveActorsMissingScopedSchedulerDelegateRefusesActualStartup() {
    schedulerContext("FIVE_ACTORS").run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void legacyMissingScopedSchedulerDelegateKeepsActualStartup() {
    schedulerContext("LEGACY").run(context -> assertNull(context.getStartupFailure()));
  }

  private ApplicationContextRunner schedulerContext(String model) {
    return new ApplicationContextRunner()
        .withInitializer(
            context ->
                context
                    .getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withPropertyValues("TASK_IDENTITY_MODEL=" + model)
        .withBean(
            DeleteUserAccountScheduler.class,
            () ->
                new DeleteUserAccountScheduler(
                    service,
                    mock(TenantContextProvider.class),
                    mock(ScheduledTaskClaimService.class),
                    claim,
                    mock(IdentityReactivationRepairService.class)));
  }

  private void consultantDeletion(boolean fiveActors) {
    String account = "11111111-1111-4111-8111-111111111111";
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:consultant-refusal-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("create table consultant(consultant_id varchar(36) primary key)");
    jdbc.update("insert into consultant values(?)", account);
    var consultant = new Consultant();
    consultant.setId(account);
    consultant.setTenantId(7L);
    consultant.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(4));
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    consultant.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    when(users.findAllByDeleteDateNotNull()).thenReturn(List.of());
    when(consultants.findAllByDeleteDateNotNull()).thenReturn(List.of(consultant));
    when(consultants.save(any(Consultant.class))).thenAnswer(call -> call.getArgument(0));
    doAnswer(
            call -> {
              jdbc.update(
                  "delete from consultant where consultant_id=?",
                  ((Consultant) call.getArgument(0)).getId());
              return null;
            })
        .when(consultants)
        .delete(any(Consultant.class));
    when(config.isFiveActorMode()).thenReturn(fiveActors);
    var nativeUsers = mock(UsersResource.class);
    when(nativeClient.getUsersResource()).thenReturn(nativeUsers);
    when(nativeUsers.get(account)).thenReturn(nativeUser);
    var identity =
        new KeycloakService(
            new AuthenticatedUser(),
            mock(UserAccountInputValidator.class),
            config,
            nativeClient,
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class));
    var sessions = mock(SessionOwnershipService.class);
    var supervisors = mock(SessionSupervisorRepository.class);
    var mobileTokens = mock(ConsultantMobileTokenRepository.class);
    var tombstones = mock(IdentityTombstoneService.class);
    var database =
        spy(
            new DeleteDatabaseConsultantAction(
                consultants, sessions, supervisors, mobileTokens, tombstones));
    actions.setCustomClassForAction(
        DeleteKeycloakConsultantAction.class, new DeleteKeycloakConsultantAction(identity));
    actions.setCustomClassForAction(DeleteDatabaseConsultantAction.class, database);
    when(registry.buildContainerForType(ConsultantDeletionWorkflowDTO.class))
        .thenAnswer(ignored -> actions.getActionContainer(ConsultantDeletionWorkflowDTO.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from consultant", Integer.class));

    service.deleteUserAccounts();

    if (fiveActors) {
      assertEquals(1, jdbc.queryForObject("select count(*) from consultant", Integer.class));
      verifyNoInteractions(nativeClient);
      verifyNoInteractions(
          actions.getActionMock(DeleteMatrixConsultantAction.class),
          database,
          sessions,
          supervisors,
          mobileTokens,
          tombstones);
      verify(consultants, never()).delete(any(Consultant.class));
      verify(mail).buildAndSendErrorMail(argThat(errors -> errors.size() == 1));
    } else {
      assertEquals(0, jdbc.queryForObject("select count(*) from consultant", Integer.class));
      verify(nativeUser).remove();
      verify(actions.getActionMock(DeleteMatrixConsultantAction.class)).execute(any());
      verify(database).execute(any());
      verify(consultants).delete(consultant);
      verifyNoInteractions(mail);
    }
  }

  @Test
  void publicSchedulerFreezesOwnedDeletionBeforeRpcAndAcknowledgesOnlyVerifiedReceipt()
      throws Exception {
    scheduledWire(false, false);
  }

  @Test
  void publicSchedulerRejectsInvalidReceiptAndRetainsTheExactFrozenIntent() throws Exception {
    scheduledWire(true, false);
  }

  @Test
  void publicSchedulerLosingLeaseBeforeFreezeCannotSendTheNativeDelete() throws Exception {
    scheduledWire(false, true);
  }

  @Test
  void publicSchedulerNewOwnerReplaysFrozenDeleteAfterLostReplyAndAckNeverCallsNativeAgain()
      throws Exception {
    scheduledWire(false, false, true);
  }

  private void scheduledWire(boolean invalidReceipt, boolean lostLease) throws Exception {
    scheduledWire(invalidReceipt, lostLease, false);
  }

  private void scheduledWire(boolean invalidReceipt, boolean lostLease, boolean lostReply)
      throws Exception {
    String account = "11111111-1111-4111-8111-111111111111";
    String realmId = "22222222-2222-4222-8222-222222222222";
    var clock = actualClock;
    var now = LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    assertInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class, actualTransactions);
    var source = actualSource;
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("drop table if exists identity_maintenance_intent");
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/changelog/changeset/20261021_identity_maintenance_367/migrate.sql"),
            new org.springframework.core.io.ClassPathResource(
                "db/changelog/changeset/20261022_identity_maintenance_retry_367/migrate.sql"))
        .execute(source);
    jdbc.update("delete from scheduled_task_claim where task_name='account-deletion'");
    actualUsers.findById(account).ifPresent(actualUsers::delete);
    var persisted = new User(account, null,
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder().encodeUsername("fixture"),
        "fixture@fixture.invalid", false);
    persisted.setTenantId(7L);
    persisted.setLanguageCode(com.neovisionaries.i18n.LanguageCode.en);
    persisted.setDeleteDate(now.minusDays(4));
    persisted.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    persisted.setDeletionReadOnlyUntil(now.minusDays(1));
    actualUsers.save(persisted);
    assertEquals(1, jdbc.queryForObject("select count(*) from user where user_id=?", Integer.class, account));
    var claims = actualClaims;
    var continued = new AtomicInteger();
    var actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN,
            "backend-account-admin",
            "public-fixture-secret",
            "33333333-3333-4333-8333-333333333333");
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    var broker = mock(TaskActorTokenBroker.class);
    var token = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(token.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(token.accessToken()).thenReturn("fixture-admin");
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(token);
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    var codec = new AccountLifecycleCodec(key);
    var http = new RestTemplate();
    var server = MockRestServiceServer.bindTo(http).build();
    var requests = new AtomicInteger();
    var firstDelete = new AtomicReference<String>();
    var receiverReply = new AtomicReference<String>();
    var receiverEffects = new AtomicInteger();
    String endpoint =
        "https://identity.test/realms/fixture/oriso-account-maintenance/v1/accounts/" + account;
    server
        .expect(requestTo(endpoint + "/read"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer fixture-admin"))
        .andRespond(
            request -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(
                  1,
                  jdbc.queryForObject(
                      "select count(*) from identity_maintenance_intent where status='PREPARED'",
                      Integer.class));
              var body =
                  codec.read(((MockClientHttpRequest) request).getBodyAsString(), JsonNode.class);
              assertMaintenanceProof(request, codec, body, "read", realmId, account, actor);
              assertEquals(account, body.path("target").path("accountId").asText());
              assertEquals("7", body.path("target").path("tenantId").asText());
              assertEquals("DELETION", body.path("target").path("origin").path("kind").asText());
              assertTrue(body.path("target").path("origin").path("humanSubject").isNull());
              requests.incrementAndGet();
              if (lostLease) {
                jdbc.update(
                    "update scheduled_task_claim set claimed_until=? where task_name=?",
                    jdbc.queryForObject("select claimed_until from scheduled_task_claim where task_name='account-deletion'", java.sql.Timestamp.class).toLocalDateTime().plusSeconds(1),
                    "account-deletion");
              }
              return withSuccess(
                      "{\"id\":\""
                          + account
                          + "\",\"username\":\"fixture\",\"email\":null,\"firstName\":null,"
                          + "\"lastName\":null,\"enabled\":false,\"emailVerified\":false,"
                          + "\"tenantId\":\"7\",\"preferredLanguage\":null,"
                          + "\"effectiveRoles\":[\"user\"],"
                          + "\"passwordChangeRequired\":false,\"state\":\""
                          + "a".repeat(64)
                          + "\"}",
                      MediaType.APPLICATION_JSON)
                  .createResponse(request);
            });
    if (!lostLease) {
      server
          .expect(
              lostReply ? ExpectedCount.twice() : ExpectedCount.once(),
              requestTo(endpoint + "/delete"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-admin"))
          .andRespond(
              request -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                String wire = ((MockClientHttpRequest) request).getBodyAsString();
                var body = codec.read(wire, JsonNode.class);
                assertMaintenanceProof(request, codec, body, "delete", realmId, account, actor);
                String operation = body.path("operationId").asText();
                assertEquals(operation, UUID.fromString(operation).toString());
                var frozen =
                    jdbc.queryForMap(
                        "select status, frozen_body from identity_maintenance_intent"
                            + " where operation_id=?",
                        operation);
                assertEquals("FROZEN", frozen.get("STATUS"));
                var savedEnvelope = codec.read(
                    jdbc.queryForObject("select frozen_body from identity_maintenance_intent where operation_id=?", String.class, operation),
                    com.fasterxml.jackson.databind.node.ObjectNode.class);
                assertEquals(codec.canonical(body), codec.canonical(savedEnvelope.path("body")));
                assertEquals("CLAIMED_SCHEDULED_ASKER_DELETE_V1", savedEnvelope.path("representation").textValue());
                assertEquals(29, savedEnvelope.path("originalSql").size());
                String binding = savedEnvelope.remove("bindingDigest").textValue();
                assertEquals(binding, codec.claimedScheduledDeletionIntentDigest(savedEnvelope));
                assertEquals("a".repeat(64), body.path("expectedState").asText());
                assertEquals(0, body.path("change").size());
                var outcome =
                    new java.util.TreeMap<String, Object>(
                        Map.of(
                            "realmId",
                            realmId,
                            "accountId",
                            account,
                            "operationId",
                            operation,
                            "command",
                            "delete",
                            "ownerClient",
                            actor.clientId(),
                            "ownerSubject",
                            actor.serviceSubject(),
                            "intentDigest",
                            codec.digest("maintenance-intent", body),
                            "previousState",
                            body.path("expectedState").asText(),
                            "tenantId",
                            "7",
                            "result",
                            "DELETED"));
                outcome.put(
                    "originDigest",
                    codec.digest("maintenance-origin", body.path("target").path("origin")));
                String receipt = nativeReceipt(key, codec, outcome);
                if (invalidReceipt) {
                  receipt =
                      receipt.substring(0, receipt.length() - 1)
                          + (receipt.endsWith("0") ? "1" : "0");
                }
                String reply =
                    codec.canonical(
                        Map.of(
                            "operationId", operation,
                            "accountId", account,
                            "result", "DELETED",
                            "receipt", receipt));
                requests.incrementAndGet();
                if (firstDelete.compareAndSet(null, wire)) {
                  receiverEffects.incrementAndGet();
                  receiverReply.set(reply);
                  if (lostReply) throw new java.io.IOException("Synthetic committed reply lost");
                } else {
                  assertEquals(firstDelete.get(), wire);
                  assertEquals(receiverReply.get(), reply);
                }
                return withSuccess(receiverReply.get(), MediaType.APPLICATION_JSON)
                    .createResponse(request);
              });
    }
    java.util.function.Supplier<ScheduledAskerMaintenance> freshWorker =
        () -> {
          var owner = new ScheduledAskerMaintenance(
                jdbc,
                actualTransactions,
                claims,
                clock,
                lifecycle,
                actors,
                broker,
                codec,
                http,
                "https://identity.test",
                "fixture",
                realmId);
          owner.configureJpaCleanupOwner(actualEntities);
          owner.configureAcknowledgedCleanup(frame -> {
            frame.requireCurrent();
            assertEquals(account, frame.accountId());
            assertEquals(7L, frame.tenantId());
            frame.runOwnedLocal(() -> {
              assertTrue(actualEntities.isJoinedToTransaction());
              frame.requireCurrentUser(actualUsers.findById(account).orElseThrow());
            });
            continued.incrementAndGet();
          });
          return owner;
        };
    var scheduler =
        new DeleteUserAccountScheduler(
            service,
            mock(TenantContextProvider.class),
            claims,
            claim,
            mock(IdentityReactivationRepairService.class));
    ReflectionTestUtils.setField(scheduler, "scopedMaintenance", freshWorker.get());
    if (lostReply) {
      // One refused account must retain its intent without aborting later candidates.
      assertDoesNotThrow(scheduler::performDeletionWorkflow);
      assertEquals(
          1,
          jdbc.queryForObject(
              "select count(*) from identity_maintenance_intent where status='FROZEN'"
                  + " and receipt_json is null and frozen_body is not null",
              Integer.class));
      assertEquals(firstDelete.get(), codec.canonical(codec.read(
          jdbc.queryForObject("select frozen_body from identity_maintenance_intent", String.class), JsonNode.class).path("body")));
      ReflectionTestUtils.setField(scheduler, "scopedMaintenance", freshWorker.get());
      scheduler.performDeletionWorkflow();
      // A fresh owner may retry an ACK for cleanup, but cannot issue another native effect.
      ReflectionTestUtils.setField(scheduler, "scopedMaintenance", freshWorker.get());
      scheduler.performDeletionWorkflow();
      assertEquals(
          1,
          jdbc.queryForObject("select count(*) from identity_maintenance_intent", Integer.class));
      assertEquals(1, receiverEffects.get());
    } else if (invalidReceipt) {
      assertDoesNotThrow(scheduler::performDeletionWorkflow);
    } else {
      scheduler.performDeletionWorkflow();
    }
    assertEquals(
        invalidReceipt || lostLease ? 0 : 1,
        jdbc.queryForObject(
            "select count(*) from identity_maintenance_intent where status='ACKNOWLEDGED'"
                + " and receipt_json is not null",
            Integer.class));
    if (invalidReceipt) {
      assertEquals(
          1,
          jdbc.queryForObject(
              "select count(*) from identity_maintenance_intent where status='FROZEN'"
                  + " and receipt_json is null and frozen_body is not null",
              Integer.class));
    }
    if (lostLease) {
      assertEquals(
          1,
          jdbc.queryForObject(
              "select count(*) from identity_maintenance_intent where status='PREPARED'"
                  + " and frozen_body is null and receipt_json is null",
              Integer.class));
    }
    assertEquals(lostLease ? 1 : lostReply ? 3 : 2, requests.get());
    server.verify();
    assertEquals(1, jdbc.queryForObject("select count(*) from user where user_id=?", Integer.class, account));
    assertEquals(invalidReceipt || lostLease ? 0 : lostReply ? 2 : 1, continued.get());
    verifyNoInteractions(nativeClient, users, consultants, registry, mail);
  }

  private String nativeReceipt(byte[] key, AccountLifecycleCodec codec, Object outcome) {
    try {
      String encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return encoded
          + "."
          + HexFormat.of()
              .formatHex(
                  mac.doFinal(
                      ("maintenance-operation-receipt\n" + encoded)
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Synthetic receipt unavailable", failure);
    }
  }

  private void assertMaintenanceProof(
      org.springframework.http.HttpRequest request,
      AccountLifecycleCodec codec,
      JsonNode body,
      String command,
      String realm,
      String account,
      TaskActorCredentials actor) {
    String proof = request.getHeaders().getFirst("X-ORISO-Task-Context");
    assertNotNull(proof);
    String[] parts = proof.split("\\.", -1);
    assertEquals(2, parts.length);
    var claims =
        codec.read(
            new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
            JsonNode.class);
    assertEquals("userservice", claims.path("iss").asText());
    assertEquals("oriso-account-maintenance", claims.path("aud").asText());
    assertEquals("ACCOUNT_MAINTENANCE", claims.path("purpose").asText());
    assertEquals("ACCOUNT_ADMIN", claims.path("actor").asText());
    assertEquals("account." + command, claims.path("operation").asText());
    assertEquals("POST", claims.path("method").asText());
    assertEquals("fixture", claims.path("realm").asText());
    assertEquals("realm/" + realm + "/account/" + account, claims.path("target").asText());
    assertEquals(actor.clientId(), claims.path("taskClient").asText());
    assertEquals(actor.serviceSubject(), claims.path("taskSubject").asText());
    assertEquals(codec.digest("payload", body), claims.path("payloadDigest").asText());
  }
}
