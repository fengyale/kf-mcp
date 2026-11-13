---
name: in-db-mcp
description: 多库只读数据库 MCP Server，支持 Oracle / SQL Server / MySQL，应用层强制只读 + 操作审计。10 个工具：查数据、按字段名搜全库、看索引/慢 SQL/会话/执行计划/大表排行。当用户需要让 AI 实时查询分析数据库、排查慢 SQL 时使用。支持 Oracle 是其核心差异化能力（市面 DB 类 MCP 大多不支持 Oracle）。
---

# in-db-mcp — 多库只读数据库 MCP Server

基于 Java 的 MCP Server，让 AI 助手（Trae / CodeBuddy / Cursor / Claude Code 等）能够**实时查询与分析**数据库。单 jar 零部署，内置各库 JDBC 驱动，**无需安装 Oracle Instant Client / ODBC**，只要有 JDK 8+。

## 核心特性

- **一个服务、三库通吃**：Oracle / SQL Server / MySQL，同一套工具无缝切换，多数据源清单式配置
- **强制只读**：仅允许 `SELECT` / `WITH`；双词法（标准 SQL / MySQL）扫描，拦截多语句、块注释/`#`/`--` 注释绕过、反斜杠字符串边界绕过、`FOR UPDATE`、`INTO OUTFILE`、`SLEEP()`/`BENCHMARK()`、`LOAD_FILE()`、Oracle `UTL_*`/`DBMS_*` 危险包、SQL Server `xp_cmdshell`/`OPENROWSET`、`@dblink` 跨库外连等；行数上限（默认 200，最大 1000）+ 20s 超时 + 输出大小封顶；fastjson 关闭 autoType
- **不止查数，还能诊断**：找字段、看索引、TOP 慢 SQL、实时会话、执行计划、大表排行
- **全操作审计**：每次调用自动落盘 `logs/audit.log`（时间/工具/数据源/耗时/结果），满足内网合规追溯
- **凭据灵活安全**：`pass` 支持明文或 `${环境变量}` 两种写法，密码可不落盘
- **零改造接入**：改数据库连接只改 `datasources.json`，不用重新打包；性能视图无权限时友好降级不崩溃

> ⚠️ **安全边界（必读）**：应用层只读是第一道防线，**不能替代数据库授权**。有一类写操作无法靠关键字识别——Oracle 中带 `PRAGMA AUTONOMOUS_TRANSACTION` 的自定义函数在 `SELECT f()` 内可执行真实 DML。因此**连接生产库时，数据库账号本身必须是只读账号**（只授 SELECT），形成"应用层拦截 + 最小权限账号"双保险。只读规则对抗用例见 `readonly-tests.txt`（77 条）。

## 提供的工具（10 个）

| 工具 | 说明 |
|---|---|
| `list_datasources` | 列出所有可用数据源 |
| `query` | 执行只读 SQL（仅 `SELECT`/`WITH`），参数：`datasource`、`sql`、`limit` |
| `list_tables` | 列出表（可按 `keyword` 过滤，含注释） |
| `describe_table` | 表结构：字段/类型/可空/**主键**/**默认值**/注释 |
| `search_column` | **按字段名搜全库**：该字段出现在哪些表（写 SQL/mapper 神器） |
| `list_indexes` | 查看表索引（名称/唯一性/字段/顺序） |
| `slow_sql_top` | TOP 慢 SQL（`order_by`：elapsed/cpu/executions） |
| `active_sessions` | 当前正在执行的会话 / SQL（实时排查卡顿） |
| `explain_sql` | SQL 执行计划 |
| `table_size_top` | 表和索引占用空间 TOP N（含行数），找大表 |

> 性能类工具依赖 `v$sql` / `v$session` / `dm_exec_*` / `performance_schema` 等视图权限（DBA / VIEW SERVER STATE），无权限时返回友好提示，不影响其它功能。

## 视图与存储过程说明

**视图**：
- 查询没问题——`SELECT * FROM 视图名` 正常执行，`describe_table` 也能查出视图的字段结构
- 但 `list_tables` 只列实体表，**不会列出视图**，需要自己知道视图名

**存储过程**：
- **不支持执行**：语句必须以 `SELECT` / `WITH` 开头，`CALL` / `EXEC` 一律拒绝（无法静态判断过程内部有无写操作，一刀切）
- 想**查看**过程定义可以查数据字典：

```sql
SELECT text FROM all_source WHERE name = '过程名' ORDER BY line
```

## 安装（3 步）

1. 把本目录整体拷到本机，如 `D:/workspace/ai-tools-project/kf-mcp/in-db-mcp/`
2. 复制 `datasources.json.example` 为 `datasources.json`，填入真实数据库连接信息（`pass` 推荐写 `${环境变量}`，避免明文落盘）
3. 在 AI 客户端的 MCP 配置里加入（本目录的 `mcp.json` 是成品示例，可直接复制）：

```json
{
  "mcpServers": {
    "in-db": {
      "command": "java",
      "args": ["-jar", "<替换为 dbmcp.jar 的绝对路径>"]
    }
  }
}
```

> `command` 写 `java` 需要在系统 PATH 中；否则写 java.exe 绝对路径。改完配置**重启客户端**生效。

## 各客户端配置文件位置

| 客户端 | 配置文件 |
|---|---|
| Trae CN | `%APPDATA%\Trae CN\User\mcp.json` |
| CodeBuddy | `~/.codebuddy/mcp.json` |
| Cursor | `~/.cursor/mcp.json` |
| Claude Code | `~/.claude.json` 或项目 `.mcp.json` |

## 目录说明

```
in-db-mcp/
├── dbmcp.jar                  # 程序（内含全部依赖，含各库 JDBC 驱动）
├── datasources.json.example   # 数据源配置模板（复制为 datasources.json 后填写）
├── mcp.json                   # 客户端 MCP 配置成品示例
├── readonly-tests.txt         # 只读规则回归测试用例
├── src/DbMcpServer.java       # 完整源码（可自行修改重打包）
└── README.md                  # 详细文档（凭据写法、审计日志、各库数据来源、FAQ、构建方法）
```

详细说明见 [README.md](README.md)。
