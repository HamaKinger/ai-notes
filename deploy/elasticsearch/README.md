# Elasticsearch 检索索引

对应设计文档 [`docs/DESIGN.md`](../../docs/DESIGN.md) §5.3 与 §6。

## 1. 安装中文分词插件(analysis-ik)

官方镜像**不含** IK 插件,需要通过下列任一方式安装:

**方式 A:启动后安装(开发用)**

```bash
docker exec -it ai-notes-elasticsearch \
  bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/9.1.0
docker restart ai-notes-elasticsearch
```

> 版本号必须与 Elasticsearch 版本严格一致(当前 compose 中为 `9.1.0`)。

**方式 B:自建镜像(推荐用于团队 / 生产)**

```dockerfile
FROM docker.elastic.co/elasticsearch/elasticsearch:9.1.0
RUN bin/elasticsearch-plugin install --batch \
    https://get.infini.cloud/elasticsearch/analysis-ik/9.1.0
```

若不想引入第三方插件,也可改用 Elasticsearch 官方的 `smartcn` 分词器,
把 `ai-notes-chunk-index.json` 里的 `ik_max_word` / `ik_smart` 换成 `smartcn`。

## 2. 创建索引(通常不需要手动做)

**后端启动时会自动创建索引**:`EsIndexInitializer` 读取
`backend/src/main/resources/elasticsearch/chunk-index.json`,发现索引不存在就 `PUT` 建好。
Elasticsearch 连不上时只打一条 WARN,不影响后端启动。

同目录下的 `ai-notes-chunk-index.json` 是**手动运维用的副本**(内容需与后端自带的那份保持一致):

```bash
curl -X PUT "http://localhost:9200/ai-notes-chunk" \
  -H "Content-Type: application/json" \
  --data-binary @ai-notes-chunk-index.json
```

## 3. 验证中文分词

```bash
curl -X POST "http://localhost:9200/ai-notes-chunk/_analyze" \
  -H "Content-Type: application/json" \
  -d '{"analyzer":"cn","text":"本地知识库的混合检索设计"}'
```

应看到被切成多个中文词条,而不是整句或单字。

## 4. 注意事项

- **向量维度**:`embedding.dims` 必须与嵌入模型一致(默认配置用阿里百炼
  `text-embedding-v3` = 1024;docker-compose 的默认走本地 `bge-m3`,同样是 1024)。
  换模型需重建索引(`dense_vector` 的维度不可修改),然后用
  `POST /api/v1/workspaces/{ws}/reindex` 回填向量。
- **IK 分词器**:官方镜像不带,需要自己装(见本目录 README 开头与 `docs/DESIGN.md` §11.1)。
  没装也不至于起不来:后端会回退到 `standard` 分词器并打 WARN。
- **不再需要 Spring AI 的 VectorStore**:M3 的实现由后端自己写 `embedding` 字段
  (见 `docs/DESIGN.md` §13.4),所以本索引的 mapping 完全由
  `backend/src/main/resources/elasticsearch/chunk-index.json` 决定,字段名不需要迁就 Spring AI。
- **工作区隔离**:所有查询都应带上 `workspace_id` 过滤,避免跨工作区泄漏。
