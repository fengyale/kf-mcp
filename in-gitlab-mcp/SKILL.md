---
name: in-gitlab-mcp
description: 零依赖内网 GitLab MCP Server（Node 标准库实现，离线可用），默认只读模式。21 个工具覆盖项目/文件/提交/分支/Issue/MR/流水线，14 个只读工具默认可用，7 个写工具默认隐藏。当用户需要让 AI 操作 GitLab（查代码、读文件、查提交/MR diff、查流水线）时使用。
---

# in-gitlab-mcp — 零依赖内网 GitLab MCP Server

只使用 Node 标准库（`http`/`https`）的 GitLab MCP 服务，**不依赖任何 npm 包，在完全离线的内网机器上拷过去就能跑**，无需 `npm install`。

- 默认对接公司内网 GitLab：`http://your-gitlab.example.com/api/v4`
- 已验证环境：GitLab 11.9.9、Node 14+（v24 已验证）
- 适用任何 stdio 传输的 MCP 客户端（Trae / CodeBuddy / Claude Desktop / Cursor / Cline 等）

## 核心特性

- **零依赖**：唯一必需文件是 `server.js`，内网离线环境友好
- **默认只读**：21 个工具中 **14 个只读工具**默认下发；**7 个写工具**（提交/建分支/建 MR/评论/合并/建 Issue）默认不下发、调用被拒，需显式设 `GITLAB_READONLY=0` 才启用
- **双重安全**：客户端开关防误触 + **token 用 `read_api` scope 才是真正的安全边界**（服务端直接拒绝写）
- **老版本兼容**：已适配内网 GitLab 11.9.9 的已知差异（代码搜索只覆盖默认分支、MR 草稿是 `WIP:` 前缀等）
- **分页友好**：所有列表工具返回末尾附分页提示

## 工具清单速览（21 个，🔒 为默认隐藏的写工具）

| 分类 | 工具 |
|---|---|
| 项目 | `gitlab_list_projects` / `gitlab_get_project` / `gitlab_list_project_members` |
| 仓库/文件 | `gitlab_get_file` / `gitlab_search_in_project` / `gitlab_list_branches` |
| 提交 | `gitlab_list_commits` / `gitlab_get_commit` |
| Issue | `gitlab_list_issues` / `gitlab_get_issue` |
| MR | `gitlab_list_merge_requests` / `gitlab_get_merge_request` / `gitlab_get_merge_request_changes` |
| CI/CD | `gitlab_list_pipelines` |
| 🔒 写-仓库 | `gitlab_create_branch` / `gitlab_create_or_update_file` / `gitlab_push_files`（批量提交） |
| 🔒 写-Issue | `gitlab_create_issue` |
| 🔒 写-MR | `gitlab_create_merge_request` / `gitlab_create_merge_request_note` / `gitlab_merge_merge_request` |

## 安装（3 步）

1. 把本目录整体拷到本机，如 `~/.codebuddy/in-gitlab-mcp/`
2. 拿 token：浏览器打开 `http://your-gitlab.example.com` → 头像 → Edit profile → Access Tokens → **勾选 `read_api`**（读仓库代码可加 `read_repository`）→ 生成
3. 配置二选一：
   - **方式 A（推荐）**：复制 `gitlab-mcp.env.example` 为 `gitlab-mcp.env` 放 `server.js` 同目录，填入 token
   - **方式 B**：写在客户端 MCP 配置的 `env` 里（本目录 `mcp-servers.snippet.json` 是成品片段）：

```json
{
  "mcpServers": {
    "in-gitlab": {
      "command": "node",
      "args": ["<server.js 绝对路径>"],
      "env": {
        "GITLAB_API_URL": "http://your-gitlab.example.com/api/v4",
        "GITLAB_PERSONAL_ACCESS_TOKEN": "你的token",
        "GITLAB_TIMEOUT_MS": "30000",
        "GITLAB_READONLY": "1"
      }
    }
  }
}
```

> `command` 写 `node` 而不是 `npx`（Windows 下 npx 是 npx.cmd，部分客户端 spawn 会失败）；多 Node 版本机器写 node.exe 绝对路径。改完**重启客户端**。

## 安全要点（按重要性排序）

1. **token scope 用 `read_api`** —— 这是唯一真正的安全边界，写操作在 GitLab 服务端直接被拒
2. `GITLAB_READONLY` 默认只读，作用是让写工具不下发、被调用时拒绝，**只是客户端侧防呆，不能替代第 1 条**
3. 确需写能力时：单独生成 `api` scope 的 token，并注意写操作（尤其合并 MR）可能**触发流水线、发出不可撤回的通知**
4. `gitlab-mcp.env` 含明文 token，**不要提交到代码仓库**；内网 `http` 明文传输，勿在不可信网络使用

## 目录说明

```
in-gitlab-mcp/
├── server.js                 # 服务主体，唯一必需文件
├── gitlab-mcp.env.example    # 配置模板（复制为 gitlab-mcp.env 使用）
├── mcp-servers.snippet.json  # 各客户端通用配置片段
├── start.cmd / start.sh      # 手动启动脚本（调试用）
├── package.json
└── README.md                 # 详细文档（工具参数、只读开关说明、版本兼容坑、排查手册）
```

详细说明（每个工具的参数、内网 GitLab 版本兼容坑、故障排查表、变更记录）见 [README.md](README.md)。
