-- Each local presenter has isolated workspaces. Row locks serialize commands and
-- commit domain state, action ledger and audit events together.
CREATE TABLE demo_workspace (
  id VARCHAR(160) PRIMARY KEY,
  document TEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE demo_session (
  token VARCHAR(100) PRIMARY KEY,
  presenter_id VARCHAR(100) NOT NULL,
  workspace_id VARCHAR(160),
  principal_id VARCHAR(100),
  role VARCHAR(20) NOT NULL,
  generation VARCHAR(100),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
