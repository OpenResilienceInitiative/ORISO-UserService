package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Closed data schema for the one saved-only SELF deactivate selector; no live authority. */
final class PreDeletionProofSchema {
  static JsonNode require(JsonNode body,String account){
    keys(body,Set.of("target","operationId","expectedState","change"));
    keys(body.path("target"),Set.of("accountId","tenantId","origin"));
    var target=body.path("target");var origin=target.path("origin");
    keys(origin,Set.of("kind","workflowId","humanSubject","humanIssuer","humanClient","humanAuthority"));
    uuid(account);uuid(body.path("operationId").textValue());
    if(!account.equals(target.path("accountId").textValue())||!"SELF".equals(origin.path("kind").textValue())
        ||!account.equals(origin.path("humanSubject").textValue())||!origin.path("humanAuthority").isNull()
        ||!body.path("operationId").equals(origin.path("workflowId"))||!body.path("expectedState").isTextual()
        ||!body.path("expectedState").asText().matches("[0-9a-f]{64}")
        ||!body.path("change").isObject()||!body.path("change").isEmpty())throw invalid();
    text(origin.path("humanIssuer").textValue(),512);text(origin.path("humanClient").textValue(),255);
    var tenant=target.path("tenantId");
    if(!tenant.isNull()){
      if(!tenant.isTextual()||!tenant.asText().matches("0|[1-9][0-9]{0,18}"))throw invalid();
      try{if(Long.parseLong(tenant.asText())<0)throw invalid();}catch(NumberFormatException invalid){throw invalid();}
    }
    return body;
  }
  static void text(String value,int limit){
    if(value==null||value.isBlank()||value.length()>limit||value.chars().anyMatch(Character::isISOControl))throw invalid();
  }
  private static void keys(JsonNode value,Set<String> keys){
    if(value==null||!value.isObject())throw invalid();var names=new HashSet<String>();value.fieldNames().forEachRemaining(names::add);
    if(!names.equals(keys))throw invalid();
  }
  private static void uuid(String value){
    try{if(!UUID.fromString(value).toString().equals(value))throw invalid();}catch(RuntimeException malformed){throw invalid();}
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid fixed pre-deletion outcome binding");}
  private PreDeletionProofSchema(){}
}
