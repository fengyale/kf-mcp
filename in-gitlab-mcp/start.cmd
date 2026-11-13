@echo off
REM 手动启动 GitLab MCP Server，仅用于调试。
REM 正常使用时由 MCP 客户端自动拉起，无需手动运行。
REM 启动信息（api 地址、token 是否已配置）会打印到 stderr。
node "%~dp0server.js"
