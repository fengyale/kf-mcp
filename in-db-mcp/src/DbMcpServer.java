import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.ParserConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 多库只读数据库 MCP Server (stdio, JSON-RPC 2.0)
 * 支持 Oracle / SQL Server / MySQL，仅允许只读查询。
 * 依赖: fastjson, ojdbc6, mssql-jdbc, mysql-connector-j
 *
 * 安全修正:
 *   - 只读校验前先剥离字符串与注释(块注释/行注释)，杜绝 INTO/**\/OUTFILE 这类绕过
 *   - fastjson 开启 safeMode，禁用 autoType
 *   - 单条消息异常不再导致进程退出
 *   - MySQL 分支对反斜杠做转义
 *   - 单元格与总输出大小封顶
 */
public class DbMcpServer {

    static JSONArray datasources = new JSONArray();
    static Map<String, JSONObject> dsMap = new LinkedHashMap<String, JSONObject>();
    static String auditLogPath = null;
    static final int CELL_MAX = 2000;          // 单元格最大字符
    static final int ROUGH_MAX = 8000;         // 单格先粗截断，避免大 CLOB 处理
    static final int OUTPUT_MAX = 512 * 1024;  // 单次输出字符上限

    public static void main(String[] args) throws Exception {
        // 安全加固: 关闭 fastjson autoType(多态)，解析普通 JSON 不需要多态支持
        try {
            ParserConfig.getGlobalInstance().setSafeMode(true);
        } catch (Throwable ignore) {
        }
        // 自测模式(不连库): java DbMcpServer --check-file <file>
        if (args.length >= 2 && "--check-file".equals(args[0])) {
            List<String> lines = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
            for (String line : lines) {
                String raw = line.trim();
                if (raw.isEmpty() || raw.startsWith("#")) continue;
                // 测试模式: 支持 \n 转义，便于验证多行 payload
                String t = raw.replace("\\n", "\n");
                try {
                    checkReadOnly(t);
                    System.out.println("ALLOW | " + t);
                } catch (Exception e) {
                    System.out.println("DENY  | " + t + "   [" + e.getMessage() + "]");
                }
            }
            return;
        }
        loadConfig();
        tryLoad("oracle.jdbc.OracleDriver");
        tryLoad("oracle.jdbc.driver.OracleDriver");
        tryLoad("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        tryLoad("com.mysql.cj.jdbc.Driver");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");

        String line;
        while ((line = in.readLine()) != null) {
            try {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject req;
                try {
                    req = JSON.parseObject(line);
                } catch (Throwable e) {
                    continue;
                }
                if (req == null || req.isEmpty()) continue;
                String method = req.getString("method");
                if (method == null) continue;
                Object id = req.get("id");
                if (method.startsWith("notifications/")) continue;
                try {
                    if ("initialize".equals(method)) {
                        JSONObject p = req.getJSONObject("params");
                        String pv = (p != null) ? p.getString("protocolVersion") : null;
                        if (pv == null || pv.isEmpty()) pv = "2024-11-05";
                        JSONObject result = new JSONObject(true);
                        result.put("protocolVersion", pv);
                        JSONObject caps = new JSONObject(true);
                        caps.put("tools", new JSONObject());
                        result.put("capabilities", caps);
                        JSONObject si = new JSONObject(true);
                        si.put("name", "in-db-mcp");
                        si.put("version", "2.1.0");
                        result.put("serverInfo", si);
                        respond(out, id, result);
                    } else if ("ping".equals(method)) {
                        respond(out, id, new JSONObject());
                    } else if ("tools/list".equals(method)) {
                        respond(out, id, buildToolsList());
                    } else if ("tools/call".equals(method)) {
                        JSONObject p = req.getJSONObject("params");
                        String name = (p != null) ? p.getString("name") : null;
                        JSONObject a = (p != null) ? p.getJSONObject("arguments") : null;
                        if (a == null) a = new JSONObject();
                        long t0 = System.currentTimeMillis();
                        try {
                            String text = callTool(name, a);
                            audit(name, a.getString("datasource"), detailOf(a), System.currentTimeMillis() - t0, true, null);
                            respond(out, id, toolResult(text, false));
                        } catch (Throwable ex) {
                            audit(name, a.getString("datasource"), detailOf(a), System.currentTimeMillis() - t0, false, ex.getMessage());
                            respond(out, id, toolResult("错误: " + ex.getMessage(), true));
                        }
                    } else {
                        error(out, id, -32601, "Method not found: " + method);
                    }
                } catch (Throwable ex) {
                    try { error(out, id, -32603, "Internal error: " + ex.getMessage()); } catch (Throwable ig) {}
                }
            } catch (Throwable t) {
                // 单条消息异常不致命，继续处理后续消息
            }
        }
    }

    static String detailOf(JSONObject a) {
        if (a == null) return "";
        String v = a.getString("sql");
        if (v != null) return v;
        v = a.getString("table");
        if (v != null) return v;
        v = a.getString("column_keyword");
        if (v != null) return v;
        return "";
    }

    /**
     * 配置查找优先级:
     *   1) 环境变量 DBMCP_CONFIG 指定的路径
     *   2) 程序(jar/classes)同目录下的 datasources.json
     *   3) 默认路径 ~/.codebuddy/in-db-mcp/datasources.json
     */
    static void loadConfig() {
        String cfgPath = null;
        String env = System.getenv("DBMCP_CONFIG");
        if (env != null && !env.trim().isEmpty() && Files.exists(Paths.get(env.trim()))) {
            cfgPath = env.trim();
        }
        String appDir = getAppDir();
        if (cfgPath == null && appDir != null) {
            Path p = Paths.get(appDir, "datasources.json");
            if (Files.exists(p)) cfgPath = p.toString();
        }
        if (cfgPath == null) {
            cfgPath = Paths.get(System.getProperty("user.home"), ".codebuddy", "in-db-mcp", "datasources.json").toString();
        }
        try {
            if (Files.exists(Paths.get(cfgPath))) {
                String txt = new String(Files.readAllBytes(Paths.get(cfgPath)), StandardCharsets.UTF_8);
                JSONObject cfg = JSON.parseObject(txt);
                datasources = cfg.getJSONArray("datasources");
                for (int i = 0; i < datasources.size(); i++) {
                    JSONObject d = datasources.getJSONObject(i);
                    dsMap.put(d.getString("name"), d);
                }
                String al = cfg.getString("audit_log");
                if (al == null || al.trim().isEmpty()) {
                    al = (appDir != null ? appDir : System.getProperty("user.home")) + File.separator + "logs" + File.separator + "audit.log";
                }
                auditLogPath = al;
                System.err.println("[in-db-mcp] 已加载配置: " + cfgPath + " (数据源 " + dsMap.size() + " 个, 审计日志 " + auditLogPath + ")");
            } else {
                System.err.println("[in-db-mcp] 未找到配置文件: " + cfgPath);
            }
        } catch (Exception e) {
            System.err.println("[in-db-mcp] 配置读取失败: " + e.getMessage());
        }
    }

    static String getAppDir() {
        try {
            java.security.CodeSource cs = DbMcpServer.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.isFile()) return f.getParentFile().getAbsolutePath();
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
        }
        return null;
    }

    /** 凭据解析: 值为 ${VAR} 形式时取环境变量，否则为明文 */
    static String resolveCred(JSONObject ds, String key) {
        String v = ds.getString(key);
        if (v == null) return null;
        v = v.trim();
        if (v.startsWith("${") && v.endsWith("}")) {
            String envName = v.substring(2, v.length() - 1).trim();
            String ev = System.getenv(envName);
            if (ev == null) {
                throw new RuntimeException("环境变量 " + envName + " 未设置（数据源 " + ds.getString("name") + " 的 " + key + "）");
            }
            return ev;
        }
        return v;
    }

    static void audit(String tool, String ds, String detail, long ms, boolean ok, String err) {
        if (auditLogPath == null) return;
        try {
            Path p = Paths.get(auditLogPath);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()))
              .append(" | ").append(ok ? "OK " : "ERR")
              .append(" | ").append(tool == null ? "-" : tool)
              .append(" | ").append(ds == null ? "-" : ds)
              .append(" | ").append(ms).append("ms");
            if (detail != null && !detail.isEmpty()) sb.append(" | ").append(oneLine(detail));
            if (err != null && !err.isEmpty()) sb.append(" | ERR: ").append(oneLine(err));
            Files.write(p, (sb.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ig) {
        }
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    static void tryLoad(String c) {
        try {
            Class.forName(c);
        } catch (Throwable t) {
        }
    }

    static void respond(PrintStream out, Object id, JSONObject result) {
        JSONObject r = new JSONObject(true);
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", result);
        out.println(r.toJSONString());
    }

    static void error(PrintStream out, Object id, int code, String msg) {
        JSONObject r = new JSONObject(true);
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        JSONObject e = new JSONObject(true);
        e.put("code", code);
        e.put("message", msg);
        r.put("error", e);
        out.println(r.toJSONString());
    }

    static JSONObject toolResult(String text, boolean isError) {
        JSONObject result = new JSONObject(true);
        JSONArray content = new JSONArray();
        JSONObject c = new JSONObject(true);
        c.put("type", "text");
        c.put("text", text);
        content.add(c);
        result.put("content", content);
        if (isError) result.put("isError", true);
        return result;
    }

    static JSONObject buildToolsList() {
        JSONObject result = new JSONObject(true);
        JSONArray tools = new JSONArray();

        tools.add(tool("list_datasources", "列出所有可用的数据库数据源及其说明", schema(new String[][]{})));

        tools.add(tool("query", "对指定数据源执行只读 SQL 查询（仅 SELECT / WITH），返回结果表格",
                schema(new String[][]{{"datasource", "数据源名称"}, {"sql", "只读 SQL（仅 SELECT / WITH）"}, {"limit", "返回最大行数，默认 200，最大 1000"}})));

        tools.add(tool("list_tables", "列出指定数据源的表（可按关键字过滤），实时反映当前结构",
                schema(new String[][]{{"datasource", "数据源名称"}, {"keyword", "可选，表名/注释关键字过滤"}})));

        tools.add(tool("describe_table", "查看表结构：字段名/类型/可空/主键/默认值/注释（实时）",
                schema(new String[][]{{"datasource", "数据源名称"}, {"table", "表名"}})));

        tools.add(tool("search_column", "按字段名搜索全库：该字段出现在哪些表（写 SQL / mapper 神器）",
                schema(new String[][]{{"datasource", "数据源名称"}, {"column_keyword", "字段名关键字（模糊匹配）"}, {"table_keyword", "可选，再按表名过滤"}})));

        tools.add(tool("list_indexes", "查看指定表的索引（名称/唯一性/字段/顺序）",
                schema(new String[][]{{"datasource", "数据源名称"}, {"table", "表名"}})));

        tools.add(tool("slow_sql_top", "查询 TOP 慢 SQL（Oracle v$sql / MySQL performance_schema / SQLServer dm_exec_query_stats）；无权限时返回友好提示",
                schema(new String[][]{{"datasource", "数据源名称"}, {"order_by", "排序依据: elapsed(默认)/cpu/executions"}, {"limit", "返回条数，默认 20，最大 100"}})));

        tools.add(tool("active_sessions", "当前正在执行的会话/SQL（实时排查卡顿）",
                schema(new String[][]{{"datasource", "数据源名称"}})));

        tools.add(tool("explain_sql", "查看 SQL 执行计划（MySQL EXPLAIN / SQLServer SHOWPLAN / Oracle EXPLAIN PLAN）",
                schema(new String[][]{{"datasource", "数据源名称"}, {"sql", "只读 SQL（仅 SELECT / WITH）"}})));

        tools.add(tool("table_size_top", "表和索引占用空间 TOP N（含行数估算），用于找大表",
                schema(new String[][]{{"datasource", "数据源名称"}, {"limit", "返回条数，默认 30，最大 200"}})));

        result.put("tools", tools);
        return result;
    }

    static JSONObject schema(String[][] props) {
        JSONObject s = new JSONObject(true);
        s.put("type", "object");
        JSONObject ps = new JSONObject(true);
        for (String[] kv : props) ps.put(kv[0], prop(kv[0].equals("limit") ? "integer" : "string", kv[1]));
        s.put("properties", ps);
        if (props.length > 0) s.put("required", arr(props[0][0]));
        return s;
    }

    static JSONObject prop(String type, String desc) {
        JSONObject o = new JSONObject(true);
        o.put("type", type);
        o.put("description", desc);
        return o;
    }

    static JSONArray arr(String... items) {
        JSONArray a = new JSONArray();
        for (String s : items) a.add(s);
        return a;
    }

    static JSONObject tool(String name, String desc, JSONObject schema) {
        JSONObject t = new JSONObject(true);
        t.put("name", name);
        t.put("description", desc);
        t.put("inputSchema", schema);
        return t;
    }

    static String callTool(String name, JSONObject a) throws Exception {
        if ("list_datasources".equals(name)) {
            StringBuilder sb = new StringBuilder("可用数据源:\n\n");
            for (int i = 0; i < datasources.size(); i++) {
                JSONObject d = datasources.getJSONObject(i);
                sb.append("- ").append(d.getString("name")).append("  [").append(d.getString("type")).append("]  ").append(d.getString("desc")).append("\n");
            }
            return sb.toString();
        }
        if (name == null) throw new Exception("缺少工具名");
        String dsName = a.getString("datasource");
        if (dsName == null) throw new Exception("缺少参数 datasource");
        JSONObject ds = dsMap.get(dsName);
        if (ds == null) throw new Exception("未知数据源: " + dsName + "（可用: " + String.join(", ", dsMap.keySet()) + "）");

        if ("query".equals(name)) {
            String sql = a.getString("sql");
            if (sql == null || sql.trim().isEmpty()) throw new Exception("缺少参数 sql");
            return runQuery(ds, checkReadOnly(sql), clamp(a.getIntValue("limit"), 200, 1000));
        } else if ("list_tables".equals(name)) {
            return listTables(ds, a.getString("keyword"));
        } else if ("describe_table".equals(name)) {
            return describeTable(ds, require(a, "table"));
        } else if ("search_column".equals(name)) {
            return searchColumn(ds, require(a, "column_keyword"), a.getString("table_keyword"));
        } else if ("list_indexes".equals(name)) {
            return listIndexes(ds, require(a, "table"));
        } else if ("slow_sql_top".equals(name)) {
            return slowSqlTop(ds, a.getString("order_by"), clamp(a.getIntValue("limit"), 20, 100));
        } else if ("active_sessions".equals(name)) {
            return activeSessions(ds);
        } else if ("explain_sql".equals(name)) {
            return explainSql(ds, require(a, "sql"));
        } else if ("table_size_top".equals(name)) {
            return tableSizeTop(ds, clamp(a.getIntValue("limit"), 30, 200));
        }
        throw new Exception("未知工具: " + name);
    }

    static String require(JSONObject a, String key) throws Exception {
        String v = a.getString(key);
        if (v == null || v.trim().isEmpty()) throw new Exception("缺少参数 " + key);
        return v.trim();
    }

    static int clamp(int v, int def, int max) {
        if (v <= 0) return def;
        return v > max ? max : v;
    }

    /**
     * 只读保护：仅允许 SELECT / WITH 单条语句。
     * 关键：先剥离字符串字面量，再把「块注释 / 行注释」整体替换为空格，
     * 之后的前缀判断、分号判断、关键字黑名单全部基于清理后的文本，
     * 从而杜绝 INTO/**&#47;OUTFILE、FOR/**&#47;UPDATE、SLEEP/**&#47;( 这类注释绕过。
     * 执行时仍使用原始 SQL（保证语义），但仅在清理版判定安全时才返回。
     */
    static String checkReadOnly(String sql) throws Exception {
        String s = sql.trim();

        // 生成两种词法语义下的“清理版本”，并集校验（任一版本命中危险即拒绝）：
        //   A 标准 SQL : '' 转义；注释只有 块注释 与 --
        //   B MySQL    : \x 转义；注释含 块注释、-- 与 #
        // 这样可同时封堵： # 行注释绕过、\' 造成的字符串边界错位绕过，
        // 又不会影响 SQL Server 的 #tmp 标识符（A 版本不把 # 当注释）。
        String[] cleaned = new String[]{cleanSql(s, false), cleanSql(s, true)};

        boolean anySelect = false;
        for (String c : cleaned) {
            String x = c.trim().toLowerCase();
            if (x.startsWith("select") || x.startsWith("with")) anySelect = true;
        }
        if (!anySelect) {
            throw new Exception("只允许只读查询（SELECT / WITH）。检测到以 [" + firstWord(s) + "] 开头");
        }

        String[] forbid = {" insert ", " update ", " delete ", " drop ", " truncate ", " alter ", " create ",
                " merge ", " grant ", " revoke ", " commit ", " rollback ", " execute ", " exec ", " call ", " begin ",
                " for update ", " into outfile ", " into dumpfile ", " outfile ", " dumpfile ", " into ",
                " rename ", " comment on ", " lock table ", " waitfor ", " shutdown ",
                " handler ", " bulk insert", " reconfigure",
                // MySQL: 资源滥用 / 文件读写 / 锁 / 命令执行（UDF）
                " sleep(", " benchmark(", " load_file(", " get_lock(", " sys_exec", " sys_eval",
                // Oracle: 有副作用的包调用（DoS / 文件 / 网络外连 / 动态SQL / 任务调度）
                " dbms_lock.", " dbms_pipe.", " dbms_sql.", " dbms_scheduler.", " dbms_java.", " dbms_jvm.",
                " dbms_xmlgen.", " dbms_utility.", " dbms_transaction.", " sys.dbms_",
                " utl_http.", " utl_file.", " utl_tcp.", " utl_smtp.", " utl_inaddr.", " utl_mail.", " dbms_ldap.",
                // SQL Server: 扩展存储过程 / 动态SQL / 外部数据源
                " xp_cmdshell", " xp_dirtree", " xp_fileexist", " xp_subdirs", " xp_regread", " xp_regwrite",
                " xp_servicecontrol", " sp_configure", " sp_executesql", " sp_oacreate", " sp_oamethod",
                " openrowset", " opendatasource", " openquery",
                // 其他库与跨库外连
                " pg_sleep(", " lo_import", " lo_export", " dblink"};

        // 额外: 禁止 Oracle 远程链接语法（@link）。字符串字面量已剥离，故 '%@%'、'x@y' 不会误伤。
        // 注: 检测前先剔除 @@（MySQL / SQL Server 的系统全局变量，如 @@version、@@max_connections，
        //     属于只读诊断用法）。Oracle 无 @@ 语法、MySQL 无 @ 外连，故该放行无安全损失。
        for (String c : cleaned) {
            if (c.replace("@@", "").indexOf('@') >= 0) throw new Exception("检测到远程链接符号 @（禁止跨库访问）");
        }

        for (String c : cleaned) {
            // 多语句检测（字符串/注释内的分号已被剥离）
            int semi = c.indexOf(';');
            if (semi >= 0 && c.substring(semi + 1).trim().length() > 0) {
                throw new Exception("不允许多条语句");
            }
            // 关键字黑名单（归一化空白，并消除“空格+左括号”）
            String l = " " + c.toLowerCase().replaceAll("\\s+", " ").replaceAll("\\s+\\(", "(") + " ";
            for (String f : forbid) {
                if (l.contains(f)) throw new Exception("检测到非只读关键字: " + f.trim());
            }
        }

        // 返回用于执行的原始 SQL（仅去掉结尾多余分号，保证语义不变）
        String exec = s;
        if (exec.endsWith(";")) exec = exec.substring(0, exec.length() - 1).trim();
        return exec;
    }

    /**
     * 按指定词法语义剥离字符串字面量与注释，返回“清理版本”用于安全判定。
     * mysql=true  : 字符串支持 \x 转义，行注释额外支持 #
     * mysql=false : 标准 SQL，仅 '' 转义，注释只有块注释与 --
     * 注：MySQL 的 可执行注释 形式（斜杠星号叹号）不剥离，保留内容参与检查。
     */
    static String cleanSql(String s, boolean mysql) {
        String noStr;
        if (mysql) {
            noStr = s.replaceAll("'(?:\\\\.|[^'\\\\])*'", "''");
        } else {
            noStr = s.replaceAll("'(?:[^']|'')*'", "''");
        }
        String r = noStr.replaceAll("/\\*(?!!)[\\s\\S]*?\\*/", " ");
        if (mysql) {
            // MySQL: -- 后必须跟空白（含行尾）才是注释；--X 被解析为减号运算，不能剥
            r = r.replaceAll("--(?=\\s|$)[^\\n]*", " ").replaceAll("#[^\\n]*", " ");
        } else {
            // 标准 SQL(Oracle/SQL Server): -- 无空白要求
            r = r.replaceAll("--[^\\n]*", " ");
        }
        return r;
    }

    static String firstWord(String s) {
        int i = s.indexOf(' ');
        String w = i > 0 ? s.substring(0, i) : s;
        return w.length() > 20 ? w.substring(0, 20) : w;
    }

    // ============ 查询执行 ============

    static String runQuery(JSONObject ds, String sql, int limit) throws Exception {
        long t0 = System.currentTimeMillis();
        try (Connection conn = getConnection(ds)) {
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(20);
                st.setMaxRows(limit);
                try (ResultSet rs = st.executeQuery(sql)) {
                    String md = toMarkdown(rs, limit);
                    long ms = System.currentTimeMillis() - t0;
                    return "数据源: " + ds.getString("name") + "  |  耗时 " + ms + " ms\n\n" + md;
                }
            }
        }
    }

    static String toMarkdown(ResultSet rs, int limit) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<String>();
        for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", cols)).append(" |\n");
        sb.append("|");
        for (int i = 0; i < n; i++) sb.append("---|");
        sb.append("\n");
        int cnt = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (sb.length() > OUTPUT_MAX) { truncated = true; break; }
            cnt++;
            sb.append("| ");
            for (int i = 1; i <= n; i++) {
                String v = rs.getString(i);
                if (v == null) v = "(null)";
                if (v.length() > ROUGH_MAX) v = v.substring(0, ROUGH_MAX);
                v = oneLine(v).replace("|", "\\|");
                if (v.length() > CELL_MAX) v = v.substring(0, CELL_MAX) + "...(truncated)";
                sb.append(v);
                if (i < n) sb.append(" | ");
            }
            sb.append(" |\n");
        }
        String head = "返回 " + cnt + " 行" + (truncated ? "（因输出过大已截断）" : "") + "\n\n";
        return head + sb.toString();
    }

    /** 字面量转义: MySQL 默认反斜杠为转义符，需先转义反斜杠；其它库仅转义单引号 */
    static String lit(JSONObject ds, String s) {
        if (s == null) return "";
        String type = (ds == null) ? "" : ds.getString("type");
        if ("mysql".equals(type)) {
            return s.replace("\\", "\\\\").replace("'", "''");
        }
        return s.replace("'", "''");
    }

    static String listTables(JSONObject ds, String kw) throws Exception {
        String type = ds.getString("type");
        String sql;
        if ("oracle".equals(type)) {
            String owner = lit(ds, ds.getString("user").toUpperCase());
            sql = "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE TABLE_TYPE='TABLE' AND OWNER='" + owner + "'";
            if (kw != null && !kw.isEmpty()) {
                String k = lit(ds, kw.toUpperCase());
                sql += " AND (UPPER(TABLE_NAME) LIKE '%" + k + "%' OR UPPER(COMMENTS) LIKE '%" + k + "%')";
            }
            sql += " ORDER BY TABLE_NAME";
        } else if ("sqlserver".equals(type)) {
            sql = "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE='BASE TABLE'";
            if (kw != null && !kw.isEmpty()) sql += " AND TABLE_NAME LIKE '%" + lit(ds, kw) + "%'";
            sql += " ORDER BY TABLE_NAME";
        } else {
            sql = "SELECT TABLE_NAME, TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE='BASE TABLE' AND TABLE_SCHEMA=DATABASE()";
            if (kw != null && !kw.isEmpty()) sql += " AND (TABLE_NAME LIKE '%" + lit(ds, kw) + "%' OR TABLE_COMMENT LIKE '%" + lit(ds, kw) + "%')";
            sql += " ORDER BY TABLE_NAME";
        }
        return runQuery(ds, sql, 2000);
    }

    static String describeTable(JSONObject ds, String table) throws Exception {
        String type = ds.getString("type");
        if ("mysql".equals(type)) {
            String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT "
                    + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + lit(ds, table) + "' ORDER BY ORDINAL_POSITION";
            return runQuery(ds, sql, 2000);
        }
        if ("sqlserver".equals(type)) {
            String t = lit(ds, table);
            String sql = "SELECT c.COLUMN_NAME, c.DATA_TYPE, c.CHARACTER_MAXIMUM_LENGTH, c.IS_NULLABLE, c.COLUMN_DEFAULT, "
                    + "CASE WHEN pk.COLUMN_NAME IS NULL THEN '' ELSE 'PK' END AS IS_PK "
                    + "FROM INFORMATION_SCHEMA.COLUMNS c "
                    + "LEFT JOIN (SELECT ku.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                    + "  JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku ON tc.CONSTRAINT_NAME=ku.CONSTRAINT_NAME AND tc.TABLE_NAME=ku.TABLE_NAME "
                    + "  WHERE tc.CONSTRAINT_TYPE='PRIMARY KEY' AND ku.TABLE_NAME='" + t + "') pk ON pk.COLUMN_NAME=c.COLUMN_NAME "
                    + "WHERE c.TABLE_NAME='" + t + "' ORDER BY c.ORDINAL_POSITION";
            return runQuery(ds, sql, 2000);
        }
        // Oracle: 字段+注释、主键、默认值分别查再合并
        String owner = lit(ds, ds.getString("user").toUpperCase());
        String t = lit(ds, table.toUpperCase());
        Map<String, String> pkCols = new LinkedHashMap<String, String>();
        Map<String, String> defs = new LinkedHashMap<String, String>();
        StringBuilder sb = new StringBuilder();
        try (Connection conn = getConnection(ds)) {
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(20);
                try (ResultSet rs = st.executeQuery("SELECT acc.COLUMN_NAME FROM ALL_CONSTRAINTS ac JOIN ALL_CONS_COLUMNS acc "
                        + "ON acc.OWNER=ac.OWNER AND acc.CONSTRAINT_NAME=ac.CONSTRAINT_NAME "
                        + "WHERE ac.OWNER='" + owner + "' AND ac.TABLE_NAME='" + t + "' AND ac.CONSTRAINT_TYPE='P' ORDER BY acc.POSITION")) {
                    while (rs.next()) pkCols.put(rs.getString(1), "PK");
                }
                try (ResultSet rs = st.executeQuery("SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS "
                        + "WHERE OWNER='" + owner + "' AND TABLE_NAME='" + t + "' ORDER BY COLUMN_ID")) {
                    while (rs.next()) {
                        String c = rs.getString(1);
                        String d = rs.getString(2);
                        if (d != null) defs.put(c, oneLine(d));
                    }
                } catch (Exception ignoreDef) {
                }
                try (ResultSet rs = st.executeQuery("SELECT c.COLUMN_NAME, c.DATA_TYPE, c.DATA_LENGTH, c.DATA_PRECISION, c.DATA_SCALE, c.NULLABLE, cc.COMMENTS "
                        + "FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER=c.OWNER AND cc.TABLE_NAME=c.TABLE_NAME AND cc.COLUMN_NAME=c.COLUMN_NAME "
                        + "WHERE c.OWNER='" + owner + "' AND c.TABLE_NAME='" + t + "' ORDER BY c.COLUMN_ID")) {
                    sb.append("| 列名 | 类型 | 可空 | 主键 | 默认值 | 注释 |\n|---|---|---|---|---|---|\n");
                    int cnt = 0;
                    while (rs.next()) {
                        cnt++;
                        String col = rs.getString(1);
                        String dt = rs.getString(2);
                        String prec = rs.getString(4), scale = rs.getString(5);
                        if ("NUMBER".equalsIgnoreCase(dt) && prec != null) {
                            dt = "NUMBER(" + prec + (scale != null && !"0".equals(scale.trim()) ? "," + scale.trim() : "") + ")";
                        } else if (prec != null && (dt.toUpperCase().contains("CHAR") || dt.toUpperCase().contains("RAW"))) {
                            dt = dt + "(" + rs.getString(3) + ")";
                        }
                        sb.append("| ").append(col).append(" | ").append(dt)
                          .append(" | ").append("Y".equals(rs.getString(6)) ? "" : "NOT NULL")
                          .append(" | ").append(pkCols.containsKey(col) ? "PK" : "")
                          .append(" | ").append(defs.containsKey(col) ? defs.get(col).replace("|", "\\|") : "")
                          .append(" | ").append(oneLine(rs.getString(7)).replace("|", "\\|")).append(" |\n");
                    }
                    sb.insert(0, "返回 " + cnt + " 行\n\n");
                }
            }
        }
        return "数据源: " + ds.getString("name") + "\n\n" + sb.toString();
    }

    static String searchColumn(JSONObject ds, String colKw, String tableKw) throws Exception {
        String type = ds.getString("type");
        String sql;
        if ("oracle".equals(type)) {
            String owner = lit(ds, ds.getString("user").toUpperCase());
            sql = "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, cc.COMMENTS FROM ALL_TAB_COLUMNS c "
                + "LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER=c.OWNER AND cc.TABLE_NAME=c.TABLE_NAME AND cc.COLUMN_NAME=c.COLUMN_NAME "
                + "WHERE c.OWNER='" + owner + "' AND UPPER(c.COLUMN_NAME) LIKE '%" + lit(ds, colKw.toUpperCase()) + "%'";
            if (tableKw != null && !tableKw.isEmpty()) sql += " AND UPPER(c.TABLE_NAME) LIKE '%" + lit(ds, tableKw.toUpperCase()) + "%'";
            sql += " ORDER BY c.TABLE_NAME, c.COLUMN_ID";
        } else if ("sqlserver".equals(type)) {
            sql = "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE COLUMN_NAME LIKE '%" + lit(ds, colKw) + "%'";
            if (tableKw != null && !tableKw.isEmpty()) sql += " AND TABLE_NAME LIKE '%" + lit(ds, tableKw) + "%'";
            sql += " ORDER BY TABLE_NAME, ORDINAL_POSITION";
        } else {
            sql = "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME LIKE '%" + lit(ds, colKw) + "%'";
            if (tableKw != null && !tableKw.isEmpty()) sql += " AND TABLE_NAME LIKE '%" + lit(ds, tableKw) + "%'";
            sql += " ORDER BY TABLE_NAME, ORDINAL_POSITION";
        }
        return runQuery(ds, sql, 2000);
    }

    static String listIndexes(JSONObject ds, String table) throws Exception {
        String type = ds.getString("type");
        String sql;
        if ("oracle".equals(type)) {
            String owner = lit(ds, ds.getString("user").toUpperCase());
            String t = lit(ds, table.toUpperCase());
            sql = "SELECT i.INDEX_NAME, i.UNIQUENESS, ic.COLUMN_NAME, ic.COLUMN_POSITION "
                + "FROM ALL_INDEXES i JOIN ALL_IND_COLUMNS ic ON ic.INDEX_OWNER=i.OWNER AND ic.INDEX_NAME=i.INDEX_NAME "
                + "WHERE i.TABLE_OWNER='" + owner + "' AND i.TABLE_NAME='" + t + "' ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION";
        } else if ("sqlserver".equals(type)) {
            sql = "SELECT i.name AS INDEX_NAME, CASE WHEN i.is_unique=1 THEN 'UNIQUE' ELSE '' END AS UNIQUENESS, "
                + "c.name AS COLUMN_NAME, ic.key_ordinal AS COLUMN_POSITION "
                + "FROM sys.indexes i JOIN sys.index_columns ic ON i.object_id=ic.object_id AND i.index_id=ic.index_id "
                + "JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id "
                + "WHERE i.object_id=OBJECT_ID('" + lit(ds, table) + "') ORDER BY i.name, ic.key_ordinal";
        } else {
            sql = "SELECT INDEX_NAME, CASE WHEN NON_UNIQUE=0 THEN 'UNIQUE' ELSE '' END AS UNIQUENESS, "
                + "COLUMN_NAME, SEQ_IN_INDEX AS COLUMN_POSITION, INDEX_TYPE "
                + "FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + lit(ds, table) + "' "
                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX";
        }
        return runQuery(ds, sql, 2000);
    }

    static String slowSqlTop(JSONObject ds, String orderBy, int limit) throws Exception {
        String type = ds.getString("type");
        String ob = (orderBy == null) ? "elapsed" : orderBy.toLowerCase();
        String sql;
        if ("oracle".equals(type)) {
            String col = "elapsed_time";
            if ("cpu".equals(ob)) col = "cpu_time";
            else if ("executions".equals(ob)) col = "executions";
            sql = "SELECT * FROM (SELECT sql_id, SUBSTR(sql_text,1,200) AS sql_text, "
                + "ROUND(elapsed_time/1000000,2) AS elapsed_sec, executions, "
                + "ROUND(elapsed_time/GREATEST(executions,1)/1000,2) AS avg_ms, ROUND(cpu_time/1000000,2) AS cpu_sec "
                + "FROM v$sql WHERE executions > 0 ORDER BY " + col + " DESC) WHERE ROWNUM <= " + limit;
        } else if ("sqlserver".equals(type)) {
            String col = "total_elapsed_time";
            if ("cpu".equals(ob)) col = "total_worker_time";
            else if ("executions".equals(ob)) col = "execution_count";
            sql = "SELECT TOP " + limit + " SUBSTRING(t.text,1,200) AS sql_text, s.execution_count, "
                + "ROUND(s.total_elapsed_time/1000.0,2) AS total_ms, "
                + "ROUND(s.total_elapsed_time/NULLIF(s.execution_count,0)/1000.0,2) AS avg_ms, "
                + "ROUND(s.total_worker_time/1000.0,2) AS total_cpu_ms "
                + "FROM (SELECT TOP " + limit + " sql_handle, execution_count, total_elapsed_time, total_worker_time "
                + "FROM sys.dm_exec_query_stats ORDER BY " + col + " DESC) s "
                + "CROSS APPLY sys.dm_exec_sql_text(s.sql_handle) t "
                + "ORDER BY s." + col + " DESC";
        } else {
            String col = "SUM_TIMER_WAIT";
            if ("cpu".equals(ob)) col = "SUM_CPU_TIME";
            else if ("executions".equals(ob)) col = "COUNT_STAR";
            sql = "SELECT DIGEST_TEXT, COUNT_STAR AS executions, "
                + "ROUND(SUM_TIMER_WAIT/1000000000000,2) AS total_sec, "
                + "ROUND(AVG_TIMER_WAIT/1000000,2) AS avg_ms "
                + "FROM performance_schema.events_statements_summary_by_digest "
                + "WHERE DIGEST_TEXT IS NOT NULL ORDER BY " + col + " DESC LIMIT " + limit;
        }
        return runQuery(ds, sql, limit);
    }

    static String activeSessions(JSONObject ds) throws Exception {
        String type = ds.getString("type");
        String sql;
        if ("oracle".equals(type)) {
            sql = "SELECT s.sid, s.serial#, s.username, s.status, s.sql_id, "
                + "ROUND(s.last_call_et/60,1) AS idle_min, SUBSTR(q.sql_text,1,200) AS sql_text "
                + "FROM v$session s LEFT JOIN v$sql q ON s.sql_id=q.sql_id "
                + "WHERE s.type='USER' AND s.status='ACTIVE' ORDER BY s.last_call_et DESC";
        } else if ("sqlserver".equals(type)) {
            sql = "SELECT r.session_id, s.login_name, r.status, r.command, r.wait_type, "
                + "r.total_elapsed_time, SUBSTRING(t.text,1,200) AS sql_text "
                + "FROM sys.dm_exec_requests r JOIN sys.dm_exec_sessions s ON r.session_id=s.session_id "
                + "OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t "
                + "WHERE s.is_user_process=1 ORDER BY r.total_elapsed_time DESC";
        } else {
            sql = "SELECT ID, USER, HOST, DB, COMMAND, TIME AS time_sec, STATE, LEFT(INFO,200) AS sql_text "
                + "FROM INFORMATION_SCHEMA.PROCESSLIST WHERE COMMAND <> 'Sleep' ORDER BY TIME DESC";
        }
        return runQuery(ds, sql, 500);
    }

    static String explainSql(JSONObject ds, String sql) throws Exception {
        String type = ds.getString("type");
        String safe = checkReadOnly(sql);
        if ("mysql".equals(type)) {
            return runQuery(ds, "EXPLAIN " + safe, 500);
        }
        if ("sqlserver".equals(type)) {
            try (Connection conn = getConnection(ds)) {
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(20);
                    st.execute("SET SHOWPLAN_ALL ON");
                    String md;
                    try (ResultSet rs = st.executeQuery(safe)) {
                        md = toMarkdown(rs, 500);
                    } finally {
                        try { st.execute("SET SHOWPLAN_ALL OFF"); } catch (Exception ig) {}
                    }
                    return "SQL Server SHOWPLAN_ALL（语句未执行）：\n\n" + md;
                }
            }
        }
        try (Connection conn = getConnection(ds)) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(20);
                st.execute("EXPLAIN PLAN FOR " + safe);
                StringBuilder sb = new StringBuilder();
                try (ResultSet rs = st.executeQuery("SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', NULL, 'BASIC'))")) {
                    while (rs.next()) sb.append(rs.getString(1)).append("\n");
                }
                conn.rollback();
                return "Oracle 执行计划（使用 EXPLAIN PLAN，已回滚 PLAN_TABLE）：\n\n" + sb.toString();
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ig) {}
                throw new Exception("Oracle EXPLAIN 失败（可能缺少 PLAN_TABLE 或权限）: " + e.getMessage());
            }
        }
    }

    static String tableSizeTop(JSONObject ds, int limit) throws Exception {
        String type = ds.getString("type");
        String sql;
        if ("oracle".equals(type)) {
            sql = "SELECT * FROM (SELECT t.TABLE_NAME, t.NUM_ROWS, NVL(s.MB,0) AS SIZE_MB FROM USER_TABLES t "
                + "LEFT JOIN (SELECT SEGMENT_NAME, ROUND(SUM(BYTES)/1024/1024,2) AS MB FROM USER_SEGMENTS "
                + "WHERE SEGMENT_TYPE='TABLE' GROUP BY SEGMENT_NAME) s ON s.SEGMENT_NAME=t.TABLE_NAME "
                + "ORDER BY NVL(s.MB,0) DESC) WHERE ROWNUM <= " + limit;
        } else if ("sqlserver".equals(type)) {
            sql = "SELECT TOP " + limit + " t.name AS TABLE_NAME, SUM(p.rows) AS NUM_ROWS, "
                + "ROUND(SUM(a.total_pages)*8.0/1024,2) AS SIZE_MB "
                + "FROM sys.tables t JOIN sys.indexes i ON t.object_id=i.object_id "
                + "JOIN sys.partitions p ON i.object_id=p.object_id AND i.index_id=p.index_id "
                + "JOIN sys.allocation_units a ON p.partition_id=a.container_id "
                + "WHERE i.index_id IN (0,1) GROUP BY t.name ORDER BY SUM(a.total_pages) DESC";
        } else {
            sql = "SELECT TABLE_NAME, TABLE_ROWS, "
                + "ROUND((IFNULL(DATA_LENGTH,0)+IFNULL(INDEX_LENGTH,0))/1024/1024,2) AS SIZE_MB "
                + "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' "
                + "ORDER BY (IFNULL(DATA_LENGTH,0)+IFNULL(INDEX_LENGTH,0)) DESC LIMIT " + limit;
        }
        return runQuery(ds, sql, limit);
    }

    static Connection getConnection(JSONObject ds) throws Exception {
        String url = ds.getString("url");
        Properties p = new Properties();
        p.put("user", resolveCred(ds, "user"));
        p.put("password", resolveCred(ds, "pass"));
        DriverManager.setLoginTimeout(10);
        return DriverManager.getConnection(url, p);
    }
}
