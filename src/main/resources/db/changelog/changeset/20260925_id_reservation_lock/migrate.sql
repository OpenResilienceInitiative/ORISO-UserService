-- One row per reserved Träger or Beratungsstelle number. Revokes and expiries lock it, so the
-- decision "no pending invite needs this number any more" is taken once (ORISO-Admin#1026).
CREATE TABLE IF NOT EXISTS id_reservation_lock (
  allocation_type VARCHAR(16) NOT NULL,
  reserved_id BIGINT NOT NULL,
  PRIMARY KEY (allocation_type, reserved_id)
);
