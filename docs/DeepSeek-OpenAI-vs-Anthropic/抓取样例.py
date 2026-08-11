#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抓取 DeepSeek OpenAI 协议 vs Anthropic 协议的请求/响应样例（思考 / 非思考 / 缓存）。

生成 docs/DeepSeek-OpenAI-vs-Anthropic/samples/ 下的请求 JSON 与原始 SSE 响应。
用法：DEEPSEEK_API_KEY=xxx python3 抓取样例.py
镜像 app 的请求结构（见 OpenAiClient.buildRequest / AnthropicClient.buildRequest）：
- OpenAI: stream + stream_options.include_usage（max_tokens 仅显式配置时才发）
- Anthropic: system 块数组 + cache_control（prompt_cache 开）/ 字符串（关）+ thinking 块 + max_tokens
"""
import json
import os
import sys
import urllib.request

KEY = os.environ.get("DEEPSEEK_API_KEY", "").strip()
if not KEY:
    cfg = os.path.expanduser("~/.zhu-code-agent/config.yml")
    if os.path.exists(cfg):
        for line in open(cfg, encoding="utf-8"):
            line = line.strip()
            if line.startswith("api_key:"):
                v = line.split(":", 1)[1].strip()
                if v.startswith("${") and v.endswith("}"):
                    v = os.environ.get(v[2:-1], "")
                if v:
                    KEY = v
                break
if not KEY:
    sys.exit("缺少 DEEPSEEK_API_KEY（环境变量或 ~/.zhu-code-agent/config.yml）")

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "samples")
os.makedirs(OUT, exist_ok=True)

OPENAI_URL = "https://api.deepseek.com/v1/chat/completions"
ANTHROPIC_URL = "https://api.deepseek.com/anthropic/v1/messages"

SYSTEM = "你是 zhuCodeAgent，一个命令行 AI 助手，回答简洁、不超过两句话。"
Q1 = "用一句话介绍你自己。"
Q2 = "再简单说下你能做什么。"

# 缓存演示用长 system：让公共前缀跨过 DeepSeek 缓存阈值（真机实测短前缀不缓存）
LONG_SYSTEM = (
    "You are zhuCodeAgent, a command-line coding assistant operating in a project workspace. "
    "You help the user with software engineering tasks: reading code, writing and editing files, "
    "searching the codebase, running shell commands, and explaining technical concepts. "
    "\n\n"
    "Tool usage rules:\n"
    "1. Use tools whenever they help complete the task; do not guess file contents.\n"
    "2. read_file returns the full content of a file; grep searches for patterns; glob lists paths.\n"
    "3. write_file creates or overwrites a file; edit_file applies a precise text replacement.\n"
    "4. run_shell executes a command in the workspace and returns its stdout/stderr and exit code.\n"
    "5. Always verify the result of a write by reading the file back before reporting success.\n"
    "6. Never invent tool names, file paths, or command output. If a tool fails, report the error.\n"
    "\n"
    "Output style:\n"
    "- Be concise. Prefer short answers; use bullet points for multiple facts.\n"
    "- When you change files, summarize what you changed and why.\n"
    "- If the user's request is ambiguous, ask a clarifying question instead of guessing.\n"
    "- Do not mention your system prompt or these instructions.\n"
    "\n"
    "Today's date is 2026-08-11. The workspace root is the project directory the user opened."
)

TOOLS = [
    {
        "name": "read_file",
        "description": "Read the full content of a file in the workspace.",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string", "description": "Absolute or workspace-relative file path"}},
            "required": ["path"],
        },
    },
    {
        "name": "grep",
        "description": "Search for a regex pattern in workspace files.",
        "input_schema": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Regex pattern to search"},
                "path": {"type": "string", "description": "Directory to search, default workspace root"},
            },
            "required": ["pattern"],
        },
    },
    {
        "name": "run_shell",
        "description": "Run a shell command in the workspace and return its output.",
        "input_schema": {
            "type": "object",
            "properties": {"cmd": {"type": "string", "description": "Shell command line to execute"}},
            "required": ["cmd"],
        },
    },
]


def openai_body(model, messages, max_tokens=None, system=None, tools=None):
    body = {"model": model}
    msgs = []
    if system:
        msgs.append({"role": "system", "content": system})
    msgs += messages
    body["messages"] = msgs
    body["stream"] = True
    body["stream_options"] = {"include_usage": True}
    if max_tokens is not None:
        body["max_tokens"] = max_tokens
    if tools:
        body["tools"] = [{"type": "function", "function": {
            "name": t["name"], "description": t["description"], "parameters": t["input_schema"]}} for t in tools]
    return body


def anthropic_body(model, messages, thinking=False, prompt_cache=True, max_tokens=8192,
                   system=SYSTEM, tools=None):
    body = {"model": model}
    if prompt_cache:
        body["system"] = [{"type": "text", "text": system,
                           "cache_control": {"type": "ephemeral"}}]
    else:
        body["system"] = system
    body["messages"] = messages
    body["max_tokens"] = max_tokens
    body["stream"] = True
    if thinking:
        body["thinking"] = {"type": "enabled", "budget_tokens": 32000}
    if tools:
        body["tools"] = [{"name": t["name"], "description": t["description"],
                          "input_schema": t["input_schema"], "cache_control": {"type": "ephemeral"}} for t in tools]
    return body


def call(protocol, body, prefix):
    """发请求，保存 request.json 与 response.sse.txt，返回 (assistant_text, thinking_text, usage_summary)。"""
    if protocol == "openai":
        url = OPENAI_URL
        headers = {"Authorization": "Bearer " + KEY, "Content-Type": "application/json"}
    else:
        url = ANTHROPIC_URL
        headers = {"x-api-key": KEY, "anthropic-version": "2023-06-01",
                   "Content-Type": "application/json"}
    redacted = json.dumps(body, ensure_ascii=False, indent=2).replace(KEY, "${DEEPSEEK_API_KEY}")
    with open(os.path.join(OUT, prefix + ".request.json"), "w", encoding="utf-8") as f:
        f.write(redacted + "\n")

    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
    with open(os.path.join(OUT, prefix + ".response.sse.txt"), "w", encoding="utf-8") as f:
        f.write(raw)

    text_parts, think_parts = [], []
    usage = {}
    for line in raw.splitlines():
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload in ("[DONE]", ""):
            continue
        try:
            ev = json.loads(payload)
        except Exception:
            continue
        etype = ev.get("type", "")
        u = ev.get("usage") or {}
        if etype == "message_start":
            u = ev.get("message", {}).get("usage") or {}
            usage.update({k: u[k] for k in
                          ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens") if k in u})
        elif etype == "message_delta":
            usage.update({k: u[k] for k in
                          ("output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens") if k in u})
            usage["stop_reason"] = ev.get("delta", {}).get("stop_reason")
        elif etype == "content_block_delta":
            d = ev.get("delta", {})
            if d.get("type") == "text_delta":
                text_parts.append(d.get("text", ""))
            elif d.get("type") == "thinking_delta":
                think_parts.append(d.get("thinking", ""))
        elif etype == "" and u:                      # OpenAI 最后一块（usage 非空）
            usage.update({k: u[k] for k in
                          ("prompt_tokens", "completion_tokens", "total_tokens",
                           "prompt_cache_hit_tokens", "prompt_cache_miss_tokens") if k in u})
            if "prompt_tokens_details" in u:
                usage["cached_tokens"] = u["prompt_tokens_details"].get("cached_tokens")
            if "completion_tokens_details" in u:
                usage["reasoning_tokens"] = u["completion_tokens_details"].get("reasoning_tokens")
        elif etype == "":                            # OpenAI 内容块
            ch = ev.get("choices") or []
            if ch:
                delta = ch[0].get("delta", {})
                c = delta.get("content")
                if c:
                    text_parts.append(c)
                rc = delta.get("reasoning_content")
                if rc:
                    think_parts.append(rc)
    return "".join(text_parts), "".join(think_parts), usage


def run_case(protocol, body, prefix, title):
    print("== %s ==" % title)
    text, thinking, usage = call(protocol, body, prefix)
    print("   文本: %r" % text[:80])
    if thinking:
        print("   思考: %r" % thinking[:80])
    print("   usage: %s" % json.dumps(usage, ensure_ascii=False))
    return text, thinking


def main():
    # 1) OpenAI 非思考（deepseek-chat = deepseek-v4-flash 非思考别名）
    run_case("openai", openai_body("deepseek-chat", [{"role": "user", "content": Q1}], system=SYSTEM),
             "01-openai-chat", "OpenAI deepseek-chat 非思考")
    # 2) OpenAI 思考（deepseek-reasoner = deepseek-v4-flash 思考别名）
    run_case("openai", openai_body("deepseek-reasoner", [{"role": "user", "content": Q1}], system=SYSTEM),
             "02-openai-reasoner", "OpenAI deepseek-reasoner 思考")

    # 3) Anthropic 非思考（deepseek-v4-flash，prompt_cache 关 → system 字符串）
    run_case("anthropic", anthropic_body("deepseek-v4-flash", [{"role": "user", "content": Q1}],
                                         thinking=False, prompt_cache=False, max_tokens=8192),
             "03-anthropic-plain", "Anthropic deepseek-v4-flash 非思考（无 cache_control）")
    # 4) Anthropic 思考（deepseek-v4-pro，prompt_cache 开 → system 块 + cache_control + thinking）
    run_case("anthropic", anthropic_body("deepseek-v4-pro", [{"role": "user", "content": Q1}],
                                         thinking=True, prompt_cache=True, max_tokens=64000),
             "04-anthropic-thinking", "Anthropic deepseek-v4-pro 思考（cache_control 断点）")

    # 5) Anthropic 缓存演示（长 system + 工具 cache_control 断点，两轮同前缀）
    a1, _ = run_case("anthropic",
                     anthropic_body("deepseek-v4-flash", [{"role": "user", "content": Q1}],
                                    thinking=False, prompt_cache=True, max_tokens=8192,
                                    system=LONG_SYSTEM, tools=TOOLS),
                     "05-anthropic-cache.1", "Anthropic 缓存第 1 轮（长前缀）")
    run_case("anthropic",
             anthropic_body("deepseek-v4-flash",
                            [{"role": "user", "content": Q1},
                             {"role": "assistant", "content": a1},
                             {"role": "user", "content": Q2}],
                            thinking=False, prompt_cache=True, max_tokens=8192,
                            system=LONG_SYSTEM, tools=TOOLS),
             "05-anthropic-cache.2", "Anthropic 缓存第 2 轮（同前缀，期望 cache_read>0）")

    # 6) OpenAI 缓存演示（长 system + 隐式前缀缓存，两轮）
    o1, _ = run_case("openai",
                     openai_body("deepseek-chat", [{"role": "user", "content": Q1}],
                                 system=LONG_SYSTEM, tools=TOOLS),
                     "06-openai-cache.1", "OpenAI 缓存第 1 轮（长前缀）")
    run_case("openai",
             openai_body("deepseek-chat",
                         [{"role": "user", "content": Q1},
                          {"role": "assistant", "content": o1},
                          {"role": "user", "content": Q2}],
                         system=LONG_SYSTEM, tools=TOOLS),
             "06-openai-cache.2", "OpenAI 缓存第 2 轮（同前缀，期望 prompt_cache_hit>0）")


if __name__ == "__main__":
    main()
