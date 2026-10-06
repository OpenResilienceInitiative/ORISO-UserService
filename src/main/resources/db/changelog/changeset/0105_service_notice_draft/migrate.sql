CREATE TABLE IF NOT EXISTS service_notice_campaign (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  campaign_key VARCHAR(80) NOT NULL,
  status VARCHAR(16) NOT NULL,
  maintenance_date DATE NOT NULL,
  maintenance_start TIME NOT NULL,
  maintenance_end TIME NOT NULL,
  status_url VARCHAR(2048) NOT NULL,
  created_by_user_id VARCHAR(100) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT uq_service_notice_campaign_key UNIQUE (campaign_key)
);
