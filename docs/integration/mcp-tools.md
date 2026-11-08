# 数据问答对接规范：MCP 只读工具

状态：已定稿（#166，决策见 D-058），协议版本与令牌注入方式已在 #180 验证（D-060）。对应 ROADMAP M8；实现见 #180～#183。

客户按本规范开放**只读**接口，我方就能把它接成“可信数据问答”的工具。规范只约定对接契约，不规定客户系统内部怎么实现。关键词“必须 / 应该 / 可以”的含义同 RFC 2119。

## 1. 总体

- 客户系统是 **MCP 服务器**（资源服务器），我方是 **MCP 客户端**。1.x 只接只读工具，写操作不在本规范内（2.0 方向，写操作先经人确认）。
- 提问人的权限、工具能不能调、能看哪些门店的数据，**全部由我方判定**（设计原则：权限由系统判定，不交给模型）。客户系统不需要理解我方的角色。
- 模型只负责选工具和填参数，调用前后都由我方代码校验；模型的输出不能扩大数据范围。

## 2. 传输

- **Streamable HTTP**，单个 MCP 端点（如 `https://<客户域名>/mcp`），只用 POST；**不用 stdio**（客户系统是远端服务）。
- 版本：**已验证（#180）**：我方 client 用的 MCP Java SDK 2.0.0（Spring AI 2.0.0 带的版本）支持 2024-11-05、2025-03-26、2025-06-18、**2025-11-25**，**不支持 2026-07-28**，所以 1.x 实际按 **2025-11-25** 对接：先 `initialize` 协商（可带 `Mcp-Session-Id`，服务器可以不发），再 `notifications/initialized`，然后 `tools/call`。客户系统若实现 2026-07-28（无会话），需要同时兼容 2025-11-25 的握手；规范 2026-07-28 的无会话形式待 SDK 支持后再接，届时更新本节。
- 必须 HTTPS（内网测试环境可例外，要在连接配置里显式声明）；服务器必须校验 `Origin`（非法返回 403）。
- 响应可以是 `application/json` 或 `text/event-stream`，我方两种都支持；只读查询建议直接返回 JSON。
- 不使用 `subscriptions/listen`、`resources`、`prompts`、采样、征询（elicitation）；只实现 `tools/list`、`tools/call`。
- 我方 client 用 Spring AI 2.0 的 MCP 依赖（`spring-ai-mcp`，底层是 MCP Java SDK 的 `HttpClientStreamableHttpTransport`），不自己写协议；它只出现在 rag-ai（D-056）。没有用 starter 的自动配置：它的 client 是应用级单例，不能按调用换令牌（见第 3 节）。

## 3. 身份与令牌

我方是自己的签发方：**每次 `tools/call` 签发一个短期 JWT**，放在 `Authorization: Bearer <令牌>`（不放 URL）。客户系统**只校验签名和声明，不维护用户体系、不做账号映射**。

令牌（JWT，`typ=at+jwt`，参考 RFC 9068；算法 ES256）声明：

| 声明 | 含义 |
| --- | --- |
| `iss` | 我方签发者标识（部署时写进客户系统的配置） |
| `aud` | 该 MCP 端点的规范 URI（RFC 8707 的 resource），客户系统**必须**校验，不是给自己的令牌一律 401 |
| `sub` | 提问人的不透明用户标识（我方用户 ID，不是姓名、手机号） |
| `exp`、`iat` | 有效期 ≤ 60 秒 |
| `jti` | 唯一编号，客户系统可用来防重放，也写进它自己的日志，与我方审计对账 |
| `tool` | 本令牌只用于这个工具名 |
| `data_scope` | 我方判定出的数据范围，如 `{"stores":["S001","S002"],"regions":["east"]}`，按“门店 / 区域”两个维度 |

- 公钥：我方提供 JWKS 地址（或离线交付公钥文件），支持密钥轮换（`kid`）。客户系统缓存 JWKS 并容忍 ≥ 60 秒时钟偏差。
- 客户系统**不得**把收到的令牌转给别的服务（禁止令牌透传）；需要访问它自己的下游，用它自己的凭据。
- **服务令牌**：`initialize` 和 `tools/list` 使用（`sub=service:rag-pilot`，`tool=*`，`data_scope` 为空），只用于连接和发现工具，不返回数据。
- **注入方式（已验证）**：SDK 的 client 是会话级的，所以每次调用新建一个 client（`McpClient.sync(HttpClientStreamableHttpTransport)`）：先用服务令牌 `initialize`，再换成该提问人、该工具的令牌发 `tools/call`，用完关闭。令牌经 `McpSyncHttpClientRequestCustomizer` 从传输上下文（`transportContextProvider`）取，放进 `Authorization` 头。SDK 对 401/403 以外的非 2xx 响应不带状态码，所以在它用的 `HttpClient` 外面套一层记下状态码和 `Retry-After`，用来区分 429、4xx 和 502/503/504。
- **签名密钥**：ES256 的 JWK（含私钥），只从环境变量 `RAG_TOOL_SIGNING_KEY` 读（D-059）；公钥用 JWKS 交给客户系统（导出命令见 `deploy/README.md`）。
- 与 MCP 授权规范的关系：规范里的 OAuth 流程（受保护资源元数据 RFC 9728、授权服务器发现、动态注册）面向“用户在浏览器里授权”的场景。本场景是服务端到服务端、客户与我方已线下约定，所以采用**预先配置**的信任：客户系统配置 `iss`、JWKS、`aud`，仍遵守“令牌放头里、校验受众、401/403 语义、不透传”。这是对规范的有意简化，评审时确认。
- 若客户系统想用自己的账号体系鉴权，需要做账号映射，不在 1.x 范围。

## 4. 数据范围授权

- 授权对象沿用“用户或角色”，与知识库授权同一套模型；维度是**门店 / 区域**（区域包含若干门店，由客户的门店主数据定义，规范要求工具能返回门店所属区域）。
- 判定只写在我方一处（#168 的 `rag-data`）：管理员**同样不绕过**数据范围；没有授权就是空范围，调用直接拒绝，不发请求。
- 我方做三道检查：
  1. **调用前**：工具参数里的门店 / 区域必须落在 `data_scope` 内，否则不发请求，回答“无权查看”；
  2. **令牌**：`data_scope` 给客户系统做第二道校验，客户系统**应该**拒绝范围外的查询（工具执行错误 `out_of_scope`）；
  3. **返回后**：结果里每一行的门店标识必须在范围内，范围外的行丢弃并记审计事件。
- 范围外查询的回答不暴露该门店的任何数据。

## 5. 工具契约

- 工具名：ASCII 字母数字、下划线、点、连字符，≤ 64 字符，用“动词_对象”，如 `get_store_inventory`。
- 必须有 `description`（给模型看，写清用途和不能做什么）、`inputSchema`（JSON Schema 2020-12，`additionalProperties:false`）、`outputSchema`；必须标注 `annotations.readOnlyHint=true`。MCP 规范要求客户端把工具注解当作不可信信息，所以**是否只读由我方的工具授权清单决定**，注解只作提示。
- 参数：只接受结构化条件（门店编号、商品编码、日期区间等），**不接受 SQL、自由文本过滤或表达式**。必须校验所有输入，并限制返回量（`limit` 默认 50、最大 100）。
- 返回：用 `structuredContent` 且符合 `outputSchema`，同时在 `content` 里放同样内容的文本 JSON（向后兼容）。结构必须包含：

| 字段 | 含义 |
| --- | --- |
| `data` | 结果行，每行带门店标识 |
| `source` | `system`（系统名）、`dataset`（数据集 / 接口名），用于回答引用 |
| `query` | 实际执行的条件（门店、商品、日期区间、`limit`），用于回答引用和核对 |
| `asOf` | 数据截至时间（ISO 8601，带时区）；实时数据等于查询时间 |
| `queriedAt` | 客户系统执行查询的时间 |
| `truncated` | 是否因 `limit` 被截断 |

- `tools/list` 返回顺序固定；工具集可以随令牌变化，但不得因连接状态变化。

## 6. 超时、错误、限流

| 项 | 约定 |
| --- | --- |
| 超时 | 我方单次调用 10 秒（覆盖 Spring AI 默认的 20 秒）。客户系统应在 8 秒内返回或失败 |
| 重试 | 只读幂等，对超时、502/503/504 最多重试 1 次，间隔 ≥ 500 ms；4xx 不重试 |
| 协议错误 | 未知工具、请求格式错、版本不支持：JSON-RPC 错误（`-32602`、`-32601`、`-32020` 等），HTTP 400/404 |
| 令牌无效或过期 | HTTP 401，带 `WWW-Authenticate: Bearer` |
| 权限不足 | HTTP 403（`insufficient_scope`）；范围外的业务查询用工具执行错误 `isError:true` 加 `code=out_of_scope` |
| 业务错误 | `isError:true`，`structuredContent.code` 取 `invalid_argument`、`not_found`、`out_of_scope`、`unavailable`，`message` 用中文且不含内部信息 |
| 限流 | 我方每个用户 30 次/分钟、每个连接并发 ≤ 8；客户系统可返回 429 + `Retry-After`，我方遵守并告诉用户稍后再试 |
| 失败时的回答 | 工具失败一律如实说“暂时查不到”，**不编造数据、不用旧数据冒充** |

以上数值是起始值，试点后按实测调整，调整写进 DECISIONS.md。

## 7. 引用

- 回答中每个数据结论必须带引用：来源系统和数据集（`source`）、查询条件（`query`）、数据截至时间（`asOf`）、查询时间（`queriedAt`）。
- 引用与知识库片段引用并列展示；数据和制度冲突时都列出，不自行取舍。
- 没有工具结果支持的数字，回答里不得出现。

## 8. 审计

每次工具调用（含被我方拒绝、未发出请求的）记一条，保留 **1 年**（与管理操作审计一致）：

- **记**：时间、提问人用户 ID、工具名、参数里的业务条件（门店、商品编码、日期区间）、`data_scope` 摘要（门店数、区域数，不逐条）、结果行数、是否截断、耗时、HTTP 状态或错误码、令牌 `jti`、被丢弃的范围外行数。
- **不记**：令牌、签名密钥、返回的数据内容、员工的问题原文、模型提示词。
- 同时按运维规则记一行 `event=tool_call` 日志（键值同上，不含个人信息）。
- 客户系统应记录 `jti`、`sub`、工具名和结果状态，两边可按 `jti` 对账。

## 9. 最小样例：门店库存查询

工具定义（`tools/list` 的一项）：

```json
{
  "name": "get_store_inventory",
  "title": "门店库存查询",
  "description": "按门店和商品编码查询当前库存数量。只读；不能查询未授权的门店，不能修改库存。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "store_id": { "type": "string", "description": "门店编号，如 S001" },
      "sku": { "type": "string", "description": "商品编码；不填则返回该门店库存最低的商品" },
      "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 50 }
    },
    "required": ["store_id"],
    "additionalProperties": false
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "data": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "store_id": { "type": "string" },
            "sku": { "type": "string" },
            "name": { "type": "string" },
            "quantity": { "type": "integer" }
          },
          "required": ["store_id", "sku", "quantity"]
        }
      },
      "source": {
        "type": "object",
        "properties": { "system": { "type": "string" }, "dataset": { "type": "string" } },
        "required": ["system", "dataset"]
      },
      "query": { "type": "object" },
      "asOf": { "type": "string", "format": "date-time" },
      "queriedAt": { "type": "string", "format": "date-time" },
      "truncated": { "type": "boolean" }
    },
    "required": ["data", "source", "query", "asOf", "queriedAt", "truncated"]
  },
  "annotations": { "readOnlyHint": true }
}
```

请求（令牌 `data_scope.stores` 含 `S001`）：

```http
POST /mcp HTTP/1.1
Authorization: Bearer <JWT：aud=https://erp.example.com/mcp, tool=get_store_inventory, data_scope={"stores":["S001"]}>
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2026-07-28
Mcp-Method: tools/call
Mcp-Name: get_store_inventory

{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "get_store_inventory",
    "arguments": { "store_id": "S001", "sku": "A1001" },
    "_meta": {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientInfo": { "name": "rag-pilot", "version": "1.0.0" },
      "io.modelcontextprotocol/clientCapabilities": {}
    }
  }
}
```

成功响应：

```json
{
  "jsonrpc": "2.0", "id": 1,
  "result": {
    "resultType": "complete",
    "content": [{ "type": "text", "text": "{\"data\":[{\"store_id\":\"S001\",\"sku\":\"A1001\",\"name\":\"鲜牛奶 1L\",\"quantity\":24}],\"source\":{\"system\":\"ERP\",\"dataset\":\"store_inventory\"},\"query\":{\"store_id\":\"S001\",\"sku\":\"A1001\",\"limit\":50},\"asOf\":\"2026-11-07T09:30:00+08:00\",\"queriedAt\":\"2026-11-07T09:30:02+08:00\",\"truncated\":false}" }],
    "structuredContent": {
      "data": [{ "store_id": "S001", "sku": "A1001", "name": "鲜牛奶 1L", "quantity": 24 }],
      "source": { "system": "ERP", "dataset": "store_inventory" },
      "query": { "store_id": "S001", "sku": "A1001", "limit": 50 },
      "asOf": "2026-11-07T09:30:00+08:00",
      "queriedAt": "2026-11-07T09:30:02+08:00",
      "truncated": false
    },
    "isError": false
  }
}
```

回答引用示例：“鲜牛奶 1L 在 S001 店库存 24（来源：ERP·store_inventory，条件：门店 S001、商品 A1001，数据截至 2026-11-07 09:30）。”

范围外（令牌只含 `S001`，参数是 `S002`——正常情况下我方调用前就已拒绝，这是客户系统的第二道校验）：

```json
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "resultType": "complete",
    "content": [{ "type": "text", "text": "无权查询门店 S002。" }],
    "structuredContent": { "code": "out_of_scope", "message": "无权查询门店 S002。" },
    "isError": true
  }
}
```

令牌过期：`HTTP 401`，`WWW-Authenticate: Bearer error="invalid_token"`。限流：`HTTP 429`，`Retry-After: 5`。

## 10. 出处

- MCP 规范 2026-07-28：[传输](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports)、[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)、[工具](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)、[授权](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)、[安全最佳实践](https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices)（受众校验、禁止令牌透传、状态句柄绑定用户、范围最小化）。
- Spring AI 2.0：[MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)（Streamable HTTP、`request-timeout` 默认 20s、`McpSyncHttpClientRequestCustomizer`、工具过滤 `McpToolFilter`）。注意其自动配置的 client 是应用级单例，`initialize` 和 `tools/list` 在连接时用服务身份，所以每次调用的用户令牌要在请求层（customizer）注入，#168 验证。
- 令牌格式：[RFC 9068](https://www.rfc-editor.org/rfc/rfc9068.html)（JWT 访问令牌）、[RFC 8707](https://www.rfc-editor.org/rfc/rfc8707.html)（resource / 受众）。
