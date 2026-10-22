-- Private applied enrollment identity; always updated under the actual owning User row lock.
CREATE TABLE identity_self_otp_local_enrollment (
  account_id VARCHAR(36) NOT NULL,
  operation_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (account_id)
);
