package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakInactivityMaintenance;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.workflow.delete.service.InactiveAskerDeletionService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.*;

/** Real owning SQL, default effects and fixed HTTP adapter; native receipts are synthetic. */
class ScopedInactivityNativeBoundaryTest {
  private static final String ACCOUNT="83000000-0000-4000-8000-000000000001";
  private static final String SUBJECT="83000000-0000-4000-8000-000000000002";
  private static final String HUMAN="83000000-0000-4000-8000-000000000003";
  private static final String ISSUER="https://public-auth.example.test/realms/test";
  private static final String URL="https://internal-auth.example.test/realms/test/oriso-account-maintenance/v1/accounts/"+ACCOUNT;
  private static final String MATRIX="@existing:example.test";
  private static final byte[] KEY="synthetic-inactivity-boundary-key-32".getBytes(StandardCharsets.UTF_8);
  private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"),ZoneOffset.UTC);

  @Test
  void acknowledgedScheduledSuspensionAndFreshHumanRestorePreserveOriginalEnabledAndMatrixLock() throws Exception {
    for(boolean originalEnabled:List.of(true,false)) {
      var f=new Fixture(originalEnabled);
      f.read(false); f.mutate("deactivate",false,false);
      f.read(true); f.mutate("reactivate",true,false);
      f.service.scan(false);
      assertEquals(AccountInactivityService.Status.SUSPENDED,f.service.snapshot(ACCOUNT).orElseThrow().status());
      String suspension=f.value("work_id");
      assertEquals("ACKNOWLEDGED",f.value("native_phase"));
      assertEquals(suspension,f.value("suspension_operation_id"));
      assertEquals(originalEnabled,f.jdbc.queryForObject("select keycloak_enabled from account_inactivity_access_state where identity_id=?",Boolean.class,ACCOUNT));
      assertEquals(List.of("matrix-read","native-deactivate","matrix-true","media-revoke"),f.order);
      f.platformHuman();
      assertTrue(assertDoesNotThrow(() -> f.service.reactivate(ACCOUNT)));
      assertNotEquals(suspension,f.value("work_id"));
      assertEquals(suspension,f.restore.path("workflow").path("suspensionOperationId").textValue());
      assertEquals(originalEnabled,f.restore.path("change").path("restoreEnabled").booleanValue());
      assertEquals(AccountInactivityService.Status.ACTIVE,f.service.snapshot(ACCOUNT).orElseThrow().status());
      assertEquals(List.of("matrix-read","native-deactivate","matrix-true","media-revoke","matrix-true","media-restore","native-reactivate"),f.order);
      assertTrue(f.jdbc.queryForObject("select restored from account_inactivity_access_state where identity_id=?",Boolean.class,ACCOUNT));
      assertFalse(f.value("claimed_json").contains("synthetic-platform-human"));
      verifyNoInteractions(f.sdk); f.server.verify();
    }
  }

  @Test
  void unknownNativeReplyKeepsTheSameOperationBodyAndCasAcrossTheNextActualDueScan() throws Exception {
    var f=new Fixture(true);
    f.read(false); f.mutate("deactivate",false,true); f.mutate("deactivate",false,false);
    f.service.scan(false);
    assertEquals(AccountInactivityService.Status.SUSPENDING,f.service.snapshot(ACCOUNT).orElseThrow().status());
    assertEquals("FROZEN",f.value("native_phase"));
    String operation=f.value("work_id"), body=f.value("native_body"), original=f.value("claimed_json");
    assertNull(f.value("native_receipt"));
    f.service.scan(false);
    assertEquals(operation,f.value("work_id"));assertEquals(body,f.value("native_body"));assertEquals(original,f.value("claimed_json"));
    assertEquals(AccountInactivityService.Status.SUSPENDED,f.service.snapshot(ACCOUNT).orElseThrow().status());
    assertEquals(List.of("matrix-read","native-deactivate","native-deactivate","matrix-true","media-revoke"),f.order);
    verify(f.broker,times(3)).issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    verifyNoInteractions(f.sdk);f.server.verify();
  }

  @Test
  void changedActualSqlIncarnationCannotResumeOrRebindAnUnknownNativeCommand() throws Exception {
    var f=new Fixture(true);f.read(false);f.mutate("deactivate",false,true);
    f.service.scan(false);
    assertEquals("FROZEN",f.value("native_phase"));
    String body=f.value("native_body"), original=f.value("claimed_json"), operation=f.value("work_id");
    f.jdbc.update("update consultant set create_date=? where consultant_id=?",java.sql.Timestamp.from(Instant.parse("2025-01-02T00:00:00Z")),ACCOUNT);
    f.service.scan(false);
    assertEquals(operation,f.value("work_id"));assertEquals(original,f.value("claimed_json"));assertEquals(body,f.value("native_body"));
    assertEquals(AccountInactivityService.Status.SUSPENDING,f.service.snapshot(ACCOUNT).orElseThrow().status());
    assertNull(f.value("native_receipt"));verifyNoInteractions(f.sdk);f.server.verify();
  }

  @Test
  void aCorruptedAcknowledgedSuspensionCannotRestoreMatrixOrMediaBeforeAuthentication() throws Exception {
    var f=new Fixture(true,false);f.read(false);f.mutate("deactivate",false,false);
    f.service.scan(false);
    assertEquals(AccountInactivityService.Status.SUSPENDED,f.service.snapshot(ACCOUNT).orElseThrow().status());
    var suspended=List.copyOf(f.order);
    f.jdbc.update("update account_inactivity set suspension_receipt='synthetic-invalid' where identity_id=?",ACCOUNT);
    f.platformHuman();
    assertThrows(RuntimeException.class,() -> f.service.reactivate(ACCOUNT));
    assertEquals(suspended,f.order,"Authenticate the named original suspension before restoring any access");
    assertEquals(AccountInactivityService.Status.REACTIVATING,f.service.snapshot(ACCOUNT).orElseThrow().status());
    verifyNoInteractions(f.sdk);f.server.verify();
  }

  private static final class Fixture {
    final ObjectMapper json=new ObjectMapper();final AccountLifecycleCodec codec=new AccountLifecycleCodec(KEY);
    final JdbcTemplate jdbc;final KeycloakClient sdk=mock(KeycloakClient.class);
    final MatrixSynapseService matrix=mock(MatrixSynapseService.class);
    final AccountInactivityMediaClient media=mock(AccountInactivityMediaClient.class);
    final TaskActorTokenBroker broker=mock(TaskActorTokenBroker.class);
    final TaskActorConfiguration actors=mock(TaskActorConfiguration.class);
    final TaskActorCredentials actor=new TaskActorCredentials(TaskActor.ACCOUNT_ADMIN,"backend-account-admin","synthetic-only",SUBJECT);
    final RestTemplate http=new RestTemplate();final MockRestServiceServer server=MockRestServiceServer.bindTo(http).build();
    final List<String> order=new ArrayList<>();final AccountInactivityService service;
    final AuthenticatedUser human=new AuthenticatedUser();final MockHttpServletRequest request=new MockHttpServletRequest();
    final boolean originalEnabled;JsonNode suspend,restore;
    Fixture(boolean enabled) { this(enabled,true); }
    Fixture(boolean enabled,boolean locked) {
      originalEnabled=enabled;
      var ds=new DriverManagerDataSource("jdbc:h2:mem:inactivity-native-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=MySQL;NON_KEYWORDS=USER","sa","");jdbc=new JdbcTemplate(ds);
      jdbc.execute("create table account_inactivity(identity_id varchar(36) primary key,tenant_id bigint,assigned_months int not null,revision bigint not null,last_activity timestamp(6) not null,due_at timestamp(6) not null,status varchar(20) not null,last_error varchar(1000),attempts int default 0 not null)");
      jdbc.execute("create table account_inactivity_journal(journal_id bigint generated by default as identity primary key,identity_id varchar(36),action varchar(30),target varchar(30),outcome varchar(40),created_at timestamp(6))");
      try (var stream = new org.springframework.core.io.ClassPathResource("db/changelog/changeset/20261023_inactivity_claimed_work_367/migrate.sql").getInputStream()) {
        String migration = new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        String[] columns = migration.replaceFirst("(?s)^.*?ALTER TABLE account_inactivity\\s+ADD COLUMN\\s+", "").replaceFirst(";\\s*$", "").split(",\\s*ADD COLUMN\\s+");
        assertEquals(11,columns.length);
        for(String column:columns) jdbc.execute("ALTER TABLE account_inactivity ADD COLUMN "+column);
      } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/changelog/changeset/20260916_account_inactivity_access/migrate.sql")).execute(ds);
      for(String table:List.of("user","consultant","admin")) jdbc.execute("create table `"+table+"`("+(table.equals("user")?"user":table)+"_id varchar(36),tenant_id bigint,username varchar(255),create_date timestamp(6),matrix_user_id varchar(255))");
      jdbc.execute("create table general_admin_identity_intent(operation_id varchar(36),account_id varchar(36),status varchar(20))");
      jdbc.update("insert into consultant values(?,7,'existing-consultant',?,?)",ACCOUNT,java.sql.Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),MATRIX);
      when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
      var token=mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
      when(token.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);when(token.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);when(token.accessToken()).thenReturn("synthetic-checked-admin");when(token.clientId()).thenReturn(actor.clientId());when(token.subject()).thenReturn(SUBJECT);when(token.issuer()).thenReturn(ISSUER);when(token.validatedJwtIssuedAt()).thenReturn(Instant.now().minusSeconds(5).getEpochSecond());when(token.validatedJwtExpiresAt()).thenReturn(Instant.now().plusSeconds(300).getEpochSecond());when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(token);
      var manager=new DataSourceTransactionManager(ds);
      var effects=new DefaultAccountInactivityEffects(jdbc,manager,sdk,matrix,media,new StaticListableBeanFactory().getBeanProvider(InactiveAskerDeletionService.class));
      var mode=mock(IdentityClientConfig.class);when(mode.isFiveActorMode()).thenReturn(true);effects.setIdentityMode(mode);
      service=new AccountInactivityService(jdbc,manager,CLOCK,effects);service.setIdentityMode(mode);
      service.configureScopedInactivity(new KeycloakInactivityMaintenance(http,actors,broker,codec,"https://internal-auth.example.test","test","realm-1",ISSUER),human,request,ISSUER,Set.of("admin"));
      service.assignAtCreation(ACCOUNT,7L,24,0,Instant.parse("2023-01-01T00:00:00Z"));
      when(matrix.getAccountLocked(MATRIX)).thenAnswer(inv -> {outside();order.add("matrix-read");return Optional.of(locked);});
      when(matrix.setAccountSuspended(eq(MATRIX),anyBoolean())).thenAnswer(inv -> {outside();order.add("matrix-"+inv.getArgument(1));return true;});
      doAnswer(inv -> {outside();order.add("media-revoke");return null;}).when(media).revoke(List.of(MATRIX));
      doAnswer(inv -> {outside();order.add("media-restore");return null;}).when(media).restore(List.of(MATRIX));
    }
    void platformHuman() {
      human.setUserId(HUMAN);human.setTenantId(0L);human.setAccessToken("synthetic-platform-human");human.setRoles(Set.of("agency-admin","tenant-admin"));
      var jwt=Jwt.withTokenValue("synthetic-platform-human").header("alg","RS256").issuer(ISSUER).subject(HUMAN).claim("azp","admin").claim("tenantId",0).issuedAt(CLOCK.instant().minusSeconds(5)).expiresAt(CLOCK.instant().plusSeconds(300)).build();
      request.setUserPrincipal(new JwtAuthenticationToken(jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("user-admin"))));request.addHeader("Authorization","Bearer synthetic-platform-human");
    }
    String value(String column) {return jdbc.queryForObject("select "+column+" from account_inactivity where identity_id=?",String.class,ACCOUNT);}
    void read(boolean restoring) {
      server.expect(requestTo(URL+"/read")).andExpect(method(HttpMethod.POST)).andRespond(outbound -> {
        try {
          var body=body(outbound,"read",restoring);assertEquals(Set.of("target","workflow"),fields(body));
          var value=new TreeMap<String,Object>();value.put("id",ACCOUNT);value.put("username","existing-consultant");value.put("email",null);value.put("firstName",null);value.put("lastName",null);value.put("enabled",restoring?false:originalEnabled);value.put("emailVerified",false);value.put("tenantId","7");value.put("preferredLanguage","de");value.put("effectiveRoles",List.of("consultant"));value.put("passwordChangeRequired",false);value.put("state","a".repeat(64));
          var response=withSuccess(canonical(value),MediaType.APPLICATION_JSON).createResponse(outbound);response.getHeaders().setCacheControl("no-store");return response;
        } catch(Exception invalid) {throw new java.io.IOException(invalid);}
      });
    }
    void mutate(String command,boolean restoring,boolean lost) {
      server.expect(requestTo(URL+"/"+command)).andExpect(method(HttpMethod.POST)).andRespond(outbound -> {
        try {
          var body=body(outbound,command,restoring);assertEquals(Set.of("target","workflow","operationId","expectedState","change"),fields(body));assertEquals(value("work_id"),body.path("operationId").textValue());assertEquals("a".repeat(64),body.path("expectedState").textValue());
          if(restoring) {restore=body;assertEquals(Set.of("restoreEnabled"),fields(body.path("change")));assertTrue(body.path("change").path("restoreEnabled").isBoolean());assertEquals(originalEnabled,body.path("change").path("restoreEnabled").booleanValue());}
          else {assertTrue(body.path("change").isEmpty());if(suspend!=null) assertEquals(suspend,body);suspend=body;}
          assertEquals("FROZEN",value("native_phase"));assertEquals(canonical(body),value("native_body"));order.add("native-"+command);
          if(lost) throw new ResourceAccessException("Synthetic unknown native reply");
          var signed=new TreeMap<String,Object>();signed.put("realmId","realm-1");signed.put("accountId",ACCOUNT);signed.put("operationId",body.path("operationId").textValue());signed.put("command",command);signed.put("ownerClient",actor.clientId());signed.put("ownerSubject",SUBJECT);signed.put("intentDigest",digest("maintenance-intent",body));signed.put("previousState",body.path("expectedState").textValue());signed.put("tenantId","7");signed.put("originDigest",digest("maintenance-origin",body.path("target").path("origin")));signed.put("result","UPDATED");
          String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(canonical(signed).getBytes(StandardCharsets.UTF_8));String receipt=encoded+"."+mac("maintenance-operation-receipt\n"+encoded);
          var response=withSuccess(canonical(Map.of("operationId",body.path("operationId").textValue(),"accountId",ACCOUNT,"result","UPDATED","receipt",receipt)),MediaType.APPLICATION_JSON).createResponse(outbound);response.getHeaders().setCacheControl("no-store");return response;
        } catch(ResourceAccessException unknown) {throw unknown;} catch(Exception invalid) {throw new java.io.IOException(invalid);}
      });
    }
    JsonNode body(org.springframework.http.client.ClientHttpRequest outbound,String command,boolean restoring) throws Exception {
      outside();assertEquals("Bearer synthetic-checked-admin",outbound.getHeaders().getFirst("Authorization"));
      assertEquals(restoring?"Bearer synthetic-platform-human":null,outbound.getHeaders().getFirst("X-ORISO-Human-Authorization"));
      var body=json.readTree(((MockClientHttpRequest)outbound).getBodyAsString());var workflow=body.path("workflow");
      assertEquals(Set.of("v","kind","workId","action","rowDigest","revision","assignedMonths","lastActivity","dueAt","suspensionOperationId"),fields(workflow));assertEquals(1,workflow.path("v").intValue());assertEquals("INACTIVITY",workflow.path("kind").textValue());assertEquals(value("work_id"),workflow.path("workId").textValue());assertEquals(restoring?"RESTORE":"SUSPEND",workflow.path("action").textValue());assertEquals(0,workflow.path("revision").longValue());assertEquals(24,workflow.path("assignedMonths").intValue());
      var original=json.readTree(value("claimed_json"));assertEquals(digest("claimed-inactivity-row/v1",original),workflow.path("rowDigest").textValue());assertEquals(original.path("lastActivityMicros").longValue()/1_000_000L,workflow.path("lastActivity").longValue());assertEquals(original.path("dueAtMicros").longValue()/1_000_000L,workflow.path("dueAt").longValue());
      assertEquals(restoring?"HUMAN_ADMIN":"INACTIVITY",body.path("target").path("origin").path("kind").textValue());assertEquals(restoring?"PROTECTED_ACCOUNT_OPERATIONS_V1":null,body.path("target").path("origin").path("humanAuthority").textValue());
      var parts=outbound.getHeaders().getFirst("X-ORISO-Task-Context").split("\\.",-1);assertEquals(2,parts.length);assertArrayEquals(HexFormat.of().parseHex(mac("proof\n"+parts[0])),Base64.getUrlDecoder().decode(parts[1]));var proof=json.readTree(Base64.getUrlDecoder().decode(parts[0]));assertEquals(15,proof.size());assertEquals("account."+command,proof.path("operation").textValue());assertEquals(digest("payload",body),proof.path("payloadDigest").textValue());
      return body;
    }
    String canonical(Object value) throws Exception {var writer=new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);return writer.writeValueAsString(writer.convertValue(value,Object.class));}
    String digest(String domain,JsonNode value) throws Exception {return mac(domain+"\n"+canonical(value));}
    String mac(String value) throws Exception {Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(KEY,"HmacSHA256"));return HexFormat.of().formatHex(hmac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
    static Set<String> fields(JsonNode node) {var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);return fields;}
    static void outside() {assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
  }
}
