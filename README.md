# ai-notes

以本地知识库为长期记忆的 **AI Agent**:笔记是它的记忆载体,检索是它的回忆能力,对话与工具调用是它的行动能力。

> 详细设计见 [`docs/DESIGN.md`](docs/DESIGN.md)(v0.11:PostgreSQL + Elasticsearch 双引擎,M3 语义检索 +
> M4 对话/RAG + 文本工具已落地;右栏是能基于笔记流式作答的 AI 面板,答案里的 `[n]` 可点回原文;
> 编辑器里选中一段文字就能润色 / 翻译 / 续写 / 总结 / 解释,结果要你点「替换选中」才写回笔记,
> 还能一键生成摘要与推荐标签;
> 界面为玻璃风格,强调色与背景色都可自定义;全文检索在顶栏、左栏只做标题过滤、结果按笔记归并,
> 点进去直接定位到命中位置;预览支持 GFM 表格,`[[双链]]` 在预览里可点击、悬停能预览目标笔记;
> 编辑器/预览区可用 Ctrl+F 就地查找。见 §9.2 / §9.5 / §13.1 / §13.5 / §13.7 / §13.9 / §13.10 / §13.11)。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Next.js 16 + React 19 + TypeScript + Tailwind CSS v4 |
| 后端 | Java 21 + Spring Boot 4.1 + Spring AI 2.0 |
| 事实数据 | PostgreSQL 16 |
| 检索索引 | Elasticsearch(全文 + 向量 + 混合;客户端 pin 9.x,实测在 8.16 上亦可运行) |
| 缓存 / 会话记忆 | Redis 7 |
| 模型 | OpenAI 兼容协议(阿里百炼 / DeepSeek / OpenAI / vLLM / Ollama 本地) |

## 环境要求

- JDK 21+
- Maven 3.9+
- Node.js 20+(本项目用 24)
- Docker + Docker Compose(用于一键起 PostgreSQL / Elasticsearch / Redis)

> ⚠️ Windows 上如果 `npm` 被 PowerShell 执行策略拦住,请改用 `npm.cmd`,或执行
> `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`。

---

## 快速开始

### 1. 启动基础设施

```bash
cd deploy
docker compose up -d postgres elasticsearch redis
```

> Linux 上 Elasticsearch 需要宿主机 `vm.max_map_count >= 262144`:
> `sudo sysctl -w vm.max_map_count=262144`
>
> 中文检索依赖 **IK 分词插件(`analysis-ik`),官方镜像并不自带**。compose 里的官方镜像
> 启动后需要手动装一次(或用 §11.1 的自建镜像):
> ```bash
> docker exec -it ai-notes-elasticsearch bin/elasticsearch-plugin install --batch \
>   https://get.infini.cloud/elasticsearch/analysis-ik/<ES 版本>
> docker restart ai-notes-elasticsearch
> ```
> **没装也能跑**:后端会回退到内置 `standard` 分词器并在启动日志里打 WARN,
> 中文按单字切分,召回尚可但精度下降。装上之后删掉索引重启即可自动切回。
>
> Elasticsearch 单机开发至少预留 1~2GB 内存。**只做笔记 CRUD 时其实只需要 PostgreSQL**,
> 可以只起 `docker compose up -d postgres`;索引初始化失败只会告警,不会让后端启动失败。

### 2. 启动后端

```bash
cd backend
export AI_CHAT_API_KEY=<你的端点密钥>   # PowerShell: $env:AI_CHAT_API_KEY="..."
mvn spring-boot:run
```

Flyway 会自动建表并写入演示数据(一个默认工作区 + 一篇欢迎笔记),
并在启动时创建 Elasticsearch 索引(Index mapping 见
`backend/src/main/resources/elasticsearch/chunk-index.json`)。

> **M3 起嵌入模型是必需的**:没有可用的 `AI_CHAT_API_KEY`,笔记仍能正常读写、
> 关键词检索也照常工作(索引期嵌入失败会写入不带向量的关键词索引并打 WARN),
> 但语义检索这一路会一直缺席。配好密钥后调一次
> `POST /api/v1/workspaces/{ws}/reindex` 就能把存量笔记的向量补齐。

- API 基址:<http://localhost:8080/api/v1>
- 健康检查:<http://localhost:8080/actuator/health>

冒烟测试:

```bash
# 1) 登录拿 Token(演示账号在首次启动时自动创建)
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@ai-notes.local","password":"demo1234"}'

# 2) 带 Token 调用(默认已强制鉴权,不带 Token 会 401)
curl http://localhost:8080/api/v1/workspaces -H "Authorization: Bearer <token>"

# 3) 混合检索(BM25 + 向量,应用层 RRF 融合)
curl -X POST "http://localhost:8080/api/v1/workspaces/<workspaceId>/search" \
  -H "Content-Type: application/json" \
  -d '{"q":"检索","topK":10}'

# 4) 重建整个工作区的索引(补向量 / 修失败笔记)
curl -X POST "http://localhost:8080/api/v1/workspaces/<workspaceId>/reindex"

# 5) AI 对话:先建会话,再流式提问(SSE;-N 关掉 curl 的输出缓冲)
#    建会话时标题可以不给,第一句提问会自动成为标题
CONV=$(curl -s -X POST "http://localhost:8080/api/v1/workspaces/<workspaceId>/conversations" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{}' \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
curl -N -X POST "http://localhost:8080/api/v1/conversations/$CONV/chat" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"q":"混合检索是怎么融合的?","mode":"rag"}'
#    mode 省略即 rag(先检索笔记、答案带 [n] 引用);mode=chat 是纯聊天,不碰知识库

# 6) 文本工具:流式润色一段选中文本(SSE);action 也可写中文(润色 / 翻译 / 续写 / 总结 / 解释)
curl -N -X POST "http://localhost:8080/api/v1/ai/inline" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"text":"这个功能我觉得吧,就是有点那个,建议改一下。","action":"polish"}'
#    meta → token* → done(结果**不落库**:要不要替换回笔记由用户在界面上点一下)
#    action=translate 时 to 可省略 —— 缺省按原文语言反着来(中文 → 英文,其它 → 中文)

# 7) 摘要与标签:一次性返回,同样不落库(保存由前端 PATCH 笔记完成)
curl -X POST "http://localhost:8080/api/v1/ai/summarize" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"title":"检索设计","content":"<正文>"}'
curl -X POST "http://localhost:8080/api/v1/ai/suggest-tags" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"title":"检索设计","content":"<正文>","existing":["检索"]}'
```

> 默认 `AI_NOTES_DEV_PERMIT_ALL=false`:**服务端强制鉴权**,匿名请求一律 401(前端点一下就登录,
> 演示账号已预填)。本地调试若想省掉"先换 Token"这一步,显式置 `true`:不带 Token 也放行,
> 并回落到 `AI_NOTES_DEMO_EMAIL` 指定的演示账号;此时**带上合法 Token 依然会解析出真实用户**。

### 3. 启动前端

```bash
cd frontend
copy .env.local.example .env.local   # Windows(PowerShell 用 Copy-Item)
npm install
npm run dev
```

打开 <http://localhost:3000>。

### 4.(可选)完全本地模型

```bash
docker compose --profile local-models up -d ollama
docker exec -it ai-notes-ollama ollama pull qwen2.5:7b
docker exec -it ai-notes-ollama ollama pull bge-m3
```

然后把端点指回本地(对话与嵌入都走它,`bge-m3` 恰好也是 1024 维,索引不用改):

```bash
export AI_CHAT_BASE_URL=http://localhost:11434/v1
export AI_CHAT_API_KEY=ollama          # Ollama 不校验,填任意非空值
export AI_CHAT_MODEL=qwen2.5:7b
export AI_EMBEDDING_MODEL=bge-m3
```

---

## 目录结构

```
ai-notes/
├─ docs/DESIGN.md          详细设计文档
├─ backend/                Spring Boot 4.1 + Spring AI 2.0
│  └─ src/main/
│     ├─ java/com/ainotes/
│     │  ├─ config/        安全(JWT)、CORS、ES 客户端、Spring AI 装配
│     │  ├─ common/        异常处理、分页、工具
│     │  ├─ auth/          用户、JWT 签发、注册登录、演示数据
│     │  ├─ workspace/     工作区 + 成员关系
│     │  ├─ note/          笔记 CRUD + 版本历史/回滚
│     │  ├─ graph/         双链与标签(CommonMark AST 解析、反链、图谱)
│     │  ├─ search/        SearchService(检索端口:BM25 + kNN,应用层 RRF)、RrfFuser
│     │  ├─ ingest/        Markdown 分块、ChunkEmbeddingService、ES 索引读写、重建索引接口、索引事件
│     │  └─ attachment/    附件(本地目录存储)
│     └─ resources/
│        ├─ application.yml
│        ├─ elasticsearch/chunk-index.json   索引 mapping(IK + HNSW)
│        └─ db/migration/  Flyway 迁移
├─ frontend/               Next.js 16 前端
│  ├─ src/
│  │  ├─ app/              路由(App Router:工作区列表 / 登录 / 三栏工作区)
│  │  │  └─ globals.css    设计系统:玻璃 / 光影 / 流动感组件类 + 主题预设(见 §9.5)
│  │  ├─ components/       NoteList / MarkdownEditor / RelationsPanel / GraphView / VersionHistory
│  │  │                    / AttachmentStrip / ThemeProvider / ThemeMenu / RequireAuth
│  │  └─ lib/              API 客户端、Token 存储、主题引擎(theme.ts)、状态文案(labels.ts)、类型
│  └─ .env.local.example   前端唯一的环境变量示例(复制成 .env.local)
└─ deploy/                 docker-compose 与 ES 索引 mapping 的运维副本
```

---

## 当前进度(M5 工具调用:Agent 读写笔记 + 写操作确认)

已完成:

**M0 骨架**

- 后端工程可编译运行;Flyway 管理完整表结构(与设计文档 §5.2 一致)
- 工作区、笔记的 CRUD REST API(含分页、软删除、内容指纹)
- 笔记版本历史落库(每次内容变更写一条快照)
- 统一错误响应、CORS、Actuator 健康检查
- Spring AI 依赖与 `ChatClient` 装配就绪(尚未调用)
- 前端:工作区列表页 + 三栏工作区页

**M1 全文检索**

- **登录鉴权**:用户表 + BCrypt + HS256 JWT(`/auth/register|login|me`),
  `SecurityConfig` 始终解析 Bearer Token;`AI_NOTES_DEV_PERMIT_ALL` 只控制
  「没带 Token 时是否放行」,**默认 `false`(服务端强制鉴权,匿名请求 401)**,
  置 `true` 才放行并回落到演示账号(仅供本地调试)
- **Elasticsearch 索引**:后端启动时按 `resources/elasticsearch/chunk-index.json`
  自动建索引(IK 中文分词 + 1024 维 HNSW 向量字段已就位);**ES 镜像没装 IK 时
  自动回退到内置 `standard` 分词器并打 WARN**,不会因此起不来
- **索引同步**:笔记保存/删除后抛事务事件,提交后异步重建该笔记的 chunk
  (Markdown 按标题树 + 段落分块),失败只把 `index_status` 标为 `failed`,
  不影响笔记本身;每次同步落一条 `index_job` 记录
- **全文检索 API**:`POST /workspaces/{id}/search`(BM25,中文分词优先 IK、缺失时回退
  `standard`,带高亮片段)、`GET /workspaces/{id}/search/suggest`(标题联想)
- **检索 UI**:全文检索在**顶栏** —— 输入即搜(防抖),结果以玻璃浮层挂在输入框下方
  (笔记标题 + 章节路径 + 高亮片段,↑↓ 选择 / Enter 打开 / Esc 关闭);左栏只做**标题过滤** +
  标签筛选。两种检索各用各的输入框,不再共用一个「标题 / 全文」开关
- **命中按笔记归并**:检索返回的命中单位是**分块**,一篇长笔记本来就能刷满整屏(看着像重复
  索引)。现在一篇一行 + 「N 处」角标,行内先给分最高的那条,`←/→` 或角标展开看全部,
  页脚显示「X 篇笔记 · Y 处命中」(原因见 `docs/DESIGN.md` §13.6 第 1 点)
- **版本回滚**:`GET /notes/{id}/versions`、`GET /notes/{id}/versions/{n}`、
  `POST /notes/{id}/versions/{n}/restore`(回滚以追加新版本的方式实现)
- **附件**:上传/下载/列表(`/notes/{id}/attachments`),本地目录存储

**M2 双链与标签**

- **解析**:用 CommonMark 官方实现 `org.commonmark:commonmark` 解析成 AST,
  只在 `Text` 节点上抽取 `[[链接]]` / `#标签` —— 代码块、行内代码、原始 HTML 整棵子树跳过,
  所以文档里写的 `[[示例]]` 不会被当成真链接;ATX 标题也不会被当成标签
- **支持写法**:`[[目标]]`、`[[目标|别名]]`、`[[目标#小节]]`、`[[目录/目标]]`;
  `![[嵌入]]` 不计入双链
- **关系存储**:`link` / `tag` / `note_tag` 三张表;保存笔记后**事务提交再异步重建**,
  与索引同步同一套约定(失败不影响笔记本身)
- **改名与新建自动接上**:`link.target_note_id` 只是缓存,事实是 `target_title` 文本 ——
  笔记改名时指向旧标题的链接自动变成「未创建」,而先有链接后建笔记也能自动挂接
- **关系面板**:当前笔记的标签 / 出链 / 反链(反链带上下文片段),
  未创建的目标可一键建笔记
- **预览里也能点**:`[[目标]]` 在预览里是可点的链接(悬停还能看目标笔记的摘要/开头),
  未创建的目标点一下就地建出来 —— 不用回左栏或关系面板找。见下面「预览里的 `[[双链]]`」
- **标签聚合**:`GET /workspaces/{ws}/tags` 返回标签与使用次数,左栏可点选过滤笔记
- **知识图谱**:`react-force-graph-2d` 力导向图,浮层展示,点击节点直接切换笔记
- **`[[` 联想**:编辑器输入 `[[` 时用标题联想接口补全(复用 M1 的 suggest 接口)
- **分栏滚动同步**:编辑与预览按**滚动比例**互相跟随(左边源码、右边渲染结果,两边高度天然
  不同,只有"滚到百分之几"可比)。防回声用**无状态**判断 —— 目标栏若已在对应比例上就不再回写,
  循环自然终止;刻意不用 `requestAnimationFrame` 解"锁",因为后台 / 被遮挡的窗口里 rAF 不跑,
  锁会永久合上、同步静默失效(实测踩到过)
- **默认视图**:打开**已有的**笔记落「预览」(先读),**新建**的笔记落「分栏」(边写边看)。
  换笔记时按 `defaultMode` **复位**视图(渲染期调整 state,不重挂载编辑器,查找到一半切换
  也不会被打断),所以手工切过的视图不会带到下一篇 —— 否则"默认视图"只在第一次进工作区时生效
- **删除笔记**:列表里**鼠标悬停**某一项,右上角才浮出 ✕;点一下变成二次确认(删除 / 取消)。
  后端 `DELETE /notes/{id}` 是**软删除** —— 界面与检索里立刻消失(`deleted_at` 之后所有查询都
  过滤掉它)、ES 里它的 chunk 同步删掉、它自己的出链与标签清掉、**指向它的链接解绑回「未创建」**;
  但笔记与版本历史仍留在 PostgreSQL,而且**界面里没有回收站**,想找回只能把 `deleted_at` 置回 NULL

**M3 语义检索**

- **分块**沿用 M1 的 `MarkdownChunker`(标题树 + 段落 + 超长硬切),不动索引结构
- **嵌入**:直接注入 Spring AI 的 `EmbeddingModel` 算向量,自己写进 ES 的
  `embedding` 字段 —— 刻意**没有**用 Spring AI 的 `ElasticsearchVectorStore`
  (它的自动配置会要求索引必须存在、并要求用裸 mapping 覆盖我们的 IK 配置,
  见 `application.yml` 里 `autoconfigure.exclude` 的说明),所以索引始终只有一份、
  mapping 始终只有一个来源
- **送模型的文本做了增强**:`标题 + 标题路径 + 正文`。片段被切短后"它属于哪篇的哪一节"
  只存在于标题里,补上它明显改善短片段与查询的语义匹配;存进 ES 的 `content` 仍是纯正文
- **混合检索**:关键词分支(BM25)+ 向量分支(kNN,走 mapping 里的 HNSW)分别召回,
  再在**应用层**做 RRF 融合。不用 ES 原生 `rrf` retriever 的原因是许可:
  该功能属于 platinum,实测在 basic 许可的集群上直接返回
  `current license is non-compliant for [Reciprocal Rank Fusion (RRF)]`
- **维度校验**:`dense_vector` 的维度在 mapping 里写死,模型换了维度对不上时不会静默失败,
  而是抛出带修复指引的错误(`ChunkEmbeddingService`)
- **降级是分层的、但绝不静默**:
  - 查询期嵌入不可用 → 只走关键词,并打 WARN(用户最差也只是"纯关键词检索")
  - 索引期嵌入失败 → **关键词索引照写**,只是文档没有 `embedding` 字段,
    `index_status` 置 `failed` 并记下原因 —— 搜索不该因为一个外部 AI 接口挂掉就整体失效
- **重建索引接口**:`POST /workspaces/{ws}/reindex`,逐个笔记独立事务,
  返回 `ready / keywordOnly / failed` 三类计数(删索引、换嵌入模型、修复失败笔记都用它)

**M4 AI 对话 + RAG(Step 1)**

- **右栏就是对话**:提问按 token 流出来,回答按 Markdown 渲染;每段依据在正文里是 `[1]`、`[2]`
  这样的**可点编号**,气泡下面还有一排「笔记 › 章节」引用。点一下打开那篇笔记并跳到引用所在段落
  —— 复用全文检索那套定位,所以会自动切到预览 + 短暂高亮
- **知识库 / 纯聊天**:标题栏的「知识库」开关。开着(RAG)先检索 `AI_NOTES_RAG_TOP_K`(默认 6)
  段笔记片段再回答,答案带引用;关掉就是普通聊天,系统提示词会明确告诉模型"你看不到用户的笔记",
  免得它假装读过某篇
- **会话**:按最近活动倒序、首句提问自动成标题、可切换/删除;历史与引用都存在 PostgreSQL
  (`message` 是完整日志,`citation` 存**快照**:标题 + 章节 + 片段,笔记之后改名/删掉也不影响历史展示)。
  **不预先建空会话** —— 点「＋」只是清空面板,问出第一句才落库
- **答到一半断了也留半截**:助手消息先落一行占位(内容为空),流式结束再回填;模型失败时已生成的
  部分照样保存,气泡里补一行错误原因。点「停止」= 主动放弃这一条(不会落库,刷新后只剩提问)
- **不用 `MessageChatMemoryAdvisor`**:它按"一组消息"整体覆盖写入,会把滑出窗口的历史删掉,
  与"这张表是完整日志"冲突;改成按 `AI_NOTES_AI_HISTORY_WINDOW`(默认 20 条)自己取窗口喂模型
- **Qwen3 的思考模式已关**:思考内容走 `reasoning_content`,而 Spring AI 只认 `content`,
  开着就是十几秒白等(同一句话实测 **29s → 1.9s**)。参数必须是**布尔字面量**写进
  `spring.ai.openai.chat.options.extra-body`(写成 `${VAR:false}` 会变成字符串 `"false"`,
  硅基流动直接 400 `code 20015`)。细节与踩坑见 DESIGN §13.10
- 已知取舍:没有 rerank;超长会话到窗口上限后老消息不再进上下文(界面上仍照常显示)

**M4 AI 文本工具(Step 2)**

- **选中就做**:编辑器里选中一段文字,工具条上出现 润色 / 翻译 / 续写 / 总结 / 解释(没选中时是一句
  提示,不是五个灰按钮)。结果在编辑器底部的面板里**流式**逐字出现,可以中途「停止」——
  已经生成的部分照样留着
- **写回是用户点的**:面板上给「替换选中」/「插到选区后」/「复制」,续写只有「接在选区后」,
  解释**不给写回按钮**(它是给人看的,替换回去等于把笔记改成问答记录)。这条规则由后端在
  `meta.writesBack` 里下发,前端不自己维护一份 —— 否则加一个动作就要改两个地方
- **翻译方向**:没指定就按原文语言反着来(中文 → 英文,其它 → 中文),按钮悬停会写明
  「翻译成英文」。目标语言、任务说明都在 `TextAction` 里,提示词的通用规则另有一条
  「只输出处理结果本身」—— 结果会被直接写回正文,模型多说一句"以下是…"就会跟着写进笔记
- **不落库、不建会话**:三个端点(`/ai/inline` SSE、`/ai/summarize`、`/ai/suggest-tags`)都不写数据库;
  替换 / 插入 / 写标签走的都是 `onContentChange` —— 和手打**同一条保存链路**(800ms 自动保存、
  版本快照、索引重建)。所以"AI 出错"最坏只是这次白等,不会悄悄改掉笔记
- **摘要要你点一下才存**:生成的是**一句话**(≤60 字,压成单行、去掉引号),点「保存为摘要」才
  PATCH 到 `note.summary` —— 双链悬停预览与检索结果都用这个字段
- **标签写进正文末尾**:推荐 3~5 个,点标签逐个写入(写过变 ✓),也能「全部写到笔记末尾」。
  工作区已有标签会一起发给模型**请它尽量复用**,而笔记里已经写过的标签会被过滤掉;
  写进去就是普通的 `#标签`,后端解析 + 左栏标签面板照旧(不需要为 AI 另开一条路)
- **生成期间改了正文也不怕**:结果写回时用的是**发起那一刻的选区快照**,并夹到当前正文长度内
  (越界会被截住,不会插到别处);换笔记会掐掉还在跑的请求并让旧回调作废 —— 否则上一篇的
  润色结果、摘要可能落到这一篇上
- **选区太长直接报错**(> `AI_NOTES_AI_MAX_INPUT_CHARS`,默认 6000 字),**不截断**:
  截断后返回的"润色结果"只覆盖前半段,用户一点替换就把后半段删了。摘要 / 标签是整篇处理,
  那里才截断,并在正文末尾注明「已截断」
- 已知取舍:预览模式下不给选区操作(渲染后的 DOM 与 Markdown 源码偏移对不上,硬映射会替换错位置);
  结果面板不允许再编辑(要改就重来一次,或直接改正文)

**M5 Agent 工具调用(Step 1)**

- **一句话交代目标,它自己动手**:右栏多了一档「工具」。说「把最近一周的笔记整理成周报,新建一篇笔记叫
  『本周周报』」,它会自己 `listNotes` → `searchNotes` → 逐篇 `getNote` → 写出正文 → `createNote`,
  然后把笔记真的落进你的知识库(标签、版本、索引走的是普通新建链路)
- **写操作一定先问你**:新建 / 修改 / 打标签都会**停下来**,面板上出现一张卡片:「等你确认」+ 要做什么 +
  **摊开的正文预览** + 确认执行 / 取消。不点,什么都不会发生;点了才执行,然后它接着收尾。
  读操作(检索、读笔记)自动执行,不然每一步都要点一次就没法用了
- **进度看得见**:回答上方是一行一步的工具轨迹(`✓ 列出 11 篇笔记`、`✓ 读取《架构设计》、
  待确认 新建《本周周报》`),点开能看工具名、参数和原始返回。引用仍旧是 `[n]` 可点 ——
  这次连流式过程中都能点(检索工具一拿到片段就把引用发出来)
- **刷新不丢**:暂停中的卡片、整条轨迹都存在库里(会话历史的助手消息带 `run`),所以刷新、换设备、
  甚至后端重启之后,那张「确认执行」的卡片还在,点了照样能接着跑
- **取消不是失败**:点「取消」只是这次写操作被标成 `rejected`,模型会收到"用户取消了"继续收尾,
  这次运行本身仍然正常结束
- **它不会重复建同一篇**:同一轮运行里,同样的工具 + 同样的参数只会执行一次 —— 实测 8B 模型在确认通过
  之后**又想再建一次同名笔记**,被拦下并回了一句"已经跳过"。这是后端的保护,不靠提示词
- **服务商断流也留得住东西**:硅基流动的流偶尔会中途 reset(实测集中在"读完几篇、准备写"和"写完收尾")。
  一轮里还没吐出任何内容就断的会自动重试一次;写操作已经执行完才断的,提示里会明确写
  「已经确认的写操作都已生效」,不会让人怀疑到底写没写进去
- 已知取舍:一次运行最多 `AI_NOTES_AGENT_MAX_STEPS`(默认 12)步;`getNote` 塞回上下文时按
  `AI_NOTES_AGENT_MAX_NOTE_CHARS`(默认 4000 字)截断;提示词要求写进笔记的正文里别带对话用的 `[n]` 编号,
  但 8B 模型偶尔仍会带上 —— 卡片预览里能看见,觉得不对直接点取消就行(兜底靠人工确认这一层)

**M5 长期记忆(Step 3)**

- **只记你明确说的**:对 Agent 说「记住:我的周报通常周五下午写」,它会调用 `rememberFact`,
  和其它写操作一样先出确认卡片,点了确认才真正记下。不会从对话里自己悄悄抽取记忆
- **提问时自动想起**:之后每次提问,按语义找出相关的记忆,放进系统提示词。记忆只作为参考资料,
  与当前问题无关时不会硬套进回答
- **换个说法也认得出**:再记一句意思相同的话,会被识别为「这条已经记过了」,不会重复存一条
- **存在哪**:内容在 PostgreSQL 的 `memory` 表,向量在 Elasticsearch 的 `ai-notes-memory` 索引;
  启动时自动创建索引(映射见 `resources/elasticsearch/memory-index.json`)
- **可查看、可删除**:`GET /workspaces/{ws}/memories` 列出,`DELETE /workspaces/{ws}/memories/{id}` 删除
  (先删 ES 再删 PG,ES 失败时可以重试)
- 已知取舍:只有 Agent 的提问会召回记忆,普通「知识库」和「聊天」模式不带;召回阈值
  (`AI_NOTES_MEMORY_RECALL_MIN_SCORE`,默认 0.7)目前只在有限的几句话上验证过,需要按实际使用再调

**Markdown 预览(GFM 表格)**

- 预览由 `ReactMarkdown` + **`remark-gfm`** 渲染:表格 / 删除线 / 任务列表 / 自动链接都是
  **GFM 扩展,CommonMark 本体不含** —— 不挂这个插件,`| 项目 | 内容 |` 就只是几行带竖线的
  普通段落(样式其实早就写在 `.markdown-body` 里了)
- 列多的表格在**表内横向滚动**(`display: block` + `max-content` + `max-width: 100%`),
  不把预览区整个撑宽;中文单元格可断行,所以通常是"列变窄"而不是滚动。细节见 DESIGN §13.7
- 后端**不需要**跟着开表格扩展:`NoteContentParser` 只做双链 / 标签抽取,`MarkdownChunker`
  是按行切的、不依赖 AST

**检索结果点击定位**

- 点顶栏检索的任一条命中(分组行或展开后的子项)→ 打开笔记**并跳到命中的位置**:先按
  `headingPath` 定位到章节,再在章节里找片段中的命中词;落点放在预览上方 1/4 处,
  并给一个两秒多后自动消失的高亮(`note-locate`,和查找的高亮分开)
- **纯前端实现**,后端不用为此返回源码偏移 —— 预览是渲染后的 DOM,行号没用。匹配**忽略空白**,
  所以 markdown 源码里的表格竖线、缩进不会让它落空;命中词被 `**加粗**` 切开也能命中(跨文本节点)
- 当前在「编辑」模式会**自动切到「预览」**;找不到命中词时退化为"跳到那个章节的标题"。
  原因与实测见 `docs/DESIGN.md` §13.8

**预览里的 `[[双链]]`**

- 后端一直有双链(出链 / 反链 / 图谱),但**预览里它只是一段灰扑扑的文字**。现在它是**能点的
  链接**,悬停还会弹一张小卡片:目标笔记的标题 + 摘要(没有摘要就取正文开头)
- **点击**:已创建 → 打开那篇;`[[目标#小节]]` → 打开**并定位到小节**(复用检索定位那套,
  所以自动切到预览 + 短暂高亮);目标还没创建 → 就地建一篇再跳过去(与关系面板的「创建」同一行为)。
  未创建的目标画成**虚线**,卡片上写「未创建 · 点击创建」,点之前就知道会发生什么
- **链接有三种状态,不混为一谈**:已创建(实线)/ **确认未创建**(虚线,点了就建)/ **还不知道**
  (点线「解析中」—— 请求还没回来、或者后端没这个端点)。**只有后端明确回了 `noteId: null`
  才允许点出新建**:否则后端没重启时"查不到"会被当成"未创建",点一下就是凭空多一篇重复笔记
  (踩过)。解析表不可信时点链接只会提示「链接还在解析,稍等一下再点」,卡片也会写明原因
- **悬停卡片**:`position: fixed` + portal 挂到 `body` —— 预览区是 `overflow-y: auto`,
  挂在里面会被裁掉;卡片 `pointer-events: none` 所以永不吃鼠标;悬停 200ms 才弹(扫过一排
  链接不会一路闪),预览一滚动就收掉(位置是按链接当时的视口坐标算的);**点链接 / 换笔记 /
  换视图 / 正文重排也收掉**(跳转后指针还停在原地,不主动收它就会一直挂着 —— 踩过)
- **解析规则与后端逐条对齐**(`|别名`、`#小节`、`目录/` 前缀、`![[嵌入]]` 不算、代码块里不算):
  两边不一致就会出现"预览说可点、关系面板说未创建"这种自相矛盾。为此后端加了
  `POST /workspaces/{ws}/links/resolve` —— **一次问一批标题**,返回
  `[{title, noteId, noteTitle, snippet}]`(`noteId` 为 null = 还没创建;与单条解析同语义,
  同名取第一条)。实现与踩坑见 `docs/DESIGN.md` §13.9

**编辑器内查找与索引收敛(v0.8 收尾)**

- **编辑器 / 预览区 `Ctrl+F`**:光标在正文里、或鼠标停在预览上时按 `Ctrl+F`,就在该区域查找
  (计数器 + ↑/↓;分栏时可切「源码 / 预览」)。源码区按字符下标选中匹配段,预览区用
  **CSS Custom Highlight API** 给全部匹配上色 —— 不往 `ReactMarkdown` 的产物里插 `<mark>`,
  否则每次输入重渲染就丢。`F3` / `Shift+F3` 在离开输入框后继续翻;焦点在标题 / 左栏 / 顶栏
  检索框里时**不抢** `Ctrl+F`,浏览器原生查找照常工作
- **没改内容就不再重建索引**:只有标题 / 正文 / 摘要 / 文件夹**真的变化**才发索引事件 ——
  前端每次自动保存都会把 `title` 与 `contentMd` 一起 PATCH,原来无条件发事件等于「一个字没改
  也重跑一遍分块 + 嵌入接口」。例外是 `index_status != ready` 时照发,否则索引失败过的笔记会
  因为"内容没变"永远修不回来(实测:原样再保存一次不再产生 `index_job`)

**界面与主题(玻璃 + 自定义配色)**

- **设计系统集中在一处**:`src/app/globals.css` 定义 `.glass` / `.glass-hover` / `.glass-active` /
  `.sheen` / `.btn-accent` / `.aurora-field` 等组件类,组件只组合这些类,不各自堆 `backdrop-blur`
  与阴影 —— 否则改一次视觉要改十几个文件
- **玻璃质感**三层叠出来:极低透明度底色 + `backdrop-filter: blur()` + 顶部 1px 内高光;
  悬停时描边与投影染上当前强调色,所以"光"随主题走
- **浮层另有一档 `.glass-pop`**:菜单 / 下拉 / 版本历史抽屉用它 —— 底色接近不透明。
  这些浮层盖在正文上,若也用 `.glass` 的透明度,背后的字会透上来与浮层文字重合
- **弹层要"逃得出去"**:`backdrop-filter` 非 `none` 会创建层叠上下文,所以**每个 `.glass`
  容器都是层叠上下文** —— 里面的弹层 `z-index` 再高也出不去,DOM 里排在它后面的兄弟会画在它上面。
  承载浮层的 header / 容器必须自带正 `z-index`(项目用 `z-30`,要盖过 `z-20` 的图谱 / 版本历史浮层)
- **流动感**:卡片/按钮悬停时一道斜向高光扫过;背景是缓慢漂移的模糊色块。全部尊重
  `prefers-reduced-motion`
- **6 套预设 + 自定义强调色**:极光 / 樱 / 余烬 / 苔原 / 深海 / 石墨,右上角色点处切换;
  也可用取色器自定义强调色,副色由 `color-mix` 自动推导。设置存在本机 localStorage,
  **首屏不闪**(`<head>` 里的内联脚本先于首次绘制写入)
- **背景色也能配**:6 个深色色板 + 取色器(任意颜色),与强调色互不干扰(各有各的复位按钮)。
  铺到视口上的颜色会被 CSS **自动截进深色区**(亮度封顶、彩度略收、色相原样),所以文字 /
  玻璃层 / 浮层这套深色设计不用另做浅色版本 —— 最亮时与正文的对比度仍有 ~12:1。
  深色输入则**原样生效**:色板里看到的就是生效的颜色
- **登录守卫**:未登录(无 Token)访问 `/` 或 `/w/:id` 会被请回登录页;退出登录立即离开;
  Token 过期(401)自动登出。前端守卫管界面体验,**服务端由默认的
  `AI_NOTES_DEV_PERMIT_ALL=false` 强制**(置回 `true` 才允许本地匿名调试)

尚未完成(见路线图):

- 语义重排(rerank)与查询改写
- Agent:工具调用 / MCP(M5)
- 编辑器 WYSIWYG(Tiptap,用法已确认:`useEditor` + `StarterKit` + `immediatelyRender: false`)

### 测试

```bash
cd backend && mvn test
```

103 个用例,全部是**不需要任何外部服务**的纯单元测试:

- `NoteContentParserTest` —— 双链/标签的解析规则(含代码块、标题、`a#b` 等边界);
- `RepositoryQueryTest` —— 用 Spring Data 自己的 `PartTree` 校验**所有**仓储的派生方法名。
  Spring Data 只在容器启动时校验这些方法名,而本项目测试不起 Spring 上下文,
  所以这一步能把「启动即崩」的拼写错误提前到 `mvn test`(已实测拦下一个:
  `note_tag` 是复合主键,`deleteByNoteId` 会抛 `PropertyReferenceException`)。
- `ChunkIndexStoreStatusTest` —— 用 Mockito 伪造 ES 响应,守住「ES 返回 4xx 必须抛异常」。
  起因:低层 `Rest5Client` 对 4xx/5xx **不抛异常**,曾经导致建索引被拒时日志照打
  "已创建"、检索永远返回空(详见 `docs/DESIGN.md` §13.3)。
- `ChunkEmbeddingServiceTest` —— 用假 `EmbeddingModel` 守住三件事:服务端乱序返回时按
  `index` 还原顺序;维度与索引不一致必须抛错(而不是写进 ES 时被拒);
  NaN/Infinity 必须在源头拦住(已知 Ollama 的 `bge-m3` 会在少数文本上触发,ollama#14657);
  按 10 条一批切分(百炼 `text-embedding-v3` 的批量上限)。
- `RrfFuserTest` —— RRF 融合:两路都命中的排在最前、只看名次不看原始分数
  (BM25 分数与余弦相似度能量纲不同)、同分时顺序稳定、保留关键词分支的高亮片段。
- `IndexingServiceDegradationTest` —— 守住「嵌入失败不能阻断关键词索引」:
  嵌入抛错时仍然调用 ES 写入、只是向量为 `null`,同时状态置 `failed`(不能静默);
  ES 写入失败才报 `FAILED`;空笔记根本不调嵌入模型。
- `NoteServiceUpdateEventTest` —— 守住「没改就不重建索引」:标题与正文都没变时**不发**
  索引事件、也不追加版本;正文变了 / 只改标题才发;连 `updated_at` 都不该被空请求顶一下。
  例外是 `index_status != ready` 时照发(索引失败的笔记得留着自愈的机会)。
- `NoteGraphServiceResolveTest` —— 预览双链的标题解析:忽略大小写与首尾空格、未创建的目标
  `noteId` 为 null、同名笔记取第一条(与单条解析同语义)、重复标题只问一次、摘要优先于正文、
  没有摘要时抹掉 markdown 标记并截到 160 字。规则错了界面会出现"预览说可点、关系面板说
  未创建"——两处走的是不同代码路径,没法互相兜底
- `AiPromptsTest` —— RAG 的编号契约:片段从 1 起编号、标题/章节路径与正文一起进 prompt、
  没有片段时问题原样发出、无标题笔记写「未命名笔记」、快照截断到 300 字、
  **检索层的 `<em>` 高亮标签不进 prompt 也不进引用快照**(否则点引用跳不过去,实测踩过);
  文本工具的提示词同样钉在这里:任务说明进 system、「待处理文本」是资料不是指令、
  摘要要一句话且不带 Markdown 标记、标签每行一个并带上已有标签
- `ChatServiceTest` —— 用假 `ChatModel` 跑完整一轮:事件顺序(meta → citation → token → done)、
  引用 `ord` 与 prompt 编号一致、答案回填落库、`chat` 模式不碰检索、检索失败降级成无引用、
  模型失败时保留半截答案并发 `error`、**历史窗口在读本轮提问之前取**(否则同一问会出现两次)
- `ChatModeTest` —— 模式解析:默认 RAG、大小写与空格容忍、未知值明确报错
- `TextActionTest` —— 文本动作的解析与任务说明:**没有默认动作**(漏传 action 必须报错,
  猜一个「润色」就意味着用户点翻译却拿到润色过的中文)、中英别名、只有解释不写回笔记、
  每个动作都必须带任务说明
- `AiTextServiceTest` —— 文本工具:事件顺序(meta → token → done)、模型失败时保留半截并发
  `error`、`writesBack` 由 meta 下发、翻译目标语言缺省时**反着来**、空白选区与超长选区
  直接报错、摘要压成单行并去掉引号、整篇超限时截断并注明「已截断」、标签解析容错
  (`#` 前缀 / 编号 / 引号 / 已有标签 / 大小写去重 / 上限 5 个)

### REST API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册(自动创建默认工作区),返回 Token |
| POST | `/api/v1/auth/login` | 登录,返回 Token |
| GET | `/api/v1/auth/me` | 当前用户 |
| GET/POST | `/api/v1/workspaces` | 工作区列表(按成员关系过滤)/ 新建 |
| GET | `/api/v1/workspaces/{ws}` | 工作区详情 |
| GET/POST | `/api/v1/workspaces/{ws}/notes` | 笔记分页列表(可按 `?q=` 标题过滤、`?tag=` 标签过滤)/ 新建 |
| GET/PATCH/DELETE | `/api/v1/notes/{id}` | 笔记详情 / 更新 / 软删除 |
| GET | `/api/v1/notes/{id}/versions` | 版本列表 |
| GET | `/api/v1/notes/{id}/versions/{n}` | 指定版本内容 |
| POST | `/api/v1/notes/{id}/versions/{n}/restore` | 回滚到指定版本 |
| GET | `/api/v1/notes/{id}/relations` | 本篇的标签 + 出链 + 反链 |
| GET | `/api/v1/workspaces/{ws}/tags` | 工作区标签及使用次数 |
| GET | `/api/v1/workspaces/{ws}/graph` | 工作区知识图谱(节点 + 边) |
| POST | `/api/v1/workspaces/{ws}/links/resolve` | 批量把 `[[标题]]` 解析成笔记(预览双链据此决定可点 / 未创建,并取悬停预览文本) |
| GET/POST | `/api/v1/workspaces/{ws}/conversations` | 会话列表(按最近活动倒序)/ 新建(标题可空,首句提问自动成标题) |
| GET | `/api/v1/conversations/{id}/messages` | 会话完整历史(含每条回答的引用快照) |
| PATCH/DELETE | `/api/v1/conversations/{id}` | 重命名 / 删除(消息与引用级联删) |
| POST | `/api/v1/conversations/{id}/chat` | **SSE 流式问答**:`{q, mode}`(`rag` 查知识库并给引用 / `chat` 纯聊天) |
| POST | `/api/v1/ai/inline` | **SSE 流式文本操作**:`{text, action, to?}`(`action` = `polish`/`translate`/`continue`/`summarize`/`explain`,或中文别名);事件 `meta`(含 `label` / `writesBack`)→ `token`* → `done`\|`error`。**不落库** |
| POST | `/api/v1/ai/summarize` | 一句话摘要:`{title, content}` → `{summary}`(**不写库**,要不要保存由前端 PATCH 笔记决定) |
| POST | `/api/v1/ai/suggest-tags` | 推荐标签:`{title, content, existing[]}` → `{tags[]}`(已有标签只用于「尽量复用」) |
| POST | `/api/v1/workspaces/{ws}/agent/run` | **SSE Agent 运行**:`{goal, conversationId?}`;事件 `meta` → (`tool_call` → `tool_result` → `citation`*)* → `token`* → `done`\|**`confirm`**(有写操作等你确认,流暂停)\|`error` |
| POST | `/api/v1/agent/runs/{id}/confirm` | **SSE 续跑**:`{approve}`;批准则执行待确认的写操作,否则标 `rejected`,然后接着推理(同一个 `runId` / 助手消息) |
| GET | `/api/v1/agent/runs/{id}` | 一次运行的轨迹快照(状态 + 每一步工具、参数、结果、耗时) |
| GET | `/api/v1/workspaces/{ws}/memories` | 长期记忆列表(由 Agent 的 `rememberFact` 经确认后写入) |
| DELETE | `/api/v1/workspaces/{ws}/memories/{id}` | 删除一条长期记忆 |
| POST | `/api/v1/workspaces/{ws}/search` | 检索(**BM25 + 向量**,应用层 RRF 融合;中文分词优先 IK,缺失回退 standard) |
| GET | `/api/v1/workspaces/{ws}/search/suggest?q=` | 标题联想 |
| POST | `/api/v1/workspaces/{ws}/reindex` | 重建整个工作区的检索索引(删索引/换嵌入模型/修复失败笔记后使用) |
| POST/GET | `/api/v1/notes/{id}/attachments` | 上传 / 列出附件 |
| GET | `/api/v1/attachments/{id}/content` | 下载附件 |

---

## 路线图

| 里程碑 | 内容 |
|---|---|
| **M0 骨架** ✅ | 工程结构、CRUD、编辑器、compose |
| **M1 全文检索** ✅ | 登录鉴权、附件、ES 索引 + IK 中文分词(缺失时自动回退 `standard`)、搜索 UI、版本回滚 |
| **M2 双链与标签** ✅ | `[[链接]]` / `#标签` 解析、反链面板、标签聚合、知识图谱 |
| **M3 语义检索** ✅ | 分块 + 嵌入 + `dense_vector` + 混合检索(**应用层** RRF,原生 rrf 需 platinum 许可)+ 重建索引接口 |
| **M4 AI + RAG** ✅ | 对话 + RAG + 引用 + SSE 流式;选中文本内联操作(润色 / 翻译 / 续写 / 总结 / 解释)+ 自动摘要 / 标签(rerank 除外) |
| **M5 Agent** | ✅ 工具调用 + 写操作确认(Step 1)、长期记忆(Step 3)。MCP 已决定不做 |
| **M6 打磨** | 导出/vault、Git 同步、多模型、可观测、PWA |

---

## 配置

后端**全部配置项都在 [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml)
里,写成 `${环境变量:默认值}` 的形式** —— 那张文件就是配置清单的单一来源,下表只是常用项。

需要覆盖时,在启动前 `export`(Linux/macOS)或 `$env:X=...`(PowerShell)即可。
> 本仓库**不提供 `.env` 文件**。Spring Boot 原生不读 `.env`,放一个在那儿只会让人误以为它生效;
> 真要按文件注入,请用 IDE 的 EnvFile 插件、`docker compose --env-file`,或自行引入 dotenv 库。
> 前端是例外:Next.js 原生支持 `frontend/.env.local`,模板见 `frontend/.env.local.example`。

关键项:

| 变量 | 说明 |
|---|---|
| `SPRING_DATASOURCE_*` | PostgreSQL 连接 |
| `SPRING_ELASTICSEARCH_URIS` | Elasticsearch 地址 |
| `AI_CHAT_BASE_URL` | OpenAI 兼容端点(**对话与嵌入共用这一份连接配置**,Spring AI 的 `base-url`/`api-key` 在两者间是共享的,所以选一个同时提供两类模型的服务商更省事) |
| `AI_CHAT_API_KEY` | 端点密钥(M3 起**必需**:没有它就无法生成向量) |
| `AI_CHAT_MODEL` | 对话模型名(M4 起使用,默认 `Qwen/Qwen3-8B`) |
| `AI_EMBEDDING_MODEL` | 嵌入模型名(默认 `text-embedding-v3`,输出 1024 维) |
| `AI_EMBEDDING_DIMENSIONS` | 向量维度(必须与嵌入模型、以及 `chunk-index.json` 里的 `embedding.dims` 三者一致) |
| `AI_NOTES_RRF_RANK_CONSTANT` | RRF 融合的 rank constant(默认 60,越小头部优势越明显) |
| `AI_NOTES_RAG_TOP_K` | 每轮问答检索几段笔记喂给模型(默认 6;片段上限 800 字) |
| `AI_NOTES_AI_HISTORY_WINDOW` | 每轮问答带多少条历史消息(默认 20;见 DESIGN §7.1) |
| `AI_NOTES_AI_MAX_INPUT_CHARS` | 文本工具单次处理的原文字数上限(默认 6000;**选中文本超过直接报错**,整篇摘要/标签则截断并在正文注明;见 DESIGN §13.11) |
| `AI_NOTES_AGENT_MAX_STEPS` | Agent 一次运行最多几步(默认 12;到顶以 `STEP_LIMIT` 结束,已生成的部分照留) |
| `AI_NOTES_AGENT_MAX_NOTE_CHARS` | 工具把一篇笔记正文塞回上下文时的字数上限(默认 4000,超出截断) |
| `AI_CHAT_TIMEOUT` | 对话 / Agent 单次模型请求的超时(默认 `5m`;注意要写 `spring.ai.openai.timeout` 这个**公共项**,`chat.timeout` 在 2.0.1 里对客户端构造无效) |
| `AI_NOTES_ASYNC_TIMEOUT` | SSE 异步请求超时(默认 `600s`;Agent 一轮多步可能跑很久) |
| `AI_NOTES_MEMORY_INDEX` | 长期记忆向量索引名(默认 `ai-notes-memory`) |
| `AI_NOTES_MEMORY_RECALL_TOP_K` | 每次提问最多召回几条记忆(默认 5) |
| `AI_NOTES_MEMORY_RECALL_MIN_SCORE` | 召回的最低相似度分(默认 0.7,低于它的不带入提示词) |
| `AI_NOTES_MEMORY_DEDUP_SCORE` | 新记忆与已有记忆相似度达到它就视为重复(默认 0.95) |
| `AI_NOTES_JWT_SECRET` | JWT 签名密钥(HS256,**至少 32 字节**,生产必须覆盖) |
| `AI_NOTES_JWT_TTL` | Token 有效期(ISO-8601 时长,默认 `PT168H`) |
| `AI_NOTES_DEV_PERMIT_ALL` | `false`(默认):严格鉴权,匿名请求 401;`true`:未带 Token 也放行(仅本地调试) |
| `AI_NOTES_DEMO_EMAIL` | 演示账号邮箱(默认 `demo@ai-notes.local` / `demo1234`) |
| `AI_NOTES_ES_INDEX` | 检索索引名(默认 `ai-notes-chunk`) |
| `AI_NOTES_CHUNK_MAX_CHARS` | 单个 chunk 最大字符数(默认 800) |
| `AI_NOTES_STORAGE_ROOT` | 附件存储根目录(默认 `./data`) |
| `AI_NOTES_CORS_ORIGINS` | 允许的前端来源 |

> 唯一不写成 `${VAR:默认值}` 的是 `spring.ai.openai.chat.options.extra-body` 里的
> `enable_thinking` —— 它必须是**布尔字面量**:占位符解析到 `Map<String,Object>` 时不做类型转换,
> 发出去会变成字符串 `"false"`,硅基流动直接拒绝(400 / `code 20015`,实测踩过)。
> 想开回思考模式改成 `true`;换服务商(尤其 OpenAI 官方)时删掉这一段,未知字段会被拒。
> 详见 `docs/DESIGN.md` §13.10 第 5 节。

> 换嵌入模型时注意:如果新模型维度不是 1024,必须同时改 `AI_EMBEDDING_DIMENSIONS`、
> `chunk-index.json` 里的 `embedding.dims`,并**删掉索引重建**(`dense_vector` 的维度不可修改)。
> 维度对不上时后端会在写入/检索时直接报错并说明怎么改,不会静默返回空结果。

---

## 排错

### 启动报 `relation "app_user" does not exist`

Flyway 没跑。Spring Boot 4 的自动配置是**按技术拆成独立模块**的:光有 `flyway-core`
不会带来 `FlywayAutoConfiguration`,必须再依赖 `org.springframework.boot:spring-boot-flyway`
(pom 里已加)。判断方法:启动日志里搜 `flyway` —— 一条都没有就是这个问题。

### 检索永远返回空

第一步看后端启动日志里 `EsIndexInitializer` 那一行:

| 日志 | 含义 |
|---|---|
| `已创建 Elasticsearch 索引 ...(IK 中文分词)` | 正常 |
| `IK 分词器不可用,回退到内置 standard 分词器...` | ES 没装 IK,装法见上面「启动基础设施」 |
| `Elasticsearch 索引初始化失败...` | 连不上 ES,或 mapping 被拒(同一行里有 ES 的原始响应) |

第二步确认索引 mapping 没被 ES 动态推断过——那种情况下 `workspace_id` 会变成 `text`,
查询里的 `term` 过滤永远匹配不上,表现为「所有检索都空」:

```bash
curl -u elastic:<password> "http://localhost:9200/ai-notes-chunk/_mapping?pretty"
```

正常应当看到 `"workspace_id": {"type": "keyword"}`。若是 `text`,说明索引其实是
`_bulk` 写入时被 ES 自动建出来的(我们的建索引请求失败了),删掉重建即可:

```bash
curl -u elastic:<password> -X DELETE http://localhost:9200/ai-notes-chunk
# 重启后端 → 索引按正确 mapping 重建;再保存一次笔记触发重新索引
```

> 索引是**可重建的派生数据**:删掉只是暂时查不到,笔记本体在 PostgreSQL 里安然无恙。
> 删掉之后用 `POST /api/v1/workspaces/{ws}/reindex` 重建(返回 `ready / keywordOnly / failed`)。

### 检索能搜到,但没有语义结果(或提示 `index_status=failed`)

M3 起检索是关键词 + 向量两路融合。向量这一路依赖外部嵌入接口,出问题时表现是
「关键词能搜到、语义搜不到」。看后端日志里的 WARN:

| 日志 | 含义与处理 |
|---|---|
| `向量分支不可用(本次降级为纯关键词检索): 401 ...` | 查询期嵌入失败,本次搜索退化成纯关键词。检查 `AI_CHAT_API_KEY` |
| `笔记 xxx 的向量生成失败,本次只写入关键词索引...` | 索引期嵌入失败。该笔记 `index_status=failed`、关键词仍能搜到 |
| `嵌入维度不匹配:模型 [x] 返回 1536 维,当前配置是 1024 维` | 换了嵌入模型但没改维度。按提示改 `AI_EMBEDDING_DIMENSIONS` + `chunk-index.json` 后删索引重建 |
| `IK 分词器不可用,回退到内置 standard 分词器...` | 与向量无关,是中文分词精度问题,见上面「启动基础设施」 |

改完配置后跑一次 `POST /api/v1/workspaces/{ws}/reindex` 补向量,返回
`keywordOnly` 为 0 就说明全部就绪。

### 某篇笔记索引显示 `ready`,但怎么都搜不到

先看日志有没有 `笔记 xxx 正文有 N 个字符却切不出任何检索片段`。
分块器是按行找 ATX 标题(`# ~ ######`)的,**整篇只有一行**时这一行会被整体当成标题、
正文缓冲区为空,于是 0 个片段 —— 这种笔记一个文档都写不进索引。
M0 的种子数据正好踩了这个坑(`V2__seed.sql` 里把换行写成了字面量 `\n`,
而 PostgreSQL 不解释标准字符串字面量里的转义),已由 `V5__fix_seed_welcome_note.sql` 修复。

### 服务端 ES 版本

本项目 pin 的是 ES 9.x 客户端与 9.x 镜像;低层 REST 调用在 **8.x** 上也能正常工作
(M1 检索、M3 的 `knn` + `filter` 都已实测)。差别在原生 `rrf` retriever:
它需要 platinum 许可,实测在 basic 许可的集群上会被直接拒绝
(`current license is non-compliant for [Reciprocal Rank Fusion (RRF)]`),
所以本项目在应用层做融合 —— 这也是默认路径,不装 9.x 也完全可用。

### 退出登录后还能访问笔记

这个现象由 `ai-notes.security.dev-permit-all` 决定。它**默认已是 `false`**(服务端强制鉴权):
匿名请求一律 401,所以退出登录后无论是直接输 URL 还是点浏览器后退,都拿不到数据。

只有把它显式置成 `true`(为了本地调试省掉换 Token)时,才会看到"退出后还能访问" ——
因为不带 Token 的请求被放行并回落到演示账号,前端删掉 Token 之后接口照样返回数据:

```bash
export AI_NOTES_DEV_PERMIT_ALL=false   # 恢复服务端强制鉴权
```

> 前端的路由守卫(`RequireAuth`)只是界面体验,**不是安全边界**;真正的边界是这个开关。
> 判定登录态刻意不调 `/auth/me` —— 放行模式下它对匿名请求也返回演示账号,分辨不出来。

### 语义检索"看起来没生效"

kNN **永远**返回 Top-K,乱码查询也有结果,所以"有没有结果"不能判断语义检索是否工作。
要拿**排序是否随查询变化**来判:同一个索引上,两个语义目标不同的查询必须给出不同排序。

排查时分两步把问题隔离开:

1. **先验索引与向量本身**——直接问 ES,拿某个 chunk 自己的向量当 `query_vector` 做 kNN,
   它应当以 `score=1.0` 排第一;换另一个 chunk 的向量,排序应当明显不同。
   若这一步正常,问题就在应用层的查询向量,不在索引。
2. 再看后端日志有没有 `向量分支不可用(...)` 的 WARN(查询期嵌入失败会静默降级成纯关键词)。

> 小语料容易误判:只有几条**高度相似**的笔记时(实测余弦 0.75~0.82),排序主要由文档中心度
> 主导,少数查询恰好给出相同排序是正常的,不代表查询向量没生效。

### 索引里有文档搜不到语义结果(`keywordOnly`)

早期配置错误期间入库的 chunk 只有关键词字段、没有 `embedding`:它们能被 BM25 召回、
**永远不能被 kNN 召回**,而且不报错、只是"安静地搜不到"。用 `POST /workspaces/{ws}/reindex`
重建即可(它是逐笔记独立事务,幂等)。

自查当前哪些工作区缺向量:

```bash
curl -u elastic:<password> -H 'Content-Type: application/json' \
  "http://localhost:9200/ai-notes-chunk/_search" -d '{
    "size":0,
    "aggs":{"ws":{"terms":{"field":"workspace_id"},
      "aggs":{"with":{"filter":{"exists":{"field":"embedding"}}},
              "without":{"filter":{"bool":{"must_not":{"exists":{"field":"embedding"}}}}}}}}}'
```

### 表格预览是一堆竖线文本

**症状**:把一份带表格的文档(比如 DESIGN.md)贴进笔记,预览区没渲染成表格,而是一堆
`| 项目 | 内容 |` 这样的普通段落 —— 可是 `.markdown-body table` 的边框样式明明写好了。

**原因**:表格是 **GFM 扩展,CommonMark 本体里没有**。`<ReactMarkdown>` 默认只吃 CommonMark,
不挂 `remark-gfm` 的话表格语法就是普通文本。

**修法**:`remarkPlugins={[remarkGfm]}` 传给 `ReactMarkdown`(插件数组放模块级常量,
别每次渲染新建数组,否则会重建 markdown 处理管线)。顺带获得删除线、任务列表、自动链接。

**宽表**:上面那条 CSS(`display: block` + `width: max-content` + `max-width: 100%` +
`overflow-x: auto`)让宽表在**自己的框里**滚动。`max-width: 100%` 是关键 —— 少了它,表格盒子会
超出预览区、把整篇正文拖出横向滚动条,而表内滚动永远不触发。详见 DESIGN §13.7。

### 主题弹层被正文盖住 / 点不动

**症状**:在笔记编辑界面点右上角的主题色点,弹层看得见轮廓,但点预设没反应 —— 点击其实打在了
下面的编辑器上。

**原因**:`backdrop-filter` 非 `none` 会创建层叠上下文,所以**每个 `.glass` 容器都是一个层叠
上下文**。工作区 header 正是 `.glass`,里面的弹层 `z-index` 再高也出不了这个上下文;而编辑列排在
DOM 之后、同为 `z-index: auto`,按顺序画在 header 上面 —— 弹层就连同 header 一起被盖住了。

**规则**:放浮动弹层的元素(header / 容器)必须自己带正 `z-index`,把整个层叠上下文提到内容之上。
本项目用 `z-30`(要盖过 `z-20` 的图谱与版本历史浮层),见工作区 header 与登录页的主题选择器容器。
顶栏的全文检索浮层同理 —— 它就挂在同一个 header 里(`z-50` 只是层内顺序,出不出得去由 header 决定)。

> 这个坑特别隐蔽:它只在 `backdrop-filter` **真正生效**时才出现。若模糊因为前缀问题被浏览器
> 丢掉(见上面「玻璃质感」那条),反而不创建层叠上下文,弹层看起来一切正常。

### 背景选了亮色,字会不会看不清

**结论**:不会。取色器给的是任意颜色,但铺到视口上的值会被 CSS 截进深色区:

```css
--bg-deep: oklch(from var(--bg-pick) min(l, 0.3) min(c, 0.12) h);  /* 只截亮度、略收彩度,色相原样 */
```

最亮时底色与正文的对比度仍有 ~12:1(WCAG AA 只要求 4.5:1);深色输入(亮度 ≤ 0.3)**原样生效**,
所以色板里看到的就是生效的颜色。

**为什么只压深、不把文字一起翻成浅色**:整套界面(玻璃层、文字、浮层、遮罩、`bg-white/N`
分隔线)都是按深色底调过的,让浅色底也好用 = 另加一套浅色主题,不是加一个配置项。
所以这一维只做「深色区内的自由」。详见 `docs/DESIGN.md` §13.5 第 6 点。

**顺带一个 CSS 坑**:自定义属性「后一条声明无效」**不会**回退到前一条声明(级联早就把前一条淘汰了),
所以给相对颜色语法留降级不能用重复声明,只能用 `@supports`。

### 预览区滚不动 / 看不到笔记底部

**症状**:分栏模式下编辑区能滚,预览区完全不滚,笔记底部的内容看不到。注意这**不是**"没做同步
滚动" —— 是预览区连自己都滚不动。

**原因**:`MarkdownEditor` 的外层 `section` 少了 `min-h-0`。它是列方向 flex 项,而 flex 项默认
`min-height: auto` 的含义是"不许矮于自身内容",于是 section 被内容撑高、溢出到底下的关系面板
之外;里面的预览**永远与内容等高**,`overflow-y-auto` 没有可滚余量,超出部分被最外层
`overflow-hidden` 裁掉。

一行自查(预览的 `clientHeight` 恒等于 `scrollHeight` 就是这个问题):

```js
const pv = document.querySelector('.markdown-body');
console.log(pv.clientHeight, pv.scrollHeight);  // 1456 1456 → 滚不动
```

**修法**:给编辑器根节点加 `min-h-0`。凡是"列方向 flex 里再放一个要内部滚动的子块",那一层
都得能"矮于内容",否则滚动永远轮不到它。

### 找不到删除笔记的按钮

**症状**:笔记列表里翻遍了也没有删除,只能去翻 API。

**原因**:删除入口是**悬停**才出现的 —— 鼠标移到某一项上,右上角才浮出 ✕。它不常驻是有意的:
列表项已经承载标题/时间/索引状态,再常驻一个红色 ✕ 会让整栏看起来"处处可删"。

**用法**:悬停 → 点 ✕ → 再点「删除」确认(**两步**,没有撤销;误触点「取消」即可)。
删除是软删除(语义见「当前进度 → M2」),但界面里没有回收站。

> 键盘用户走 `Tab` 也能到 ✕:它带 `focus-visible`,获得焦点时同样显形(见 DESIGN §13.2)。

### 全文检索总是返回"满屏",而且一篇笔记占了好几行

**症状**:搜什么都是一个数量级的条数,乱码也照样有结果;同一篇长笔记连着占很多行。

**原因**:命中单位是**分块(chunk)** 而不是笔记,而**向量分支没有相关性阈值** —— kNN 永远返回
最近的 k 条(k = `topK × 4`,上限 100)。语料比 k 小时,**任何查询都会把整个工作区召回**,
顺序由 RRF 与 BM25 的名次决定。

**怎么判断**:看条数没有意义,看**排序**是否随查询变化(乱码查询的分数会退化成只来自向量那一路
的 `1/61~1/67`)。想确认块数,直接数 ES:

```bash
curl.exe -s -u elastic:<密码> -H "Content-Type: application/json" \
  -d '{"query":{"term":{"workspace_id":"<工作区 id>"}}}' \
  "http://<es>:9200/ai-notes-chunk/_count"
```

**已经做的缓解**:界面把命中**按笔记归并**(一篇一行 + 「N 处」角标,可展开),页脚显示
「X 篇笔记 · Y 处命中」。彻底解决要么给向量分支加相对阈值,要么上 rerank(见 DESIGN §13.6 第 1 点)。

### 保存一次笔记是不是就重建了一遍索引?

**不会重复写**:重建是"先按 `note_id` 删旧块、再 `_bulk` 写入",文档 `_id` 是确定性的
`{noteId}:{ord}` —— 写同一个 `_id` 就是覆盖。实测索引里 `_id` 零重复、同一笔记内没有相同内容。

**但会重复"算"** —— 每次真的保存都会把该笔记的**全部**分块重新分块 + 重新调嵌入接口
(7 块的笔记改一个字 = 7 次嵌入请求)。所以 v0.8 起只有标题/正文/摘要/文件夹**真的变化**
才发索引事件;例外是 `index_status != ready`(索引失败过)时照发,给它留自愈机会。

**自查**:
```sql
select note_id, status, count(*) from index_job group by note_id, status order by 3 desc;
```
如果某个 `note_id` 的条数远多于它的版本数,说明有大量"内容没变"的保存(或改标题)在触发重建。

