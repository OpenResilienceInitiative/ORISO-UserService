CREATE TABLE IF NOT EXISTS consultant_message_stat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  consultant_hmac VARCHAR(64) NOT NULL,
  tenant_id BIGINT NULL,
  agency_id BIGINT NULL,
  source_session_id BIGINT NULL,
  sent_date DATETIME NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_consultant_message_stat_hmac_tenant_date (consultant_hmac, tenant_id, sent_date)
);
