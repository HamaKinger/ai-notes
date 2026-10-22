-- 初始演示数据:一个默认工作区 + 一篇欢迎笔记
INSERT INTO workspace (id, name, storage_mode)
VALUES ('00000000-0000-0000-0000-000000000001', '我的知识库', 'db');

INSERT INTO note (id, workspace_id, title, slug, content_md, content_hash, index_status)
VALUES (
    '00000000-0000-0000-0000-000000000011',
    '00000000-0000-0000-0000-000000000001',
    '欢迎使用 ai-notes',
    'welcome',
    '# 欢迎使用 ai-notes\n\n这是一个以本地知识库为长期记忆的 AI Agent。\n\n- 左侧:目录树 / 搜索\n- 中间:Markdown 编辑器\n- 右侧:反链 / AI 侧栏\n\n试试用 `[[双链]]` 和 `#标签`。\n',
    'seed',
    'pending'
);
