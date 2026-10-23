package de.caritas.cob.userservice.api.workflow.accountinactivity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed original-source custody on the existing inactivity row; it issues no work authority. */
final class InactivitySuspensionSource {
  record Change(String before, String after) {
    @Override
    public String toString() {
      return "Change[source-custody=redacted]";
    }
  }

  private final JdbcTemplate jdbc;
  private final InactivityClaimJournal journal;
  private final AccountInactivityIdentityTransport transport;

  InactivitySuspensionSource(
      JdbcTemplate jdbc,
      InactivityClaimJournal journal,
      AccountInactivityIdentityTransport transport) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.transport = Objects.requireNonNull(transport);
  }

  InactivityClaimJournal.Saved capture(InactivityClaimJournal.Saved claimed) {
    sql();
    if (!"CLAIMED".equals(claimed.phase())
        || !"SUSPEND".equals(journal.decode(claimed.claimed()).path("action").textValue())
        || claimed.suspensionSource() != null) throw invalid();
    var prefix = new TreeMap<String, Object>();
    prefix.put("representation", "CLAIMED_INACTIVITY_SUSPENSION_CUSTODY_V1");
    prefix.put("originalRow", journal.decode(claimed.claimed()));
    prefix.put("source", journal.decode(claimed.source()));
    prefix.put("nativeBody", null);
    prefix.put("nativeReceipt", null);
    prefix.put("phase", "CAPTURED");
    String sealed = transport.bindSuspensionCustody(journal.encode(prefix));
    if (sealed == null || sealed.isBlank()) throw invalid();
    transport.verifySuspensionCustody(sealed);
    if (jdbc.update(
            "update account_inactivity set suspension_source_json=? where identity_id=? and work_id=? and native_phase='CLAIMED' and suspension_source_json is null",
            sealed,
            claimed.id(),
            claimed.work())
        != 1) throw invalid();
    var saved = journal.saved(claimed.id(), true);
    requireBound(saved, saved);
    return saved;
  }

  Change advance(InactivityClaimJournal.Saved original, String phase, String body, String receipt) {
    var current = journal.saved(original.id(), false);
    requireBound(original, current);
    var sealed = (ObjectNode) journal.decode(current.suspensionSource());
    String previous = sealed.path("phase").textValue();
    if (!("FROZEN".equals(phase) && "CAPTURED".equals(previous)
        || Set.of("ACKNOWLEDGED", "NO_EFFECT").contains(phase) && "FROZEN".equals(previous)))
      throw invalid();
    sealed.remove("bindingDigest");
    sealed.put("phase", phase);
    sealed.set("nativeBody", journal.decode(body));
    if (receipt == null) sealed.putNull("nativeReceipt");
    else sealed.put("nativeReceipt", receipt);
    String next = transport.bindSuspensionCustody(journal.encode(sealed));
    if (next == null || next.isBlank()) throw invalid();
    transport.verifySuspensionCustody(next);
    return new Change(current.suspensionSource(), next);
  }

  void save(InactivityClaimJournal.Saved original, Change change) {
    sql();
    var current = journal.saved(original.id(), true);
    requireBound(original, current);
    if (!Objects.equals(change.before(), current.suspensionSource())) throw invalid();
    if (jdbc.update(
            "update account_inactivity set suspension_source_json=? where identity_id=? and work_id=? and suspension_source_json=? and native_phase=?",
            change.after(),
            original.id(),
            original.work(),
            change.before(),
            current.phase())
        != 1) throw invalid();
  }

  void requireBound(InactivityClaimJournal.Saved original, InactivityClaimJournal.Saved current) {
    if (current.suspensionSource() == null) throw invalid();
    transport.verifySuspensionCustody(current.suspensionSource());
    var sealed = journal.decode(current.suspensionSource());
    String phase = "CLAIMED".equals(current.phase()) ? "CAPTURED" : current.phase();
    if (!Objects.equals(phase, sealed.path("phase").textValue())
        || !journal.decode(original.claimed()).equals(sealed.path("originalRow"))
        || !journal.decode(original.source()).equals(sealed.path("source"))) throw invalid();
    if ("CAPTURED".equals(phase)) {
      if (current.body() != null || current.receipt() != null) throw invalid();
    } else if (current.body() == null
        || !journal.decode(current.body()).equals(sealed.path("nativeBody"))
        || !Objects.equals(
            current.receipt(),
            sealed.path("nativeReceipt").isNull()
                ? null
                : sealed.path("nativeReceipt").textValue())) throw invalid();
  }

  void requirePrior(InactivityClaimJournal.Saved current, String suspension) {
    if (current.suspensionSource() == null || suspension == null) throw invalid();
    transport.verifySuspensionCustody(current.suspensionSource());
    var sealed = journal.decode(current.suspensionSource());
    if (!"ACKNOWLEDGED".equals(sealed.path("phase").textValue())
        || !suspension.equals(sealed.path("originalRow").path("workId").textValue())
        || !current.id().equals(sealed.path("originalRow").path("identityId").textValue())
        || !journal.decode(current.source()).equals(sealed.path("source"))) throw invalid();
    var legacy =
        jdbc.queryForMap(
            "select suspension_body,suspension_receipt from account_inactivity where identity_id=? and work_id=?",
            current.id(),
            current.work());
    if (legacy.get("suspension_body") == null
        || legacy.get("suspension_receipt") == null
        || !journal.decode((String) legacy.get("suspension_body")).equals(sealed.path("nativeBody"))
        || !legacy.get("suspension_receipt").equals(sealed.path("nativeReceipt").textValue()))
      throw invalid();
  }

  private void sql() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw invalid();
  }

  private static IllegalStateException invalid() {
    return InactivityClaimJournal.invalid();
  }
}
