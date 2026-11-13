'use strict';
// 本地 GitLab MCP Server —— 零依赖，仅使用 Node 标准库。
// 适配公司内网 GitLab (REST API v4)，通过 MCP stdio (NDJSON) 协议通信。
//
// 环境变量：
//   GITLAB_API_URL               形如 http://your-gitlab.example.com 或 http://your-gitlab.example.com/api/v4
//   GITLAB_PERSONAL_ACCESS_TOKEN Personal Access Token（scope 需含 api）
//   GITLAB_TIMEOUT_MS            请求超时，默认 30000
//
// 也支持在脚本同目录放一个 gitlab-mcp.env（KEY=VALUE 每行一条，# 为注释），
// 这样多个 MCP 客户端可以共用同一份配置；进程已有的环境变量优先级更高。

const http = require('http');
const https = require('https');
const { URL } = require('url');

(function loadEnvFile() {
  const fs = require('fs');
  const path = require('path');
  const names = ['gitlab-mcp.env', '.env'];
  for (let i = 0; i < names.length; i++) {
    const p = path.join(__dirname, names[i]);
    if (!fs.existsSync(p)) continue;
    fs.readFileSync(p, 'utf8').split(/\r?\n/).forEach((line) => {
      const s = line.trim();
      if (!s || s.charAt(0) === '#') return;
      const eq = s.indexOf('=');
      if (eq <= 0) return;
      const k = s.slice(0, eq).trim();
      let v = s.slice(eq + 1).trim();
      if (v.length > 1 &&
        ((v.charAt(0) === '"' && v.charAt(v.length - 1) === '"') ||
         (v.charAt(0) === "'" && v.charAt(v.length - 1) === "'"))) {
        v = v.slice(1, -1);
      }
      if (process.env[k] === undefined || process.env[k] === '') process.env[k] = v;
    });
    break;
  }
})();

const RAW_API_URL = process.env.GITLAB_API_URL || 'http://your-gitlab.example.com';
const API_URL = RAW_API_URL.replace(/\/+$/, '').replace(/\/api\/v4$/i, '') + '/api/v4';
const TOKEN = process.env.GITLAB_PERSONAL_ACCESS_TOKEN || '';
// 容错：GITLAB_TIMEOUT_MS 填了非数字/非正数时回落到默认值，避免 NaN 传进 setTimeout 导致请求秒超时
const TIMEOUT = (function () {
  const n = Number(process.env.GITLAB_TIMEOUT_MS);
  return Number.isFinite(n) && n > 0 ? n : 30000;
})();
const MAX_TEXT = 200000;

// 只读模式：默认【开启】（安全优先）。
// 只有显式设为 0 / false / no / off 才会启用写操作工具。
// 开启时写工具既不会出现在 tools/list 里，被直接调用也会被拒绝。
const READONLY = (function () {
  const v = String(process.env.GITLAB_READONLY === undefined ? '' : process.env.GITLAB_READONLY)
    .trim().toLowerCase();
  return !(v === '0' || v === 'false' || v === 'no' || v === 'off');
})();

// ---------------------------------------------------------------- 工具定义

const TOOLS = [
  {
    name: 'gitlab_list_projects',
    description: '列出或搜索 GitLab 项目。search 为名称关键字，membership=true 只返回当前 token 所属的项目。',
    properties: {
      search: { type: 'string', description: '按项目名称/路径搜索的关键字' },
      membership: { type: 'boolean', description: '仅返回当前用户所属的项目' },
      owned: { type: 'boolean', description: '仅返回当前用户拥有的项目' },
      simple: { type: 'boolean', description: '返回精简字段' },
      order_by: { type: 'string', description: '排序字段，如 id/name/last_activity_at' },
      sort: { type: 'string', description: 'asc 或 desc' },
      page: { type: 'number', description: '页码，默认 1' },
      per_page: { type: 'number', description: '每页条数，默认 20，最大 100' }
    },
    run: (a) => list('/projects', a, pick(a, ['search', 'membership', 'owned', 'simple', 'order_by', 'sort']))
  },
  {
    name: 'gitlab_get_project',
    description: '获取单个项目的详细信息（默认分支、可见性、URL 等）。',
    properties: { project_id: { type: 'string', description: '项目 ID（数字）或完整路径 namespace/project' } },
    required: ['project_id'],
    run: (a) => api('GET', '/projects/' + projectPath(a.project_id))
  },
  {
    name: 'gitlab_list_project_members',
    description: '列出项目成员及其权限级别。',
    properties: {
      project_id: { type: 'string', description: '项目 ID 或 namespace/project' },
      query: { type: 'string', description: '按成员姓名/用户名过滤' },
      page: { type: 'number' },
      per_page: { type: 'number' }
    },
    required: ['project_id'],
    run: (a) => list('/projects/' + projectPath(a.project_id) + '/members/all', a, pick(a, ['query']))
  },
  {
    name: 'gitlab_list_branches',
    description: '列出项目分支，可按名称搜索。',
    properties: {
      project_id: { type: 'string' },
      search: { type: 'string', description: '分支名关键字' },
      page: { type: 'number' },
      per_page: { type: 'number' }
    },
    required: ['project_id'],
    run: (a) => list('/projects/' + projectPath(a.project_id) + '/repository/branches', a, pick(a, ['search']))
  },
  {
    name: 'gitlab_get_file',
    description: '读取仓库中某个文件的原始内容。file_path 为仓库内相对路径。',
    properties: {
      project_id: { type: 'string' },
      file_path: { type: 'string', description: '文件路径，如 src/main/App.java' },
      ref: { type: 'string', description: '分支名 / tag / commit sha，默认项目默认分支' }
    },
    required: ['project_id', 'file_path'],
    run: async (a) => {
      const path = '/projects/' + projectPath(a.project_id) + '/repository/files/' +
        encodeURIComponent(a.file_path) + '/raw';
      return await api('GET', path, { query: { ref: a.ref }, raw: true });
    }
  },
  {
    name: 'gitlab_list_commits',
    description: '列出提交记录，支持按分支、时间范围过滤。',
    properties: {
      project_id: { type: 'string' },
      ref_name: { type: 'string', description: '分支名 / tag，默认默认分支' },
      since: { type: 'string', description: '起始时间，ISO8601，如 2026-09-01T00:00:00Z' },
      until: { type: 'string', description: '结束时间，ISO8601' },
      path: { type: 'string', description: '仅该文件/目录的提交' },
      author: { type: 'string', description: '提交人邮箱或姓名' },
      with_stats: { type: 'boolean', description: '附带增删行数统计' },
      page: { type: 'number' },
      per_page: { type: 'number' }
    },
    required: ['project_id'],
    run: (a) => list('/projects/' + projectPath(a.project_id) + '/repository/commits', a,
      pick(a, ['ref_name', 'since', 'until', 'path', 'author', 'with_stats']))
  },
  {
    name: 'gitlab_get_commit',
    description: '获取单个提交的详情与文件变更 diff。',
    properties: {
      project_id: { type: 'string' },
      sha: { type: 'string', description: 'commit sha' }
    },
    required: ['project_id', 'sha'],
    run: (a) => api('GET', '/projects/' + projectPath(a.project_id) + '/repository/commits/' + encodeURIComponent(a.sha))
  },
  {
    name: 'gitlab_list_issues',
    description: '列出项目 Issue，可按状态、标签、关键字过滤。',
    properties: {
      project_id: { type: 'string', description: '留空则查询全局 Issue' },
      state: { type: 'string', description: 'opened / closed / all' },
      search: { type: 'string' },
      labels: { type: 'string', description: '标签，多个用英文逗号分隔' },
      assignee_username: { type: 'string' },
      page: { type: 'number' },
      per_page: { type: 'number' }
    },
    run: (a) => list((a.project_id ? '/projects/' + projectPath(a.project_id) : '') + '/issues', a,
      pick(a, ['state', 'search', 'labels', 'assignee_username']))
  },
  {
    name: 'gitlab_get_issue',
    description: '获取单个 Issue 详情（使用项目内 IID）。',
    properties: {
      project_id: { type: 'string' },
      issue_iid: { type: 'number', description: 'Issue 的 IID（项目内编号）' }
    },
    required: ['project_id', 'issue_iid'],
    run: (a) => api('GET', '/projects/' + projectPath(a.project_id) + '/issues/' + num(a.issue_iid, 'issue_iid'))
  },
  {
    name: 'gitlab_create_issue',
    write: true,
    description: '在项目中创建 Issue。',
    properties: {
      project_id: { type: 'string' },
      title: { type: 'string' },
      description: { type: 'string' },
      labels: { type: 'string', description: '多个用英文逗号分隔' },
      assignee_ids: { type: 'array', items: { type: 'number' }, description: '指派人 user id 列表' }
    },
    required: ['project_id', 'title'],
    run: (a) => api('POST', '/projects/' + projectPath(a.project_id) + '/issues',
      { body: pick(a, ['title', 'description', 'labels', 'assignee_ids']) })
  },
  {
    name: 'gitlab_list_merge_requests',
    description: '列出合并请求（MR），可按状态、源/目标分支、关键字过滤。',
    properties: {
      project_id: { type: 'string', description: '留空则查询当前用户可见的全局 MR' },
      state: { type: 'string', description: 'opened / closed / merged / all' },
      source_branch: { type: 'string' },
      target_branch: { type: 'string' },
      author_username: { type: 'string' },
      search: { type: 'string' },
      page: { type: 'number' },
      per_page: { type: 'number' }
    },
    run: (a) => list((a.project_id ? '/projects/' + projectPath(a.project_id) : '') + '/merge_requests', a,
      pick(a, ['state', 'source_branch', 'target_branch', 'author_username', 'search']))
  },
  {
    name: 'gitlab_get_merge_request',
    description: '获取单个 MR 详情（使用项目内 IID）。',
    properties: {
      project_id: { type: 'string' },
      mr_iid: { type: 'number' }
    },
    required: ['project_id', 'mr_iid'],
    run: (a) => api('GET', '/projects/' + projectPath(a.project_id) + '/merge_requests/' + num(a.mr_iid, 'mr_iid'))
  },
  {
    name: 'gitlab_get_merge_request_changes',
    description: '获取 MR 的代码变更 diff（含逐文件新旧内容）。',
    properties: {
      project_id: { type: 'string' },
      mr_iid: { type: 'number' }
    },
    required: ['project_id', 'mr_iid'],
    run: (a) => api('GET', '/projects/' + projectPath(a.project_id) + '/merge_requests/' + num(a.mr_iid, 'mr_iid') + '/changes')
  },
  {
    name: 'gitlab_create_merge_request',
    write: true,
    description: '创建合并请求。',
    properties: {
      project_id: { type: 'string' },
      source_branch: { type: 'string' },
      target_branch: { type: 'string' },
      title: { type: 'string' },
      description: { type: 'string' },
      remove_source_branch: { type: 'boolean', description: '合并后删除源分支' },
      assignee_id: { type: 'number' }
    },
    required: ['project_id', 'source_branch', 'target_branch', 'title'],
    run: (a) => api('POST', '/projects/' + projectPath(a.project_id) + '/merge_requests',
      { body: pick(a, ['source_branch', 'target_branch', 'title', 'description', 'remove_source_branch', 'assignee_id']) })
  },
  {
    name: 'gitlab_create_merge_request_note',
    write: true,
    description: '在 MR 下发表评论。body 中可用 ```suggestion 语法提交代码建议。',
    properties: {
      project_id: { type: 'string' },
      mr_iid: { type: 'number' },
      body: { type: 'string', description: '评论内容（Markdown）' }
    },
    required: ['project_id', 'mr_iid', 'body'],
    run: (a) => api('POST', '/projects/' + projectPath(a.project_id) + '/merge_requests/' + num(a.mr_iid, 'mr_iid') + '/notes',
      { body: { body: a.body } })
  },
  {
    name: 'gitlab_merge_merge_request',
    write: true,
    description: '合并一个已就绪的 MR。',
    properties: {
      project_id: { type: 'string' },
      mr_iid: { type: 'number' },
      merge_commit_message: { type: 'string' },
      squash: { type: 'boolean', description: '是否压缩提交' },
      should_remove_source_branch: { type: 'boolean' }
    },
    required: ['project_id', 'mr_iid'],
    run: (a) => api('PUT', '/projects/' + projectPath(a.project_id) + '/merge_requests/' + num(a.mr_iid, 'mr_iid') + '/merge',
      { body: pick(a, ['merge_commit_message', 'squash', 'should_remove_source_branch']) })
  },
  {
    name: 'gitlab_create_branch',
    write: true,
    description: '基于某个 ref 创建新分支。',
    properties: {
      project_id: { type: 'string' },
      branch: { type: 'string', description: '新分支名' },
      ref: { type: 'string', description: '来源分支名 / sha' }
    },
    required: ['project_id', 'branch', 'ref'],
    run: (a) => api('POST', '/projects/' + projectPath(a.project_id) + '/repository/branches',
      { query: { branch: a.branch, ref: a.ref } })
  },
  {
    name: 'gitlab_create_or_update_file',
    write: true,
    description: '在指定分支创建或更新文件（自动判断走新建还是更新，产生一个新提交）。content 为明文即可，编码方式由 encoding 指定。',
    properties: {
      project_id: { type: 'string' },
      file_path: { type: 'string' },
      branch: { type: 'string' },
      content: { type: 'string', description: '文件完整内容（明文）' },
      commit_message: { type: 'string' },
      encoding: { type: 'string', description: 'base64 或 text，默认 text' }
    },
    required: ['project_id', 'file_path', 'branch', 'content', 'commit_message'],
    run: async (a) => {
      const p = projectPath(a.project_id);
      const fp = encodeURIComponent(a.file_path);
      const body = {
        branch: a.branch,
        content: a.content,
        commit_message: a.commit_message,
        encoding: a.encoding || 'text'
      };
      // 先探测文件是否已存在：仅 404 视为不存在（走 POST 新建），其余错误（401/403/500 等）直接抛出，
      // 否则会把鉴权失败误判成“文件不存在”，最后报出一个让人摸不着头脑的 400
      let exists = true;
      try {
        await api('GET', '/projects/' + p + '/repository/files/' + fp, { query: { ref: a.branch } });
      } catch (e) {
        if (e.status !== 404) throw e;
        exists = false;
      }
      return await api(exists ? 'PUT' : 'POST', '/projects/' + p + '/repository/files/' + fp, { body });
    }
  },
  {
    name: 'gitlab_push_files',
    write: true,
    description: '在【同一个提交】里批量新增 / 修改 / 删除 / 移动多个文件（GitLab Commits API 的 actions）。一次改动涉及多个文件时用它，避免产生一堆碎提交。整批操作是原子的：只要有一个文件失败，整个提交都不会产生。',
    properties: {
      project_id: { type: 'string' },
      branch: { type: 'string', description: '提交到哪个分支' },
      commit_message: { type: 'string', description: '提交信息' },
      start_branch: { type: 'string', description: '可选：当 branch 还不存在时，以该分支为起点创建' },
      files: {
        type: 'array',
        description: '文件操作列表，至少一项',
        items: {
          type: 'object',
          properties: {
            action: { type: 'string', description: 'create（新增）/ update（修改）/ delete（删除）/ move（移动或重命名）' },
            file_path: { type: 'string', description: '仓库内相对路径，如 src/main/App.java' },
            content: { type: 'string', description: '文件内容（明文）；create / update / move 必填，delete 不需要' },
            previous_path: { type: 'string', description: '仅 move 需要：原路径' },
            encoding: { type: 'string', description: 'text（默认）或 base64；老版本 GitLab 可能忽略此项' }
          },
          required: ['action', 'file_path']
        }
      },
      author_name: { type: 'string', description: '可选：覆盖提交人姓名' },
      author_email: { type: 'string', description: '可选：覆盖提交人邮箱' }
    },
    required: ['project_id', 'branch', 'commit_message', 'files'],
    run: async (a) => {
      if (!Array.isArray(a.files) || a.files.length === 0) {
        throw new Error('files 必须是非空数组，且每项至少要有 action 和 file_path');
      }
      const ALLOWED = ['create', 'update', 'delete', 'move'];
      // 先做完整的本地校验，参数有问题时一个字节都不会发出去
      const actions = a.files.map((f) => {
        const act = String((f && f.action) || '').toLowerCase();
        if (ALLOWED.indexOf(act) === -1) {
          throw new Error('不支持的动作「' + ((f && f.action) || '') + '」，仅支持 create / update / delete / move');
        }
        if (!f.file_path) throw new Error('每个文件项都必须提供 file_path');
        const item = { action: act, file_path: f.file_path };
        if (act === 'move') {
          if (!f.previous_path) throw new Error('move 动作必须提供 previous_path：' + f.file_path);
          item.previous_path = f.previous_path;
        }
        if (act === 'delete') return item;
        if (typeof f.content !== 'string') {
          throw new Error(act + ' 动作必须提供 content 字符串：' + f.file_path);
        }
        item.content = f.content;
        if (f.encoding) item.encoding = f.encoding;
        return item;
      });
      const body = { branch: a.branch, commit_message: a.commit_message, actions: actions };
      if (a.start_branch) body.start_branch = a.start_branch;
      if (a.author_name) body.author_name = a.author_name;
      if (a.author_email) body.author_email = a.author_email;

      const commit = await api('POST', '/projects/' + projectPath(a.project_id) + '/repository/commits', { body });

      const lines = [];
      lines.push('提交成功：' + ((commit && (commit.short_id || commit.id)) || '（服务端未返回 sha）') +
        (commit && commit.title ? '  ' + commit.title : ''));
      if (commit && commit.web_url) lines.push('链接：' + commit.web_url);
      lines.push('本次共 ' + actions.length + ' 个文件操作，全部落在同一个提交里：');
      actions.forEach((x) => {
        lines.push('  [' + x.action + '] ' + x.file_path +
          (x.previous_path ? '（原路径 ' + x.previous_path + '）' : ''));
      });
      lines.push('');
      lines.push(JSON.stringify(commit, null, 2));
      return lines.join('\n');
    }
  },
  {
    name: 'gitlab_search_in_project',
    description: '在项目内搜索代码（blobs）、提交、Issue 或 MR。',
    properties: {
      project_id: { type: 'string' },
      scope: { type: 'string', description: 'blobs / commits / issues / merge_requests，默认 blobs' },
      search: { type: 'string', description: '搜索关键字' },
      ref: { type: 'string', description: 'scope=blobs 时的分支名' },
      page: { type: 'number' },
      per_page: { type: 'number' }
    },
    required: ['project_id', 'search'],
    run: (a) => list('/projects/' + projectPath(a.project_id) + '/search', a,
      Object.assign({ scope: a.scope || 'blobs' }, pick(a, ['search', 'ref'])))
  },
  {
    name: 'gitlab_list_pipelines',
    description: '列出项目的流水线（CI/CD）。',
    properties: {
      project_id: { type: 'string' },
      ref: { type: 'string', description: '分支名' },
      status: { type: 'string', description: 'running / pending / success / failed / canceled' },
      page: { type: 'number' },
      per_page: { type: 'number' }
    },
    required: ['project_id'],
    run: (a) => list('/projects/' + projectPath(a.project_id) + '/pipelines', a, pick(a, ['ref', 'status']))
  }
];

// ---------------------------------------------------------------- HTTP 调用

function req(method, path, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    if (!TOKEN) {
      return reject(new Error('未配置 GITLAB_PERSONAL_ACCESS_TOKEN。请在 ~/.codebuddy/mcp.json 的 GitLab.env 中填入内网 GitLab 的 Personal Access Token（scope 需勾选 api）。'));
    }
    let u;
    try {
      u = new URL(API_URL + path);
    } catch (e) {
      return reject(new Error('URL 拼接失败: ' + API_URL + path));
    }
    if (opts.query) {
      Object.keys(opts.query).forEach((k) => {
        const v = opts.query[k];
        if (v === undefined || v === null || v === '') return;
        u.searchParams.set(k, String(v));
      });
    }
    const payload = opts.body ? JSON.stringify(opts.body) : null;
    const headers = {
      'PRIVATE-TOKEN': TOKEN,
      'Accept': 'application/json',
      'User-Agent': 'CodeBuddy-GitLab-MCP/1.0'
    };
    if (payload) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(payload);
    }
    const lib = u.protocol === 'https:' ? https : http;
    const r = lib.request(u, { method: method, headers: headers, timeout: TIMEOUT }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: data }));
    });
    r.on('error', (e) => reject(new Error('网络错误（' + u.host + '）：' + e.message)));
    r.on('timeout', () => r.destroy(new Error('请求超时（' + TIMEOUT + 'ms）：' + u.pathname)));
    if (payload) r.write(payload);
    r.end();
  });
}

async function api(method, path, opts) {
  opts = opts || {};
  const res = await req(method, path, opts);
  let parsed = null;
  if (res.text) {
    try { parsed = JSON.parse(res.text); } catch (e) { parsed = res.text; }
  }
  if (res.status >= 400) {
    let msg;
    if (parsed && typeof parsed === 'object') {
      msg = parsed.message || parsed.error || JSON.stringify(parsed);
      if (Array.isArray(msg)) msg = msg.join('; ');
    } else {
      msg = String(parsed || '').slice(0, 500);
    }
    if (res.status === 401) msg += '（401：token 无效、过期或缺少 api scope）';
    if (res.status === 404) msg += '（404：项目/资源不存在，或 token 无该仓库权限）';
    const err = new Error('GitLab API ' + res.status + ' ' + method + ' ' + path + ' -> ' + msg);
    err.status = res.status;
    throw err;
  }
  if (opts.raw) return typeof parsed === 'string' ? parsed : res.text;
  return parsed;
}

async function list(path, args, query) {
  const q = Object.assign({}, query);
  q.page = args && args.page ? Number(args.page) : 1;
  q.per_page = args && args.per_page ? Math.min(Number(args.per_page), 100) : 20;
  const res = await req('GET', path, { query: q });
  let parsed = null;
  try { parsed = JSON.parse(res.text); } catch (e) { parsed = res.text; }
  if (res.status >= 400) {
    let msg = parsed && typeof parsed === 'object' ? (parsed.message || parsed.error || '') : String(parsed || '');
    if (Array.isArray(msg)) msg = msg.join('; ');
    if (res.status === 401) msg += '（401：token 无效、过期或缺少 api scope）';
    if (res.status === 404) msg += '（404：项目/资源不存在，或 token 无该仓库权限）';
    const err = new Error('GitLab API ' + res.status + ' GET ' + path + ' -> ' + msg);
    err.status = res.status;
    throw err;
  }
  if (!Array.isArray(parsed)) return parsed;
  const total = res.headers['x-total'] || '?';
  const next = res.headers['x-next-page'] || '';
  const footer = '\n--- 分页：第 ' + q.page + ' 页，每页 ' + q.per_page + '，共 ' + total +
    ' 条；' + (next ? '下一页请传 page=' + next : '已是最后一页');
  if (parsed.length === 0) return '（无数据）' + footer;
  return JSON.stringify(parsed.map(slim), null, 2) + footer;
}

// 列表接口精简输出，避免冗余字段把上下文撑爆
const KEEP_FIELDS = [
  'id', 'iid', 'project_id', 'name', 'name_with_namespace', 'path', 'path_with_namespace',
  'title', 'state', 'status', 'web_url', 'http_url_to_repo', 'ssh_url_to_repo', 'default_branch',
  'author', 'assignee', 'assignees', 'labels', 'created_at', 'updated_at', 'last_activity_at',
  'source_branch', 'target_branch', 'merge_status', 'has_conflicts', 'draft', 'work_in_progress',
  'message', 'short_id', 'committed_date', 'authored_date', 'merged_at', 'closed_at', 'due_date',
  'merged', 'username', 'access_level', 'description', 'ref', 'sha', 'coverage', 'user_defined',
  // 代码搜索（scope=blobs）命中结果字段，老版本 GitLab 用 filename 而非 path
  'type', 'basename', 'filename', 'data', 'startline'
];

const CLIP_LEN = 4000;

function clip(v) {
  return (typeof v === 'string' && v.length > CLIP_LEN)
    ? v.slice(0, CLIP_LEN) + '...[已截断]'
    : v;
}

function slim(obj) {
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) return clip(obj);
  const out = {};
  KEEP_FIELDS.forEach((k) => {
    if (Object.prototype.hasOwnProperty.call(obj, k)) out[k] = clip(obj[k]);
  });
  // 命中字段过少说明是特殊结构（如代码搜索命中），改为保留全部字段并裁剪超长值
  if (Object.keys(out).length < 2) {
    Object.keys(obj).forEach((k) => { out[k] = clip(obj[k]); });
  }
  return out;
}

// ---------------------------------------------------------------- 参数工具

function pick(obj, keys) {
  const out = {};
  keys.forEach((k) => {
    if (obj && Object.prototype.hasOwnProperty.call(obj, k)) out[k] = obj[k];
  });
  return out;
}

function projectPath(v) {
  if (v === undefined || v === null || String(v).trim() === '') {
    throw new Error('缺少参数 project_id（项目数字 ID 或 namespace/project）');
  }
  const s = String(v).trim();
  return /^\d+$/.test(s) ? s : encodeURIComponent(s);
}

function num(v, name) {
  const n = Number(v);
  if (!Number.isFinite(n)) throw new Error('参数 ' + name + ' 必须是数字');
  return n;
}

// ---------------------------------------------------------------- MCP 协议

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

function textResult(text, isError) {
  let t = typeof text === 'string' ? text : JSON.stringify(text, null, 2);
  if (t.length > MAX_TEXT) t = t.slice(0, MAX_TEXT) + '\n...[输出过长已截断]';
  return { content: [{ type: 'text', text: t }], isError: !!isError };
}

async function handle(msg) {
  if (msg.id === undefined || msg.id === null) return; // 通知类消息无需响应
  const id = msg.id;
  const method = msg.method;
  const params = msg.params || {};

  if (method === 'initialize') {
    const pv = params.protocolVersion || '2024-11-05';
    return send({
      jsonrpc: '2.0', id: id,
      result: {
        protocolVersion: pv,
        capabilities: { tools: {} },
        serverInfo: { name: 'in-gitlab-mcp', version: '1.1.0' }
      }
    });
  }

  if (method === 'tools/list') {
    // 只读模式下不把写工具暴露给客户端，从源头消除误调用的可能
    const visible = READONLY ? TOOLS.filter((t) => !t.write) : TOOLS;
    return send({
      jsonrpc: '2.0', id: id,
      result: { tools: visible.map((t) => ({ name: t.name, description: t.description, inputSchema: schema(t) })) }
    });
  }

  if (method === 'tools/call') {
    const tool = TOOLS.filter((t) => t.name === params.name)[0];
    if (!tool) {
      return send({ jsonrpc: '2.0', id: id, error: { code: -32602, message: '未知工具: ' + params.name } });
    }
    // 双保险：即使客户端缓存了旧的工具清单直接发起调用，这里也会拦住
    if (READONLY && tool.write) {
      return send({ jsonrpc: '2.0', id: id, result: textResult(
        '「' + tool.name + '」是写操作工具，当前服务运行在只读模式，已拒绝执行。\n' +
        '如需启用写操作：在 gitlab-mcp.env 中显式设置 GITLAB_READONLY=0，' +
        '并确认 token 具备相应权限（注意这会把写能力真正打开）。', true) });
    }
    const args = params.arguments || {};
    const missing = (tool.required || []).filter((k) => args[k] === undefined || args[k] === null || args[k] === '');
    if (missing.length) {
      return send({ jsonrpc: '2.0', id: id, result: textResult('缺少必填参数: ' + missing.join(', '), true) });
    }
    try {
      const out = await tool.run(args);
      return send({ jsonrpc: '2.0', id: id, result: textResult(out) });
    } catch (e) {
      return send({ jsonrpc: '2.0', id: id, result: textResult(e.message || String(e), true) });
    }
  }

  if (method === 'ping') {
    return send({ jsonrpc: '2.0', id: id, result: {} });
  }

  if (method === 'resources/list') {
    return send({ jsonrpc: '2.0', id: id, result: { resources: [] } });
  }

  if (method === 'prompts/list') {
    return send({ jsonrpc: '2.0', id: id, result: { prompts: [] } });
  }

  send({ jsonrpc: '2.0', id: id, error: { code: -32601, message: '方法未实现: ' + method } });
}

function schema(tool) {
  const s = { type: 'object', properties: tool.properties || {} };
  if (tool.required && tool.required.length) s.required = tool.required;
  return s;
}

// ---------------------------------------------------------------- 启动

let buffer = '';
let pending = 0;
let stdinEnded = false;

function maybeExit() {
  if (stdinEnded && pending === 0) process.exit(0);
}

process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) !== -1) {
    const line = buffer.slice(0, idx).trim();
    buffer = buffer.slice(idx + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch (e) { continue; }
    pending++;
    Promise.resolve()
      .then(() => handle(msg))
      .catch((e) => {
        if (msg && msg.id !== undefined) {
          send({ jsonrpc: '2.0', id: msg.id, result: textResult('内部错误: ' + (e && e.message), true) });
        }
      })
      .then(() => { pending--; maybeExit(); });
  }
});
// stdin 关闭表示客户端已断开：等在途请求写完再退出，避免丢掉最后一个响应
process.stdin.on('end', () => { stdinEnded = true; maybeExit(); });

process.stderr.write('[in-gitlab-mcp] started, api=' + API_URL + ', token=' + (TOKEN ? '已配置' : '未配置') +
  ', mode=' + (READONLY
    ? '只读（写工具已隐藏，可用 ' + TOOLS.filter((t) => !t.write).length + ' 个）'
    : '读写（可用 ' + TOOLS.length + ' 个）') + '\n');
