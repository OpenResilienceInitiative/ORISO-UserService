CREATE TABLE IF NOT EXISTS tenant_case_handover_policy_cache (
  tenant_id BIGINT NOT NULL,
  policies LONGTEXT NOT NULL,
  refreshed_at DATETIME NOT NULL,
  stale_since DATETIME NULL,
  PRIMARY KEY (tenant_id)
);
