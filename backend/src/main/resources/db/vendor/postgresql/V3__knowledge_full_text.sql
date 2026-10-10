CREATE INDEX knowledge_article_search ON knowledge_article
USING GIN (to_tsvector('english', title || ' ' || content));
