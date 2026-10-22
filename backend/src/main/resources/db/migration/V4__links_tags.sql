-- ============================================================
-- M2:双链与标签
-- ============================================================

-- 标签:同一工作区内名称唯一(大小写不敏感)
CREATE UNIQUE INDEX ux_tag_workspace_name ON tag (workspace_id, lower(name));
CREATE INDEX ix_tag_workspace ON tag (workspace_id);

-- 笔记 <-> 标签:按标签反查笔记
CREATE INDEX ix_note_tag_tag ON note_tag (tag_id);

-- 双链:同一篇笔记里指向同一个标题只保留一条(带首次出现的上下文)
CREATE UNIQUE INDEX ux_link_source_target ON link (source_note_id, lower(target_title));
CREATE INDEX ix_link_workspace ON link (workspace_id);
