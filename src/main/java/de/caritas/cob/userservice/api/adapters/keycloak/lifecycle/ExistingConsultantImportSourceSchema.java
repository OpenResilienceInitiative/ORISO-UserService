package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Closed nonsecret data schema. Only the private importer owner can authorize dispatch. */
final class ExistingConsultantImportSourceSchema {
  private static final Set<String> ROLES=Set.of("consultant","group-chat-consultant","restricted-agency-admin","user-admin");
  private static final Set<String> TABLES=Set.of("admin","consultant","language","consultant_topic","consultant_agency","admin_agency");
  private ExistingConsultantImportSourceSchema() {}

  static JsonNode source(JsonNode value) {
    fields(value,Set.of("sourceDigest","recordNumber","accountId","tenantId"));
    tuple(value);return value;
  }

  static JsonNode row(JsonNode value) {
    fields(value,Set.of("v","kind","sourceDigest","recordNumber","workId","accountId","tenantId","createdAtMicros","record","agencies","originalSql","initiator"));
    if(number(value,"v",1,1)!=1 || !"EXISTING_CONSULTANT_IMPORT".equals(text(value,"kind",32,false))) throw invalid();
    tuple(value);uuid(value,"workId");long created=number(value,"createdAtMicros",1,Long.MAX_VALUE);
    long tenant=decimal(value,"tenantId");String account=uuid(value,"accountId");
    JsonNode record=value.path("record");
    fields(record,Set.of("tenantId","username","email","firstName","lastName","consultantId","oldId","usernameEncoded","agenciesAndRoleSets","roles","absent","absenceMessage","formalLanguage","teamConsultant"));
    if(number(record,"tenantId",1,Long.MAX_VALUE)!=tenant || !account.equals(uuid(record,"consultantId"))) throw invalid();
    text(record,"username",255,false);text(record,"email",320,false);
    text(record,"usernameEncoded",2048,false);text(record,"agenciesAndRoleSets",65536,false);
    for(String field:List.of("firstName","lastName")) nullableText(record,field,255);
    nullableText(record,"absenceMessage",65536);
    var old=record.get("oldId");if(!old.isNull() && (!old.isIntegralNumber() || !old.canConvertToLong())) throw invalid();
    for(String field:List.of("absent","formalLanguage","teamConsultant")) if(!record.path(field).isBoolean()) throw invalid();
    var required=roles(record.path("roles"));if(!required.contains("consultant")) throw invalid();
    JsonNode agencies=value.path("agencies");if(!agencies.isArray() || agencies.isEmpty() || agencies.size()>100) throw invalid();
    long previous=0;var union=new TreeSet<String>();
    for(var agency:agencies) {
      fields(agency,Set.of("agencyId","requiredRoles"));long id=decimal(agency,"agencyId");if(id<=previous) throw invalid();previous=id;
      union.addAll(roles(agency.path("requiredRoles")));
    }
    if(!required.equals(List.copyOf(union))) throw invalid();
    originalSql(value.path("originalSql"),account,tenant,created);
    initiator(value.path("initiator"));return value;
  }

  private static void tuple(JsonNode value) {
    if(!text(value,"sourceDigest",64,false).matches("[a-f0-9]{64}")) throw invalid();
    number(value,"recordNumber",1,Long.MAX_VALUE);uuid(value,"accountId");decimal(value,"tenantId");
  }

  private static void originalSql(JsonNode value,String account,long tenant,long created) {
    fields(value,TABLES);
    var parser=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    for(String table:TABLES) {
      var rows=value.path(table);if(!rows.isArray()) throw invalid();
      if(table.equals("consultant") && rows.size()!=1 || table.equals("admin") && rows.size()>1) throw invalid();
      String previous=null;
      for(var encoded:rows) {
        if(!encoded.isTextual() || encoded.textValue().isBlank() || encoded.textValue().length()>1048576) throw invalid();
        String raw=encoded.textValue();if(previous!=null && previous.compareTo(raw)>0) throw invalid();previous=raw;
        JsonNode row;
        try {row=parser.readTree(raw);} catch(java.io.IOException invalid) {throw invalid();}
        if(!row.isObject()) throw invalid();
        String key=table.startsWith("admin")?"admin_id":"consultant_id";
        if(!account.equals(uuid(row,key))) throw invalid();
        if(table.equals("consultant") || table.equals("admin")) {
          if(number(row,"tenant_id",1,Long.MAX_VALUE)!=tenant) throw invalid();
          if(table.equals("consultant")) {
            try {
              var time=Instant.parse(text(row,"create_date",128,false));
              long micros=Math.addExact(Math.multiplyExact(time.getEpochSecond(),1000000L),time.getNano()/1000);
              if(time.getNano()%1000!=0 || micros!=created) throw invalid();
            } catch(java.time.DateTimeException | ArithmeticException invalid) {throw invalid();}
          }
        }
      }
    }
  }

  private static void initiator(JsonNode value) {
    if("IMPORT".equals(value.path("kind").textValue())) {fields(value,Set.of("kind"));return;}
    fields(value,Set.of("kind","subject","issuer","client"));
    if(!"HUMAN_ADMIN".equals(value.path("kind").textValue())) throw invalid();
    uuid(value,"subject");String raw=text(value,"issuer",2048,false);URI issuer;
    try {issuer=URI.create(raw);} catch(IllegalArgumentException invalid) {throw invalid();}
    if(!Set.of("https","http").contains(issuer.getScheme()) || issuer.getHost()==null || issuer.getRawUserInfo()!=null || issuer.getRawQuery()!=null || issuer.getRawFragment()!=null) throw invalid();
    if(!text(value,"client",255,false).matches("[A-Za-z0-9._:-]{1,255}")) throw invalid();
  }

  private static List<String> roles(JsonNode value) {
    if(!value.isArray() || value.isEmpty() || value.size()>4) throw invalid();
    var result=new ArrayList<String>();String previous=null;
    for(var role:value) {
      if(!role.isTextual() || !ROLES.contains(role.textValue()) || previous!=null && previous.compareTo(role.textValue())>=0) throw invalid();
      previous=role.textValue();result.add(previous);
    }
    return List.copyOf(result);
  }
  private static long decimal(JsonNode value,String field) {
    String raw=text(value,field,19,false);if(!raw.matches("[1-9][0-9]{0,18}")) throw invalid();
    try {long id=Long.parseLong(raw);if(id<1) throw invalid();return id;} catch(NumberFormatException invalid) {throw invalid();}
  }
  private static long number(JsonNode value,String field,long min,long max) {
    var n=value.path(field);if(!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<min || n.longValue()>max) throw invalid();return n.longValue();
  }
  private static String uuid(JsonNode value,String field) {
    String id=text(value,field,36,false);try {if(!UUID.fromString(id).toString().equals(id)) throw invalid();return id;} catch(IllegalArgumentException invalid) {throw invalid();}
  }
  private static String text(JsonNode value,String field,int max,boolean empty) {
    var n=value.path(field);if(!n.isTextual() || n.textValue().length()>max || !empty && n.textValue().isBlank()) throw invalid();return n.textValue();
  }
  private static void nullableText(JsonNode value,String field,int max) {if(!value.path(field).isNull()) text(value,field,max,true);}
  private static void fields(JsonNode value,Set<String> expected) {
    if(value==null || !value.isObject() || value.size()!=expected.size()) throw invalid();var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected)) throw invalid();
  }
  private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid existing consultant import source");}
}
