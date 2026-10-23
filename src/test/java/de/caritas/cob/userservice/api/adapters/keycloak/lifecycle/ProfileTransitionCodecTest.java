package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Fixed command/data MACs only; actual human/profile grants are independently enforced natively. */
class ProfileTransitionCodecTest {
  private static final String ACCOUNT="86000000-0000-4000-8000-000000000001",OP="86000000-0000-4000-8000-000000000002";
  private static final byte[] KEY="synthetic-profile-transition-codec".getBytes(StandardCharsets.UTF_8);
  private final AccountLifecycleCodec codec=new AccountLifecycleCodec(KEY);
  private final ObjectMapper json=new ObjectMapper();
  private final TaskActorCredentials actor=new TaskActorCredentials(TaskActor.ACCOUNT_ADMIN,"backend-account-admin","synthetic-only","86000000-0000-4000-8000-000000000003");

  @Test void fixedProfileTransitionProofRetainsExactlyTheOriginalFifteenClaims() throws Exception {
    for(boolean protectedTarget:List.of(false,true)) {
      var body=body(protectedTarget);
      String proof=assertDoesNotThrow(() -> codec.maintenanceProof("profile-transition","test","realm-1",ACCOUNT,actor,body));
      String[] parts=proof.split("\\.",-1);assertEquals(2,parts.length);
      assertArrayEquals(hmac("proof\n"+parts[0]),Base64.getUrlDecoder().decode(parts[1]));
      var claims=json.readTree(Base64.getUrlDecoder().decode(parts[0]));
      assertEquals(Set.of("iss","aud","purpose","operation","method","actor","realm","target","taskClient","taskSubject","payloadDigest","jti","nonce","iat","exp"),fields(claims));
      assertEquals("account.profile-transition",claims.path("operation").textValue());
      assertEquals("ACCOUNT_MAINTENANCE",claims.path("purpose").textValue());
      assertEquals("POST",claims.path("method").textValue());
      assertEquals("realm/realm-1/account/"+ACCOUNT,claims.path("target").textValue());
      assertEquals(digest("payload",body),claims.path("payloadDigest").textValue());
      assertEquals(60,claims.path("exp").longValue()-claims.path("iat").longValue());
    }
  }

  @Test void fixedProfileTransitionUpdatedReceiptBindsBothClosedUnionBodiesAndAllElevenFields() throws Exception {
    for(boolean protectedTarget:List.of(false,true)) {
      var body=body(protectedTarget);var signed=outcome(body,"UPDATED");String reply=reply(signed,false,0);
      assertEquals(reply,assertDoesNotThrow(() -> codec.verifyMaintenanceProfileTransitionReceipt(reply,"realm-1",actor,body)));
      assertEquals(11,signed.size());
      for(String field:new ArrayList<>(signed.keySet())) {
        var changed=new TreeMap<>(signed);changed.put(field,field.equals("operationId")?"86000000-0000-4000-8000-000000000099":field.endsWith("Digest")||field.equals("previousState")?"f".repeat(64):"foreign");
        assertThrows(IllegalArgumentException.class,() -> codec.verifyMaintenanceProfileTransitionReceipt(reply(changed,false,0),"realm-1",actor,body),field);
      }
      var drift=body.deepCopy();((ObjectNode)drift.path("change")).put("transition","ADD_AGENCY_ADMIN_IDENTITY");
      assertThrows(IllegalArgumentException.class,() -> codec.verifyMaintenanceProfileTransitionReceipt(reply,"realm-1",actor,drift));
    }
  }

  @Test void onlyBoundSigned403Or409CanBecomeAProfileTransitionNoEffect() throws Exception {
    for(int status:List.of(403,409)) {
      var body=body(true);var signed=outcome(body,"NO_EFFECT");signed.put("status",status);String reply=reply(signed,true,status);
      var verified=assertDoesNotThrow(() -> codec.verifyMaintenanceNoEffectReceipt(reply,"realm-1",actor,body,"profile-transition"));
      assertEquals(status,verified.status());assertEquals(UUID.fromString(OP),verified.operationId());assertEquals("profile-transition",verified.command());
      for(String field:new ArrayList<>(signed.keySet())) {
        var changed=new TreeMap<>(signed);changed.put(field,field.equals("status")?status==403?409:403:field.equals("operationId")?"86000000-0000-4000-8000-000000000099":field.endsWith("Digest")||field.equals("previousState")?"f".repeat(64):"foreign");
        assertThrows(IllegalArgumentException.class,() -> codec.verifyMaintenanceNoEffectReceipt(reply(changed,true,status),"realm-1",actor,body,"profile-transition"),field);
      }
      var wrong400=outcome(body,"NO_EFFECT");wrong400.put("status",400);
      assertThrows(IllegalArgumentException.class,() -> codec.verifyMaintenanceNoEffectReceipt(reply(wrong400,true,400),"realm-1",actor,body,"profile-transition"));
    }
  }

  private ObjectNode body(boolean protectedTarget) {
    var origin=Map.of("kind","HUMAN_ADMIN","workflowId",OP,"humanSubject","86000000-0000-4000-8000-000000000004","humanIssuer","https://public-auth.example.test/realms/test","humanClient","admin","humanAuthority","PROTECTED_ACCOUNT_OPERATIONS_V1");
    return json.valueToTree(Map.of("target",Map.of("accountId",ACCOUNT,"tenantId","7","origin",origin),"operationId",OP,"expectedState","a".repeat(64),"change",protectedTarget?Map.of("transition","ADD_CONSULTANT_IDENTITY","profileId","reviewed-tenant-consultant-v7","profileVersion",Long.MAX_VALUE):Map.of("transition","ADD_CONSULTANT_IDENTITY","group","/human-roles/consultant-agency-admin")));
  }
  private TreeMap<String,Object> outcome(JsonNode body,String result) throws Exception {
    var signed=new TreeMap<String,Object>();signed.put("realmId","realm-1");signed.put("accountId",ACCOUNT);signed.put("operationId",OP);signed.put("command","profile-transition");signed.put("ownerClient",actor.clientId());signed.put("ownerSubject",actor.serviceSubject());signed.put("intentDigest",digest("maintenance-intent",body));signed.put("previousState",body.path("expectedState").textValue());signed.put("tenantId","7");signed.put("originDigest",digest("maintenance-origin",body.path("target").path("origin")));signed.put("result",result);return signed;
  }
  private String reply(Map<String,Object> signed,boolean refused,int status) throws Exception {
    String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(codec.canonical(signed).getBytes(StandardCharsets.UTF_8));
    String receipt=encoded+"."+HexFormat.of().formatHex(hmac((refused?"maintenance-no-effect-receipt/v1":"maintenance-operation-receipt")+"\n"+encoded));
    var outer=new TreeMap<String,Object>();outer.put("operationId",OP);outer.put("accountId",ACCOUNT);outer.put("result",refused?"NO_EFFECT":"UPDATED");outer.put("receipt",receipt);if(refused)outer.put("status",status);return codec.canonical(outer);
  }
  private Set<String> fields(JsonNode value) {var result=new HashSet<String>();value.fieldNames().forEachRemaining(result::add);return result;}
  private String digest(String domain,JsonNode value) throws Exception {return HexFormat.of().formatHex(hmac(domain+"\n"+codec.canonical(value)));}
  private byte[] hmac(String value) throws Exception {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}
}
