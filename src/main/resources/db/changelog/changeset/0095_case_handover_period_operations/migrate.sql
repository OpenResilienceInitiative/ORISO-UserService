ALTER TABLE case_handover_request
  ADD COLUMN direction VARCHAR(8) NOT NULL DEFAULT 'PULL' AFTER previous_consultant_id,
  ADD COLUMN initiator_consultant_id VARCHAR(36) NULL AFTER requester_consultant_id,
  ADD COLUMN recipient_decision_at DATETIME NULL AFTER resolved_at,
  ADD COLUMN expected_ownership_revision BIGINT NULL AFTER direction,
  ADD COLUMN operation_id CHAR(36) NULL AFTER expected_ownership_revision;

UPDATE case_handover_request
SET initiator_consultant_id = requester_consultant_id
WHERE initiator_consultant_id IS NULL;

ALTER TABLE case_handover_request
  MODIFY COLUMN initiator_consultant_id VARCHAR(36) NOT NULL,
  ADD CONSTRAINT case_handover_request_initiator_fk
    FOREIGN KEY (initiator_consultant_id) REFERENCES consultant (consultant_id) ON UPDATE CASCADE,
  ADD UNIQUE INDEX uq_case_handover_initiator_operation
    (initiator_consultant_id, operation_id),
  ADD INDEX idx_case_handover_session_period_status
    (session_id, expected_ownership_revision, status);
