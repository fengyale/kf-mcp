#!/usr/bin/env sh
# 手动启动 GitLab MCP Server，仅用于调试。
# 正常使用时由 MCP 客户端自动拉起，无需手动运行。
exec node "$(dirname "$0")/server.js"
