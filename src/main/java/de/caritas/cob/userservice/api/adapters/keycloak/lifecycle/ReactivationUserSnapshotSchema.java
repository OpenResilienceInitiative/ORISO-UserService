package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** Closed nonsecret data only. The actual saga independently proves and rechecks SQL ownership. */
final class ReactivationUserSnapshotSchema {
  private static final Set<String> FIELDS = Set.of(
      "user_id", "id_old", "username", "email", "matrix_user_id", "chat_recovery_mode", "chat_recovery_policy_revision", "language_formal",
      "delete_date", "deletion_lifecycle_state", "deletion_read_only_until", "deletion_paused_until", "deletion_pause_reason", "deletion_paused_by", "deletion_pause_created_at",
      "reactivation_operation_id", "reactivation_operation_started_at", "tenant_id", "create_date", "update_date", "encourage_2fa", "magic_link_login_enabled", "language_code",
      "terms_and_conditions_confirmation", "data_privacy_confirmation", "notifications_enabled", "notifications_settings", "temporary_account", "avatar_id");
  private ReactivationUserSnapshotSchema() {}

  static void require(JsonNode row, String account, Long tenant, String operation) {
    if (!row.isObject() || row.size() != FIELDS.size()) throw invalid();
    var actual=new HashSet<String>();row.fieldNames().forEachRemaining(actual::add);
    if (!FIELDS.equals(actual) || !account.equals(uuid(row,"user_id"))
        || !operation.equals(uuid(row,"reactivation_operation_id"))
        || tenant == null || tenant < 0 || !row.path("tenant_id").isIntegralNumber()
        || !row.path("tenant_id").canConvertToLong() || tenant.longValue() != row.path("tenant_id").longValue()
        || !"REACTIVATION_IN_PROGRESS".equals(text(row,"deletion_lifecycle_state",32,false))) throw invalid();
    nullableLong(row,"id_old",false);nullableLong(row,"chat_recovery_policy_revision",true);
    text(row,"username",255,false);text(row,"email",255,false);
    nullableText(row,"matrix_user_id",255);nullableText(row,"deletion_pause_reason",512);nullableText(row,"deletion_paused_by",64);
    nullableText(row,"notifications_settings",4000);nullableText(row,"avatar_id",40);
    if (!row.get("chat_recovery_mode").isNull()
        && !Set.of("LOGIN_PASSWORD","RECOVERY_KEY").contains(text(row,"chat_recovery_mode",32,false))) throw invalid();
    if (!text(row,"language_code",2,false).matches("[a-z]{2}")) throw invalid();
    for (String field:List.of("language_formal","notifications_enabled","temporary_account"))
      if (!row.path(field).isBoolean()) throw invalid();
    for (String field:List.of("encourage_2fa","magic_link_login_enabled"))
      if (!row.path(field).isNull() && !row.path(field).isBoolean()) throw invalid();
    for (String field:List.of("create_date","delete_date","reactivation_operation_started_at")) timestamp(row,field,false);
    for (String field:List.of("update_date","deletion_read_only_until","deletion_paused_until","deletion_pause_created_at","terms_and_conditions_confirmation","data_privacy_confirmation")) timestamp(row,field,true);
  }
  private static void timestamp(JsonNode row,String field,boolean nullable) {
    if (row.path(field).isNull() && nullable) return;
    String raw=text(row,field,64,false);
    try {var time=Instant.parse(raw);if (time.getEpochSecond()<=0 || time.getNano()%1000!=0 || !time.toString().equals(raw)) throw invalid();}
    catch (java.time.DateTimeException invalid) {throw invalid();}
  }
  private static void nullableLong(JsonNode row,String field,boolean nonnegative) {
    var value=row.path(field);if (value.isNull()) return;
    if (!value.isIntegralNumber() || !value.canConvertToLong() || nonnegative && value.longValue()<0) throw invalid();
  }
  private static void nullableText(JsonNode row,String field,int max) {if (!row.path(field).isNull()) text(row,field,max,true);}
  private static String uuid(JsonNode row,String field) {String id=text(row,field,36,false);try {if (!UUID.fromString(id).toString().equals(id)) throw invalid();return id;}catch (IllegalArgumentException invalid) {throw invalid();}}
  private static String text(JsonNode row,String field,int max,boolean empty) {
    var value=row.path(field);if (!value.isTextual() || value.textValue().length()>max || !empty && value.textValue().isBlank()
        || value.textValue().chars().anyMatch(c -> c<32 || c==127)) throw invalid();return value.textValue();
  }
  private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid private reactivation User snapshot");}
}
