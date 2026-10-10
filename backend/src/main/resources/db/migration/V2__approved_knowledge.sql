CREATE TABLE knowledge_article (
  workspace_id VARCHAR(160) NOT NULL,
  article_id VARCHAR(100) NOT NULL,
  version INTEGER NOT NULL,
  title VARCHAR(300) NOT NULL,
  content TEXT NOT NULL,
  PRIMARY KEY (workspace_id, article_id)
);
