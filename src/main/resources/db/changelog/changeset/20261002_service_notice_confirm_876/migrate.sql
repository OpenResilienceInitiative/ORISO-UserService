-- Who confirmed a planned notice, and when. NULL while the campaign is still a draft.
ALTER TABLE service_notice_campaign
  ADD COLUMN confirmed_by_user_id VARCHAR(100) NULL,
  ADD COLUMN confirmed_at DATETIME(6) NULL;

-- One row per campaign and recipient, written once at confirmation. It is the send record:
-- the mail status says whether a mail is still due, was sent, or was deliberately not sent.
-- No address is stored here; the sender reads the current one at send time.
CREATE TABLE service_notice_recipient (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campaign_id BIGINT NOT NULL,
  recipient_id VARCHAR(36) COLLATE utf8_unicode_ci NOT NULL,
  tenant_id BIGINT NULL,
  mail_status VARCHAR(32) NOT NULL,
  correlation_id VARCHAR(36) NOT NULL,
  failure_count INT NOT NULL DEFAULT 0,
  next_attempt_at_utc DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  claimed_at DATETIME NULL,
  sent_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_snr_campaign_recipient (campaign_id, recipient_id),
  UNIQUE KEY uq_snr_correlation (correlation_id),
  KEY idx_snr_due (mail_status, next_attempt_at_utc),
  CONSTRAINT fk_snr_campaign FOREIGN KEY (campaign_id) REFERENCES service_notice_campaign (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
