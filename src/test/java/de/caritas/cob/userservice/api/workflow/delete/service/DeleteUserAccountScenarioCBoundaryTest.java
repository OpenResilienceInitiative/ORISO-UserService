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
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.scheduler.DeleteUserAccountScheduler;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/**
 * Actual scheduled-service boundary; a missing C capability cannot authorize downstream cleanup.
 */
class DeleteUserAccountScenarioCBoundaryTest {
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
  void publicSchedulerFreezesOwnedDeletionBeforeRpcAndAcknowledgesOnlyVerifiedReceipt()
      throws Exception {
    String account = "11111111-1111-4111-8111-111111111111";
    String realmId = "22222222-2222-4222-8222-222222222222";
    var clock = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    var now = LocalDateTime.now(clock);
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:scheduled-maintenance-"
                + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute(
        "create table scheduled_task_claim(task_name varchar(128) primary key,"
            + " claimed_at timestamp(6), claimed_until timestamp(6))");
    jdbc.execute(
        "create table user(user_id varchar(36) primary key, tenant_id bigint,"
            + " delete_date timestamp(6), deletion_lifecycle_state varchar(64),"
            + " deletion_read_only_until timestamp(6), deletion_paused_until timestamp(6))");
    jdbc.execute(
        "create table identity_maintenance_intent(operation_id varchar(36) primary key,"
            + " realm_id varchar(36), realm_name varchar(255), subject_type varchar(32),"
            + " subject_id varchar(36), deletion_started_at timestamp(6), tenant_id bigint,"
            + " claim_token varchar(36), claim_until timestamp(6), status varchar(32),"
            + " expected_state varchar(64), frozen_body clob, receipt_json clob,"
            + " unique(subject_type, subject_id, deletion_started_at))");
    jdbc.update(
        "insert into scheduled_task_claim values(?,?,?)",
        "account-deletion",
        now,
        now.plusHours(12));
    jdbc.update(
        "insert into user values(?,?,?,?,?,?)",
        account,
        7L,
        now.minusDays(4),
        "READ_ONLY_SAFEGUARD",
        now.minusDays(1),
        null);
    assertEquals(1, jdbc.queryForObject("select count(*) from user", Integer.class));
    var claims = mock(ScheduledTaskClaimService.class);
    when(claims.tryClaimLease("account-deletion", Duration.ofHours(12)))
        .thenReturn(
            Optional.of(
                new ScheduledTaskClaimService.ClaimLease("account-deletion", now.plusHours(12))));
    var actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN,
            "backend-account-admin",
            "public-fixture-secret",
            "33333333-3333-4333-8333-333333333333");
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    var broker = mock(TaskActorTokenBroker.class);
    when(broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(TaskActor.ACCOUNT_ADMIN, "fixture-admin", 60));
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    var codec = new AccountLifecycleCodec(key);
    var http = new RestTemplate();
    var server = MockRestServiceServer.bindTo(http).build();
    var requests = new AtomicInteger();
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
    server
        .expect(requestTo(endpoint + "/delete"))
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
              assertEquals(
                  codec.canonical(body),
                  jdbc.queryForObject(
                      "select frozen_body from identity_maintenance_intent where operation_id=?",
                      String.class,
                      operation));
              assertEquals("a".repeat(64), body.path("expectedState").asText());
              assertEquals(0, body.path("change").size());
              var outcome =
                  new java.util.TreeMap<String, Object>(
                      Map.of(
                          "realmId", realmId,
                          "accountId", account,
                          "operationId", operation,
                          "command", "delete",
                          "ownerClient", actor.clientId(),
                          "ownerSubject", actor.serviceSubject(),
                          "intentDigest", codec.digest("maintenance-intent", body),
                          "previousState", body.path("expectedState").asText(),
                          "tenantId", "7",
                          "result", "DELETED"));
              outcome.put(
                  "originDigest",
                  codec.digest("maintenance-origin", body.path("target").path("origin")));
              String receipt = nativeReceipt(key, codec, outcome);
              requests.incrementAndGet();
              return withSuccess(
                      codec.canonical(
                          Map.of(
                              "operationId", operation,
                              "accountId", account,
                              "result", "DELETED",
                              "receipt", receipt)),
                      MediaType.APPLICATION_JSON)
                  .createResponse(request);
            });
    var worker =
        new ScheduledAskerMaintenance(
            jdbc,
            new DataSourceTransactionManager(source),
            claims,
            clock,
            actors,
            broker,
            codec,
            http,
            "https://identity.test",
            "fixture",
            realmId);
    var scheduler =
        new DeleteUserAccountScheduler(
            service,
            mock(TenantContextProvider.class),
            claims,
            claim,
            mock(IdentityReactivationRepairService.class));
    ReflectionTestUtils.setField(scheduler, "scopedMaintenance", worker);
    scheduler.performDeletionWorkflow();
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from identity_maintenance_intent where status='ACKNOWLEDGED'"
                + " and receipt_json is not null",
            Integer.class));
    assertEquals(2, requests.get());
    server.verify();
    assertEquals(1, jdbc.queryForObject("select count(*) from user", Integer.class));
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
