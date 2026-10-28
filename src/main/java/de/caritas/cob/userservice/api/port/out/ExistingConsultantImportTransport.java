package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.service.identity.ExistingConsultantImportMaintenance.CheckedImport;

/** Fixed configured-import operations; only the live owning importer can supply the scope. */
public interface ExistingConsultantImportTransport {
  /** Fixed nonsecret integrity data; neither operation mints dispatch authority. */
  String sourceKey(
      de.caritas.cob.userservice.api.service.ConsultantImportService.ScopedExistingImport source);

  record OriginalBinding(String rowDigest, String requestDigest) {}

  OriginalBinding originalBinding(
      de.caritas.cob.userservice.api.service.ConsultantImportService.ScopedExistingImport source,
      String originalJson);

  HumanAdminMaintenanceTransport.Projection read(CheckedImport source);

  HumanAdminMaintenanceTransport.FrozenProfile freeze(
      CheckedImport source, String expectedState, String group);

  HumanAdminMaintenanceTransport.ProfileOutcome execute(
      CheckedImport source, HumanAdminMaintenanceTransport.FrozenProfile frozen);

  String verifyReceipt(
      CheckedImport source, HumanAdminMaintenanceTransport.FrozenProfile frozen, String receipt);

  HumanAdminMaintenanceTransport.ProfileNoEffect verifyNoEffect(
      CheckedImport source, HumanAdminMaintenanceTransport.FrozenProfile frozen, String receipt);

  void verifyStoredNoEffect(
      CheckedImport source,
      String operation,
      String digest,
      HumanAdminMaintenanceTransport.FrozenProfile frozen,
      String receipt);
}
