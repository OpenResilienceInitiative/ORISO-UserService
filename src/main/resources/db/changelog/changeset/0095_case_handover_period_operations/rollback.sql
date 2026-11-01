ALTER TABLE case_handover_request
  DROP FOREIGN KEY case_handover_request_initiator_fk,
  DROP INDEX uq_case_handover_initiator_operation,
  DROP INDEX idx_case_handover_session_period_status,
  DROP COLUMN recipient_decision_at,
  DROP COLUMN operation_id,
  DROP COLUMN expected_ownership_revision,
  DROP COLUMN direction,
  DROP COLUMN initiator_consultant_id;
