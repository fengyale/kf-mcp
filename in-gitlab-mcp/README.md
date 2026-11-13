# GitLab MCP Server（零依赖 / 适配内网 GitLab）

一个**零第三方依赖**的 GitLab MCP 服务，只使用 Node 标准库（`http`/`https`），
通过 MCP stdio（NDJSON）协议对外暴露 GitLab 操作工具。

> ### 默认只读
>
> 服务**默认运行在只读模式**：只下发 **14 个只读工具**（查询 / 搜索 / diff / 流水线），
> **7 个写工具**（提交、建分支、建 MR、评论、合并 MR、建 Issue）默认**不下发、不可调用**。
> 需要写能力时显式设置 `GITLAB_READONLY=0` —— 详见第 3 节。

因为不依赖任何 npm 包，**在完全离线的内网机器上也能直接运行**，无需 `npm install`。

- 默认对接：`http://your-gitlab.example.com/api/v4`（公司内网 GitLab）
- 已验证环境：GitLab **11.9.9**、Node **v24.20.0**（Node 14+ 均可）
- 适用客户端：任何支持 stdio 传输的 MCP 客户端（CodeBuddy / Claude Desktop / Cursor / Trae / Cline 等）

---

## 1. 目录结构

```
in-gitlab-mcp/
├── server.js                 # 服务主体，唯一必需文件
├── gitlab-mcp.env.example    # 配置模板（复制为 gitlab-mcp.env 使用）
├── mcp-servers.snippet.json  # 各客户端通用配置片段
├── start.cmd                 # Windows 手动启动脚本（调试用）
├── start.sh                  # macOS / Linux 手动启动脚本（调试用）
├── package.json
└── README.md
```

> 真正必需的只有 `server.js`。其余是配置模板与调试辅助。
> 在 macOS / Linux 上如果 `start.sh` 没有执行权限，先 `chmod +x start.sh`。

---

## 2. 快速开始（3 步）

### 第 1 步：拿到 Personal Access Token

浏览器打开 `http://your-gitlab.example.com` → 右上角头像 → **Edit profile / Preferences → Access Tokens**
→ 勾选 scope **`read_api`**（需要读仓库代码可再加 `read_repository`）→ 生成并复制。

> **强烈建议用 `read_api` 而不是 `api`。**
> 这是唯一真正的安全边界：只读 token 会让写操作在 **GitLab 服务端**直接被拒，
> 此时即使客户端工具层有疏漏，也写不进任何东西。
> 若确实需要写能力，再单独生成一个 `api` scope 的 token，用完换回。

### 第 2 步：填配置（二选一）

**方式 A：集中配置文件（推荐，多个客户端共用一份）**

```bash
cp gitlab-mcp.env.example gitlab-mcp.env   # Windows: copy
```

编辑 `gitlab-mcp.env`：

```
GITLAB_API_URL=http://your-gitlab.example.com/api/v4
GITLAB_PERSONAL_ACCESS_TOKEN=你的token
GITLAB_TIMEOUT_MS=30000
GITLAB_READONLY=1        # 只读模式（默认值；设为 0 才启用写工具）
```

该文件放在 `server.js` **同目录**即可被自动加载（优先找 `gitlab-mcp.env`，找不到再退而找 `.env`）；
进程已有的环境变量优先级更高，因此客户端配置里填了 `env` 时会以客户端为准。

> 注意：因为会兜底读取同目录的 `.env`，请不要把 `server.js` 随意丢进别的项目根目录，
> 以免误读该项目 `.env` 里的同名变量。

**方式 B：写在客户端配置的 `env` 里**（见第 3 步示例）。

### 第 3 步：接入 MCP 客户端

把下面片段合并进对应客户端的 MCP 配置（把 `server.js` 换成实际绝对路径）：

```json
{
  "mcpServers": {
    "in-gitlab": {
      "command": "node",
      "args": ["C:/Users/你的用户名/.codebuddy/in-gitlab-mcp/server.js"],
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

各客户端配置文件位置：

| 客户端 | 配置文件 |
|---|---|
| CodeBuddy | `~/.codebuddy/mcp.json` |
| Claude Desktop | `%APPDATA%\Claude\claude_desktop_config.json`（Win）/ `~/Library/Application Support/Claude/claude_desktop_config.json`（macOS） |
| Cursor | 项目内 `.cursor/mcp.json` 或全局 `~/.cursor/mcp.json` |
| Trae | 项目内 `.trae/mcp.json` |
| Cline / Roo | 扩展设置中的 MCP Servers → Edit Config |

> 用 `command: "node"` 而不是 `npx`：Windows 下 `npx` 实际是 `npx.cmd`，部分客户端 spawn 会失败；`node` 对应真实的 `node.exe`，最稳。
> 若一台机器有多个 Node 版本，把 `command` 写成 node 的绝对路径，如 `F:/Program Files/node-v24.20.0-win-x64/node.exe`。

改完配置后**重启客户端**，服务才会被拉起。

---

## 3. 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `GITLAB_API_URL` | 否 | `http://your-gitlab.example.com/api/v4` | GitLab 地址；带不带 `/api/v4` 都能识别 |
| `GITLAB_PERSONAL_ACCESS_TOKEN` | **是** | 无 | Personal Access Token，**推荐 scope `read_api`** |
| `GITLAB_TIMEOUT_MS` | 否 | `30000` | 单次请求超时（毫秒） |
| `GITLAB_READONLY` | 否 | **`1`（只读）** | 只读模式开关。留空 / `1` / `true` 均为只读；**只有显式设为 `0` / `false` / `no` / `off` 才启用写工具** |

未配置 token 时不会崩溃，调用工具会返回明确的提示信息。

**关于 `GITLAB_READONLY` 的两点说明：**

1. **为什么默认只读**：写操作（尤其合并 MR）可能触发流水线、发出不可撤回的通知邮件。
   安全方向默认正确，要通过写操作必须显式打开。
2. **它不等于安全边界**：这个开关只是让写工具不下发、调用被拒，属于客户端侧防呆；
   服务进程本身仍有能力发起写请求。**真正的边界是 token 的 scope（用 `read_api`）。**

---

## 4. 工具清单

共 21 个。**默认只读模式下下发 14 个**；带 🔒 的 **7 个写操作工具默认隐藏**，需 `GITLAB_READONLY=0` 才会出现。

### 项目 / 成员（只读）
| 工具 | 说明 |
|---|---|
| `gitlab_list_projects` | 列出或搜索项目，支持 `membership` / `owned` / `search` |
| `gitlab_get_project` | 项目详情（默认分支、可见性、地址等） |
| `gitlab_list_project_members` | 项目成员及权限级别 |

### 仓库 / 文件 / 提交
| 工具 | 说明 |
|---|---|
| `gitlab_get_file` | 读取文件原始内容（指定 `ref`） |
| `gitlab_search_in_project` | 项目内搜索代码 / 提交 / Issue / MR |
| `gitlab_list_branches` | 分支列表，支持关键字搜索 |
| `gitlab_list_commits` | 提交列表，支持分支、时间范围、路径、作者过滤 |
| `gitlab_get_commit` | 单个提交详情与 diff |
| 🔒 `gitlab_create_branch` | 基于 ref 创建分支（可能触发流水线） |
| 🔒 `gitlab_create_or_update_file` | 创建或更新**单个**文件（自动判断 PUT/POST） |
| 🔒 `gitlab_push_files` | **批量提交**：同一个提交里新增 / 修改 / 删除 / 移动多个文件 |

### Issue
| 工具 | 说明 |
|---|---|
| `gitlab_list_issues` | Issue 列表（状态 / 标签 / 关键字过滤） |
| `gitlab_get_issue` | Issue 详情 |
| 🔒 `gitlab_create_issue` | 创建 Issue（会发出通知） |

### 合并请求（MR）
| 工具 | 说明 |
|---|---|
| `gitlab_list_merge_requests` | MR 列表（状态 / 源分支 / 目标分支过滤） |
| `gitlab_get_merge_request` | MR 详情 |
| `gitlab_get_merge_request_changes` | MR 代码变更 diff |
| 🔒 `gitlab_create_merge_request` | 创建 MR（会发出通知、请求 review） |
| 🔒 `gitlab_create_merge_request_note` | MR 评论（支持 `suggestion` 语法） |
| 🔒 `gitlab_merge_merge_request` | **合并 MR** —— 会改动主干，可能触发流水线 |

### CI/CD（只读）
| 工具 | 说明 |
|---|---|
| `gitlab_list_pipelines` | 流水线列表 |

所有列表工具都支持 `page` / `per_page`（默认 20，上限 100），返回文本末尾会附分页信息，例如：

```
--- 分页：第 1 页，每页 20，共 31 条；下一页请传 page=2
```

---

## 5. 内网 GitLab 版本兼容性说明

目标服务端为 **GitLab 11.9.9**（较老），有几处已知差异，脚本内已做兼容：

- 代码搜索结果里字段名是 `filename`，而非新版本的 `path` —— 两者都已保留；
- MR 没有 `draft` 字段，草稿状态体现为标题的 `WIP:` 前缀；
- `gitlab_create_merge_request` 的 `remove_source_branch` 参数在 11.x 可能不生效，
  更可靠的做法是合并时用 `gitlab_merge_merge_request` 传 `should_remove_source_branch`；
- **`gitlab_search_in_project` 的 `ref` 参数在该版本不生效**（实测传 `ref=dev`，返回结果里的
  `ref` 仍是默认分支 `master`）—— 老版本 blobs 搜索只覆盖默认分支。若要查其他分支的代码，
  请改用 `gitlab_get_file` 并显式指定 `ref`。

---

## 6. 常见问题排查

| 现象 | 原因与处理 |
|---|---|
| `401` | token 无效 / 过期 / scope 不足；或 header 未被透传 |
| 写工具「已拒绝执行」 | 服务处于只读模式。如需写操作，显式设置 `GITLAB_READONLY=0`（并确认 token 权限） |
| 工具列表里只有 14 个 | 正常 —— 只读模式默认隐藏 7 个写工具 |
| `404` | 项目 ID 或路径写错，或 token 对该仓库无权限（项目路径用 `namespace/project`，如 `zhfw_group/medical-insurance`） |
| `未配置 GITLAB_PERSONAL_ACCESS_TOKEN` | 客户端 `env` 没填，且同目录没有 `gitlab-mcp.env` |
| 客户端里看不到 GitLab 工具 | 未重启客户端；或 `command`/`args` 路径写错（先用第 7 节的命令自测） |
| 连不上、超时 | 确认能访问 `http://your-gitlab.example.com`；`GITLAB_TIMEOUT_MS` 可调大 |

---

## 7. 手动自测（不依赖任何客户端）

```bash
# 1) 语法检查
node --check server.js

# 2) 直接发一条 MCP 请求（Windows PowerShell）
'{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | node server.js
```

正常应输出包含 `serverInfo: {"name":"in-gitlab-mcp","version":"1.1.0"}` 或工具列表的 JSON 行
（只读模式下是 14 个工具）。

也可以点开 `start.cmd` / `start.sh` 手动运行，此时 stderr 会打印：

```
[in-gitlab-mcp] started, api=http://your-gitlab.example.com/api/v4, token=已配置, mode=只读（写工具已隐藏，可用 14 个）
```

未配置 token 或 `GITLAB_READONLY=0` 时，这一行会相应显示 `token=未配置` 或 `mode=读写（可用 21 个）`。

---

## 8. 安全提示

**关于写操作（按重要性排序）：**

1. **把 token 的 scope 限制为 `read_api`** —— 这是**唯一真正的安全边界**。只读 token 会让
   写操作在 GitLab 服务端被直接拒绝，即使工具层有疏漏也写不进任何东西。
2. `GITLAB_READONLY` 默认为只读，作用是让写工具不下发、被调用时直接拒绝。
   **它只是客户端侧的防呆，不能替代第 1 条。**
3. 真要启用写能力时请注意：写操作——**尤其 `gitlab_merge_merge_request`（合并 MR）**——
   可能**触发流水线、发出不可撤回的通知邮件**。建议先在无人关注的一次性仓库里验证。

**关于凭据：**

- `gitlab-mcp.env` 与客户端配置里含有明文 token，**不要提交到代码仓库**，建议加入 `.gitignore`；
- **打包分发时必须排除 `gitlab-mcp.env`** —— 只分发 `gitlab-mcp.env.example` 模板，否则会把真实 token 一起发出去；
- 内网使用 `http` 明文传输，token 会以明文经过网络，请勿在不可信网络下使用。

---

## 9. 变更记录

| 版本 | 变更 |
|---|---|
| **1.1.0** | ① 新增 `GITLAB_READONLY` 开关，**默认只读**（21 个工具中 7 个写工具默认不下发、调用被拒）；② 新增 `gitlab_push_files` 批量提交工具；③ `license` 由 `MIT` 改为 `KF` |
| 1.0.0 | 首个版本：20 个工具、零依赖 stdio 实现、`gitlab-mcp.env` 配置加载、GitLab 11.9.9 兼容层、字段裁剪与分页 |
