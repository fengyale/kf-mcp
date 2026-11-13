# in-db-mcp — 多库只读数据库 MCP Server（v2.1.0）

一个基于 **Java** 的 MCP (Model Context Protocol) Server，让 AI 助手（CodeBuddy / Trae / Cursor / Claude Code 等）能够**实时查询与分析**数据库（Oracle / SQL Server / MySQL）。

> **只读安全**：仅允许 `SELECT` / `WITH` 查询，`INSERT` / `UPDATE` / `DELETE` / DDL 等一律拒绝。

---

## ⭐ 核心优势

| 优势 | 说明 |
|---|---|
| **零客户端依赖** | 内置各库驱动（Oracle thin / mssql / mysql），**无需安装 Oracle Instant Client、ODBC 等**，只要有 JDK 8 就能跑 |
| **一个服务、三库通吃** | 同一套工具无缝支持 **Oracle / SQL Server / MySQL**，不用为每种库换一套工具 |
| **只读安全护栏** | 强制 `SELECT`/`WITH` 白名单；**双词法扫描**（标准 SQL / MySQL）拦截多语句、`/*/**/`/`#`/`--` 注释绕过、反斜杠字符串边界绕过、`FOR UPDATE`、`INTO OUTFILE`、`SLEEP()`/`BENCHMARK()`、`LOAD_FILE()`、Oracle `UTL_*`/`DBMS_*` 危险包、SQL Server `xp_cmdshell`/`OPENROWSET`、`@dblink` 跨库外连等；行数上限 + 20s 超时 + 输出封顶；fastjson 关闭 autoType |
| **不止查数，还能诊断** | 找字段、看索引、看表结构（含主键/默认值）、**TOP 慢 SQL**、**实时会话**、**执行计划**、**大表排行** |
| **跨客户端通用** | 标准 MCP 协议，CodeBuddy / Trae / Cursor / Claude Code 等**同一份配置**即插即用 |
| **凭据灵活安全** | 支持明文或 `${环境变量}` 两种写法，密码可不落盘 |
| **全操作审计** | 每次调用自动落盘（时间 / 工具 / 数据源 / 耗时 / 结果），满足企业内网合规追溯 |
| **零改造接入** | 改数据库连接**只改一个 json**、不用重新打包；无权限时**友好降级**、不崩溃 |

---

## 1. 环境要求

- **JDK 8 或更高**（命令行能执行 `java -version` 即可）
- 所在网络能访问目标数据库

## 2. 快速开始

1. 从技能库获取本目录（`通用技能/in-db-mcp/`），整体拷到本机任意位置，例如 `D:/workspace/ai-tools-project/kf-mcp/in-db-mcp/`
2. 复制 **`datasources.json.example`** 为 **`datasources.json`**，填写数据库连接信息（`pass` 推荐用 `${环境变量}` 写法，避免密码明文落盘）
3. 在 AI 客户端的 MCP 配置文件里加入下面这段（路径指向**你目录里的 `dbmcp.jar`**）：
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
   （注意把 `-jar` 后面的路径换成**你实际目录里 `dbmcp.jar` 的绝对路径**）
4. **重启客户端**。

目录结构（技能库中本技能包含的文件）：
```
in-db-mcp/
  dbmcp.jar                    程序（java -jar 直接运行，无需解压）
  datasources.json.example     数据库配置模板（复制为 datasources.json 后填写）
  datasources.json             【自行创建，不入库】实际数据库配置，含密码切勿提交
  mcp.json                     客户端 MCP 配置成品示例
  SKILL.md                     技能入口说明
  README.md                    本说明
  readonly-tests.txt           只读规则回归用例 77 条（java -jar dbmcp.jar --check-file readonly-tests.txt）
  src/
    DbMcpServer.java           源码（仅重新编译时需要）
  logs/audit.log               【运行时自动生成，不入库】操作审计日志
```

> 💡 自行重打包时会产生构建产物（`db-mcp-release.zip`、`stage-*`、`dist/` 等），均不需要提交，构建方法见第 9 节。

## 3. 配置文件说明（datasources.json）

```json
{
  "audit_log": "",
  "datasources": [
    {
      "name": "oracle_main",
      "type": "oracle",
      "desc": "Oracle 主库",
      "url": "jdbc:oracle:thin:@//HOST:1521/SERVICE_NAME",
      "user": "YOUR_USER",
      "pass": "YOUR_PASSWORD"
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `name` | 数据源名称（调用工具时使用） |
| `type` | `oracle` / `sqlserver` / `mysql` |
| `desc` | 描述（`list_datasources` 会显示） |
| `url` | JDBC 连接串 |
| `user` / `pass` | 账号 / 密码 |
| `audit_log`（顶层，可选） | 审计日志文件路径；留空则默认 `程序目录/logs/audit.log` |

### 🔐 凭据两种写法（可混用，自己选）
```json
"pass": "明文密码"            // ① 直接写明文
"pass": "${ORACLE_PWD}"      // ② 写 ${变量名}，运行时从环境变量取
```
- 写法 ② 时，环境变量可来自：**系统环境变量**，或**客户端 `mcp.json` 的 `env` 字段**注入
- 变量未设置时返回友好错误，不会崩溃
- `user` 字段同样支持这两种写法

**JDBC 连接串示例**
- Oracle：`jdbc:oracle:thin:@//HOST:1521/SERVICE`
- SQL Server：`jdbc:sqlserver://HOST:1433;DatabaseName=DB`
- MySQL：`jdbc:mysql://HOST:3306/DB?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai`

**只配一个库也没问题**：配置只是「清单」，连接是「用到才连」。

**配置查找顺序**：① 环境变量 `DBMCP_CONFIG` → ② 程序同目录 `datasources.json` → ③ `~/.codebuddy/in-db-mcp/datasources.json`

## 4. 工具清单（10 个）

| 工具 | 说明 |
|---|---|
| `list_datasources` | 列出所有可用数据源 |
| `query` | 执行**只读** SQL（仅 `SELECT`/`WITH`），参数 `datasource`、`sql`、`limit` |
| `list_tables` | 列出表（可按 `keyword` 过滤，含注释） |
| `describe_table` | 表结构：字段/类型/可空/**主键**/**默认值**/注释 |
| `search_column` | **按字段名搜全库**：该字段出现在哪些表（写 SQL/mapper 神器） |
| `list_indexes` | 查看表索引（名称/唯一性/字段/顺序） |
| `slow_sql_top` | TOP 慢 SQL（`order_by`：elapsed/cpu/executions） |
| `active_sessions` | 当前正在执行的会话 / SQL（实时排查卡顿） |
| `explain_sql` | SQL 执行计划 |
| `table_size_top` | 表和索引占用空间 TOP N（含行数），找大表 |

### 各库数据来源
| 工具 | Oracle | MySQL | SQL Server |
|---|---|---|---|
| `slow_sql_top` | `v$sql` | `performance_schema` | `sys.dm_exec_query_stats` |
| `active_sessions` | `v$session`+`v$sql` | `INFORMATION_SCHEMA.PROCESSLIST` | `sys.dm_exec_requests` |
| `explain_sql` | `EXPLAIN PLAN`（**会写 PLAN_TABLE 并立即回滚**） | `EXPLAIN`（纯只读） | `SET SHOWPLAN_ALL`（**不执行**语句） |
| `table_size_top` | `USER_SEGMENTS`+`USER_TABLES` | `INFORMATION_SCHEMA.TABLES` | `sys.partitions` |

> ⚠️ 性能类视图（`v$sql`/`v$session`/`dm_exec_*`/`performance_schema`）**需要相应权限**（DBA / VIEW SERVER STATE 等）。无权限时返回友好提示，不影响其它功能。
> `EXPLAIN PLAN` 是 Oracle 生成执行计划的标准机制，会写 `PLAN_TABLE`，本工具执行后**立即 `ROLLBACK`**。

## 5. 审计日志

所有工具调用都会追加记录到 `logs/audit.log`（可用 `audit_log` 配置改路径）：
```
2026-09-22 21:16:35 | OK  | search_column | oracle_main | 357ms | DRUG
2026-09-22 21:16:35 | ERR | query         | his_mssql   | 20ms  | SELECT ... | ERR: 权限不足
```
格式：`时间 | 结果 | 工具 | 数据源 | 耗时 | 详情[ | 错误]`

## 6. 客户端配置位置

| 客户端 | 配置文件 |
|---|---|
| CodeBuddy | `~/.codebuddy/mcp.json` |
| Trae CN | `%APPDATA%\Trae CN\User\mcp.json` |
| Cursor | `~/.cursor/mcp.json` |
| Claude Code | `~/.claude.json` 或项目 `.mcp.json` |

## 7. 安全说明

- 应用层**强制只读**：非 `SELECT`/`WITH` 直接拒绝；**双词法扫描**（标准 SQL / MySQL 两套清理规则取并集），先剥离字符串与注释再校验，拦截 `/*/**/`、`#`、`--`（MySQL 中 `--` 后无空白不算注释）等注释绕过、反斜杠字符串边界绕过、`/*!...*/` 可执行注释、多语句、`FOR UPDATE`、`INTO OUTFILE/DUMPFILE`、`SELECT ... INTO`、`SLEEP()`/`BENCHMARK()`、`LOAD_FILE()`、Oracle `UTL_*`/`DBMS_*` 危险包、SQL Server `xp_cmdshell`/`OPENROWSET`/`OPENQUERY`、`@dblink` 跨库外连（`@@` 系统变量属只读诊断，放行）
- 返回行数受限（默认 200，最大 1000）、查询超时 20 秒、单次输出大小封顶（512KB）
- fastjson 开启 safeMode，禁用 autoType 反序列化
- 畸形消息不会导致进程退出，单条异常不影响服务
- **连接生产库时数据库账号本身必须是只读账号（只授 SELECT）**：应用层只读是第一道防线，**不能替代数据库授权**——Oracle 中带 `PRAGMA AUTONOMOUS_TRANSACTION` 的自定义函数在 `SELECT f()` 内可执行真实 DML，无法靠关键字静态识别，必须用最小权限账号兜底
- 只读规则对抗回归用例见 `readonly-tests.txt`（77 条：16 ALLOW / 61 DENY）
- 凭据支持环境变量方式，避免明文落盘

## 8. 常见问题

- **连不上库**：确认网络可达（端口通）、JDBC URL 与账号密码正确
- **MySQL 8 认证报错**：URL 带 `allowPublicKeyRetrieval=true&useSSL=false`
- **性能工具提示“表或视图不存在”**：该账号缺少 `v$sql` / `dm_exec_*` / `performance_schema` 权限，请联系 DBA 授权
- **改了 `datasources.json` 不生效**：确认客户端已**重启**
- **报“找不到或无法加载主类”**：自行重打包时务必排除依赖中的 `META-INF/*.SF`、`*.RSA`、`META-INF/INDEX.LIST`

## 9. 从源码重新构建（可选）

```bash
javac -encoding UTF-8 -cp <fastjson.jar> -d classes DbMcpServer.java
java -cp "classes;<fastjson.jar>;<ojdbc6.jar>;<mssql-jdbc.jar>;<mysql-connector.jar>" DbMcpServer
# 只读规则回归测试
java -cp classes;<fastjson.jar> DbMcpServer --check-file readonly-tests.txt
```
