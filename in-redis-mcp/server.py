#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
<h2>Redis MCP Server</h2>
本地开发和调试用的 Redis 管理工具，通过 MCP 协议暴露 Redis 操作给 AI 客户端。

<h3>连接方式（优先级从高到低）</h3>
<ol>
  <li>REDIS_URL: redis://[:password@]host:port[/db] — 一键连接</li>
  <li>分项环境变量: REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DB</li>
</ol>

<h3>提供的 Tool</h3>
<ul>
  <li>redis_ping — 连通性检查</li>
  <li>redis_get — 获取 key 值（自动识别类型）</li>
  <li>redis_set — 设置 string key（可选 TTL）</li>
  <li>redis_delete — 删除 key（支持批量）</li>
  <li>redis_scan — 按 pattern 扫描（SCAN 非 KEYS，生产安全）</li>
  <li>redis_ttl — 查看剩余过期时间</li>
  <li>redis_type — 查看数据类型</li>
  <li>redis_keysize — 查看 key 内存占用</li>
  <li>redis_info — 查看 Redis 服务器信息</li>
  <li>redis_flushdb — 清空当前库（需传入 confirm="yes"）</li>
</ul>

<h3>AI 客户端配置示例</h3>
<pre>
{
  "mcpServers": {
    "in-redis": {
      "command": "D:\\Program Files\\Python312\\python.exe",
      "args": ["<替换为 server.py 的绝对路径>"],
      "cwd": "<替换为你的工作目录>",
      "env": {
        "REDIS_URL": "redis://:your-password@localhost:6379/0"
      }
    }
  }
}
</pre>
"""

import asyncio
import os
import json
import logging
import re
from typing import Optional

import redis
from mcp.server.mcpserver import MCPServer

# --- 日志 ---
logging.basicConfig(level=logging.WARNING)
log = logging.getLogger("in-redis-mcp")

# --- Redis 连接 ---

def _mask_redis_url(url: str) -> str:
    """将 Redis URL 中的密码替换为 *** 再输出日志"""
    return re.sub(r'(://)(:[^@]*)(@)', r'\1:***\3', url)


def _create_redis_client() -> redis.Redis:
    """根据环境变量创建 Redis 客户端，优先使用 REDIS_URL"""
    redis_url = os.getenv("REDIS_URL")
    if redis_url:
        # Mask 密码再打日志，避免敏感信息泄露
        safe_url = _mask_redis_url(redis_url)
        log.info("使用 REDIS_URL 连接: %s", safe_url)
        return redis.Redis.from_url(
            redis_url,
            decode_responses=True,
            socket_connect_timeout=5,
            socket_keepalive=True,
        )

    host = os.getenv("REDIS_HOST", "localhost")
    port = int(os.getenv("REDIS_PORT", "6379"))
    password = os.getenv("REDIS_PASSWORD") or None
    db = int(os.getenv("REDIS_DB", "0"))
    log.info("使用分项配置连接: %s:%s db=%s", host, port, db)
    return redis.Redis(
        host=host,
        port=port,
        password=password,
        db=db,
        decode_responses=True,
        socket_connect_timeout=5,
        socket_keepalive=True,
    )

r = _create_redis_client()

# --- MCP Server ---

mcp = MCPServer("in-redis")

# 大 value 截断阈值（字符数）
_MAX_VALUE_LENGTH = 5000
# 集合类型元素数上限，防止大 key 打爆内存
_MAX_COLLECTION_SIZE = 200


def _ok(data: dict) -> str:
    """统一 JSON 序列化"""
    return json.dumps(data, ensure_ascii=False, default=str)


def _error(msg: str) -> str:
    return json.dumps({"error": msg}, ensure_ascii=False)


def _truncate(value: str) -> str:
    """超长字符串截断并标注"""
    if len(value) > _MAX_VALUE_LENGTH:
        return value[:_MAX_VALUE_LENGTH] + f"... [截断，总长 {len(value)} 字符]"
    return value


# --- Tools ---

@mcp.tool()
def redis_ping() -> str:
    """检查 Redis 连接是否正常"""
    try:
        result = r.ping()
        return _ok({"ping": True, "message": "PONG"})
    except Exception as e:
        return _error(f"连接失败: {e}")


@mcp.tool()
def redis_get(key: str) -> str:
    """获取指定 key 的值，自动识别数据类型（string/hash/list/set/zset），大集合自动截断"""
    try:
        key_type = r.type(key)

        if key_type == "none":
            return _ok({"exists": False, "key": key})
        elif key_type == "string":
            value = r.get(key)
            return _ok({
                "exists": True, "key": key, "type": "string",
                "value": _truncate(value),
            })
        elif key_type == "hash":
            # 用 HSCAN 代替 HGETALL，防止大 hash 阻塞
            total = r.hlen(key)
            value = {}
            for field, val in r.hscan_iter(key, count=100):
                value[field] = val
                if len(value) >= _MAX_COLLECTION_SIZE:
                    break
            return _ok({
                "exists": True, "key": key, "type": "hash",
                "value": value, "size": len(value),
                "total": total,
                "truncated": total > _MAX_COLLECTION_SIZE,
            })
        elif key_type == "list":
            # 取前 N 个，避免全量拉取大 list
            total = r.llen(key)
            value = r.lrange(key, 0, _MAX_COLLECTION_SIZE - 1)
            return _ok({
                "exists": True, "key": key, "type": "list",
                "value": value, "length": len(value),
                "total": total,
                "truncated": total > _MAX_COLLECTION_SIZE,
            })
        elif key_type == "set":
            # 用 SSCAN 代替 SMEMBERS，防止大 set 阻塞
            total = r.scard(key)
            value = []
            for member in r.sscan_iter(key, count=100):
                value.append(member)
                if len(value) >= _MAX_COLLECTION_SIZE:
                    break
            return _ok({
                "exists": True, "key": key, "type": "set",
                "value": value, "size": len(value),
                "total": total,
                "truncated": total > _MAX_COLLECTION_SIZE,
            })
        elif key_type == "zset":
            # 取前 N 个，避免全量拉取大 zset
            total = r.zcard(key)
            value = r.zrange(key, 0, _MAX_COLLECTION_SIZE - 1, withscores=True)
            result = [{"member": m, "score": s} for m, s in value]
            return _ok({
                "exists": True, "key": key, "type": "zset",
                "value": result, "size": len(result),
                "total": total,
                "truncated": total > _MAX_COLLECTION_SIZE,
            })
        else:
            return _ok({
                "exists": True, "key": key, "type": key_type,
                "value": "不支持的类型",
            })
    except Exception as e:
        return _error(f"redis_get 失败: {e}")


@mcp.tool()
def redis_set(key: str, value: str, ttl_seconds: Optional[int] = None) -> str:
    """设置 string 类型的 key-value，可选设置过期时间（秒）"""
    try:
        if ttl_seconds and ttl_seconds > 0:
            r.setex(key, ttl_seconds, value)
            return _ok({"ok": True, "key": key, "ttl_seconds": ttl_seconds})
        else:
            r.set(key, value)
            return _ok({"ok": True, "key": key})
    except Exception as e:
        return _error(f"redis_set 失败: {e}")


@mcp.tool()
def redis_delete(keys: list[str]) -> str:
    """删除一个或多个 key，返回成功删除的数量"""
    try:
        count = r.delete(*keys)
        return _ok({"deleted": count})
    except Exception as e:
        return _error(f"redis_delete 失败: {e}")


@mcp.tool()
def redis_scan(pattern: str = "*", count: int = 50) -> str:
    """按 pattern 扫描 key 列表（用 SCAN 命令，生产安全），默认返回前 50 个匹配项"""
    try:
        keys = []
        cursor = 0
        max_iterations = 20  # 最多迭代 20 次，防止海量 key 时拖太久
        iterations = 0
        while iterations < max_iterations:
            cursor, batch = r.scan(cursor, match=pattern, count=min(count, 100))
            keys.extend(batch)
            iterations += 1
            if cursor == 0 or len(keys) >= count:
                break
        result = keys[:count]
        return _ok({
            "pattern": pattern,
            "matched_so_far": len(keys),
            "shown": len(result),
            "keys": result,
        })
    except Exception as e:
        return _error(f"redis_scan 失败: {e}")


@mcp.tool()
def redis_ttl(key: str) -> str:
    """查看 key 的剩余过期时间（秒）。-1 表示永不过期，-2 表示 key 不存在"""
    try:
        ttl = r.ttl(key)
        return _ok({"key": key, "ttl_seconds": ttl})
    except Exception as e:
        return _error(f"redis_ttl 失败: {e}")


@mcp.tool()
def redis_type(key: str) -> str:
    """查看 key 的数据类型和 TTL"""
    try:
        key_type = r.type(key)
        ttl = r.ttl(key) if key_type != "none" else None
        return _ok({"key": key, "type": key_type, "ttl_seconds": ttl})
    except Exception as e:
        return _error(f"redis_type 失败: {e}")


@mcp.tool()
def redis_keysize(key: str) -> str:
    """查看 key 的内存占用（字节），使用 MEMORY USAGE 命令"""
    try:
        usage = r.memory_usage(key)
        if usage is None:
            return _ok({"key": key, "exists": False, "memory_bytes": None})
        return _ok({"key": key, "memory_bytes": usage})
    except Exception as e:
        return _error(f"redis_keysize 失败: {e}")


@mcp.tool()
def redis_info(section: Optional[str] = None) -> str:
    """查看 Redis 服务器信息。可选 section: server/clients/memory/stats/replication/keyspace 等"""
    try:
        if section:
            info = r.info(section)
        else:
            # 默认返回最常用的几个 section 汇总
            info = r.info("server")
            info.update(r.info("clients"))
            info.update(r.info("memory"))
            info.update(r.info("stats"))
            info.update(r.info("keyspace"))
        return _ok({"section": section or "all", "info": info})
    except Exception as e:
        return _error(f"redis_info 失败: {e}")


@mcp.tool()
def redis_flushdb(confirm: str = "") -> str:
    """清空当前数据库的所有 key。必须传入 confirm=\"yes\" 才会执行，否则拒绝"""
    if confirm != "yes":
        return _ok({
            "ok": False,
            "message": "危险操作被拒绝！如需清空，请设置参数 confirm='yes'",
            "hint": "redis_flushdb(confirm='yes')",
        })
    try:
        r.flushdb()
        return _ok({"ok": True, "message": "当前数据库已清空"})
    except Exception as e:
        return _error(f"redis_flushdb 失败: {e}")


# --- Entry ---

if __name__ == "__main__":
    asyncio.run(mcp.run_stdio_async())