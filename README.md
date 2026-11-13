# kf-mcp

一组面向内网环境的自研 MCP Server，让 AI 客户端能直接操作数据库、缓存和代码平台。

[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://www.oracle.com/java/) [![Python](https://img.shields.io/badge/Python-3.x-blue)](https://www.python.org/) [![Node.js](https://img.shields.io/badge/Node.js-14%2B-green)](https://nodejs.org/) [![MCP](https://img.shields.io/badge/protocol-MCP-purple)](https://modelcontextprotocol.io/) [![License](https://img.shields.io/badge/license-unlicensed-lightgrey)](#许可证)

三个子项目彼此独立，可单独拷走使用，也可同时接入同一个 MCP 客户端（Trae / CodeBuddy / Cursor / Claude Code 等）。

## 子项目一览

| 项目 | 语言 | 能力 | 默认安全策略 | 依赖 |
|---|---|---|---|---|
| [in-db-mcp](#in-db-mcp) | Java | 只读查询 Oracle / SQL Server / MySQL，10 个工具 | 仅允许 `SELECT` / `WITH`，双词法扫描拦截绕过 | JDK 8+，单 jar 内置驱动 |
| [in-redis-mcp](#in-redis-mcp) | Python | Redis 读写与诊断，10 个工具 | `redis_flushdb` 需显式 `confirm="yes"` | `mcp>=1.0.0`、`redis>=5.0.0` |
| [in-gitlab-mcp](#in-gitlab-mcp) | Node.js | GitLab 项目 / 文件 / 提交 / Issue / MR / 流水线，21 个工具 | 默认只读，仅下发 14 个只读工具 | 无第三方依赖（Node 标准库） |

---

## in-db-mcp

**解决什么问题**：让 AI 助手实时查询与分析内网数据库，无需安装 Oracle Instant Client 或 ODBC，一个 `dbmcp.jar` 覆盖三种数据库；除查数外还能做慢 SQL、会话、执行计划、大表排行等诊断。

**核心工具（10 个）**

| 工具 | 说明 |
|---|---|
| `list_datasources` | 列出所有可用数据源 |
| `query` | 执行只读 SQL（仅 `SELECT`/`WITH`） |
| `list_tables` | 列出表，可按关键字过滤 |
| `describe_table` | 表结构（字段/类型/可空/主键/默认值/注释） |
| `search_column` | 按字段名搜全库，定位该字段出现在哪些表 |
| `list_indexes` | 查看表索引 |
| `slow_sql_top` | TOP 慢 SQL（`order_by`：elapsed/cpu/executions） |
| `active_sessions` | 当前正在执行的会话 / SQL |
| `explain_sql` | SQL 执行计划 |
| `table_size_top` | 表和索引占用空间 TOP N |

**最小配置**

复制 `datasources.json.example` 为 `datasources.json`（不入库），密码建议写环境变量：

```json
{
  "datasources": [
    {
      "name": "oracle_main",
      "type": "oracle",
      "url": "jdbc:oracle:thin:@//<HOST>:1521/<SERVICE>",
      "user": "<USER>",
      "pass": "${ORACLE_PWD}"
    }
  ]
}
```

客户端注册（同 `in-db-mcp/mcp.json`）：

```json
{
  "mcpServers": {
    "in-db": {
      "command": "java",
      "args": ["-jar", "<dbmcp.jar 绝对路径>"],
      "env": {}
    }
  }
}
```

**环境要求**：JDK 8 或更高，所在网络能访问目标数据库；`dbmcp.jar` 已内置 Oracle thin / mssql / mysql 驱动。

---

## in-redis-mcp

**解决什么问题**：在本地开发和联调时，让 AI 客户端直接读写、诊断 Redis，而不必手工敲 `redis-cli`。

**核心工具（10 个）**：`redis_ping`、`redis_get`、`redis_set`、`redis_delete`、`redis_scan`（SCAN 非 KEYS）、`redis_ttl`、`redis_type`、`redis_keysize`、`redis_info`、`redis_flushdb`（需 `confirm="yes"`）。

**最小配置**（源自 `server.py` 顶部文档字符串）：

```json
{
  "mcpServers": {
    "in-redis": {
      "command": "<python.exe 绝对路径>",
      "args": ["<server.py 绝对路径>"],
      "cwd": "<工作目录>",
      "env": {
        "REDIS_URL": "redis://:<password>@<host>:6379/0"
      }
    }
  }
}
```

连接方式优先级：`REDIS_URL` 优先；否则读取 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DB`。

**环境要求**：Python 3，安装 `pip install -r requirements.txt`（`mcp>=1.0.0`、`redis>=5.0.0`）。

---

## in-gitlab-mcp

**解决什么问题**：在完全离线的内网机器上，让 AI 读取内网 GitLab 的代码、提交、MR diff 与流水线；零 npm 依赖，拷过去即可运行，并已适配老版本 GitLab 11.9.9 的已知差异。

**核心工具（21 个，默认只读下发 14 个；🔒 为需 `GITLAB_READONLY=0` 才出现的写工具）**

| 分类 | 工具 |
|---|---|
| 项目 / 成员 | `gitlab_list_projects`、`gitlab_get_project`、`gitlab_list_project_members` |
| 仓库 / 文件 | `gitlab_get_file`、`gitlab_search_in_project`、`gitlab_list_branches` |
| 提交 | `gitlab_list_commits`、`gitlab_get_commit` |
| Issue | `gitlab_list_issues`、`gitlab_get_issue` |
| MR | `gitlab_list_merge_requests`、`gitlab_get_merge_request`、`gitlab_get_merge_request_changes` |
| CI/CD | `gitlab_list_pipelines` |
| 🔒 写-仓库 | `gitlab_create_branch`、`gitlab_create_or_update_file`、`gitlab_push_files` |
| 🔒 写-Issue | `gitlab_create_issue` |
| 🔒 写-MR | `gitlab_create_merge_request`、`gitlab_create_merge_request_note`、`gitlab_merge_merge_request` |

**最小配置**（同 `in-gitlab-mcp/mcp-servers.snippet.json`）：

```json
{
  "mcpServers": {
    "in-gitlab": {
      "command": "node",
      "args": ["<server.js 绝对路径>"],
      "env": {
        "GITLAB_API_URL": "http://<gitlab-host>/api/v4",
        "GITLAB_PERSONAL_ACCESS_TOKEN": "<token>",
        "GITLAB_TIMEOUT_MS": "30000",
        "GITLAB_READONLY": "1"
      }
    }
  }
}
```

也可复制 `gitlab-mcp.env.example` 为 `gitlab-mcp.env`，放在 `server.js` 同目录自动加载。

**环境要求**：Node 14+（已验证 Node v24.20.0），无需 `npm install`；一个 scope 为 `read_api` 的 Personal Access Token。

---

## 快速开始

1. **拷目录**：把需要的子项目目录整体拷到本机任意位置。
2. **配环境变量**：数据库密码、Redis 密码、GitLab token 统一走环境变量或客户端 `env` 字段，不写进仓库。
3. **注册客户端**：把上面对应的 `mcpServers` 片段合并到客户端 MCP 配置文件，路径替换为绝对路径，然后**重启客户端**。

常见客户端配置文件位置：Trae CN `%APPDATA%\Trae CN\User\mcp.json`；CodeBuddy `~/.codebuddy/mcp.json`；Cursor `~/.cursor/mcp.json`；Claude Code `~/.claude.json` 或项目 `.mcp.json`；Trae 项目内 `.trae/mcp.json`。

## 目录结构

```
kf-mcp/
├── in-db-mcp/
│   ├── dbmcp.jar                  # 可执行程序（内含各库驱动）
│   ├── datasources.json.example   # 数据源配置模板（复制为 datasources.json）
│   ├── mcp.json                   # 客户端配置成品示例
│   ├── readonly-tests.txt         # 只读规则回归用例
│   ├── README.md / SKILL.md
│   └── src/DbMcpServer.java       # 源码
├── in-redis-mcp/
│   ├── server.py                  # 服务主体
│   └── requirements.txt
├── in-gitlab-mcp/
│   ├── server.js                  # 服务主体，唯一必需文件
│   ├── gitlab-mcp.env.example     # 配置模板（复制为 gitlab-mcp.env）
│   ├── mcp-servers.snippet.json   # 客户端配置片段
│   ├── start.cmd / start.sh       # 手动启动脚本（调试用）
│   ├── package.json
│   └── README.md / SKILL.md
└── .gitignore
```

## 安全说明

- **in-gitlab-mcp 默认只读**：仅下发 14 个只读工具，7 个写工具需显式设置 `GITLAB_READONLY=0`。该开关只是客户端侧防呆，真正的安全边界是 token 使用 `read_api` scope——服务端会直接拒绝写操作。
- **in-db-mcp 只允许 `SELECT` / `WITH`**：其余语句一律拒绝；行数上限（默认 200，最大 1000）、20 秒超时、输出封顶。应用层拦截不能替代数据库授权，连接生产库请使用只读账号。
- **in-redis-mcp 的 `redis_flushdb` 需 `confirm="yes"`**：不传确认参数不会执行清库；`redis_scan` 使用 SCAN 而非 KEYS。
- **凭据一律走环境变量**：`${ORACLE_PWD}` 写法、客户端 `env`、或同目录 `.env` 文件。
- **不要提交** `gitlab-mcp.env`、`datasources.json` 及任何真实 token / 密码；`.gitignore` 已忽略这两类文件，模板 `.example` 保留入库。

## 许可证

三个子目录均未包含 `LICENSE` 文件，本仓库暂未声明许可证（`in-gitlab-mcp/package.json` 中 `license` 字段为 `KF`）。如需对外分发，请先确认授权范围。
