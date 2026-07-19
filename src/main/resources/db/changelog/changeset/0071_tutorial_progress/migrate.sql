CREATE TABLE userservice.tutorial_progress (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  surface VARCHAR(16) NOT NULL,
  tour_id VARCHAR(64) NOT NULL,
  tour_version INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  current_step_id VARCHAR(64) NULL,
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  create_date DATETIME NOT NULL,
  update_date DATETIME NOT NULL,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_tutorial_progress_scope UNIQUE (user_id, surface, tour_id, tour_version)
);

CREATE INDEX idx_tutorial_progress_user_surface ON userservice.tutorial_progress (user_id, surface);
CREATE INDEX idx_tutorial_progress_tour ON userservice.tutorial_progress (tour_id, tour_version, status);
