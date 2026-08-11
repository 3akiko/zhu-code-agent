# DeepSeek 协议对比：OpenAI 兼容端点 vs Anthropic 兼容端点

> 抓取日期：2026-08-11 · 模型：deepseek-v4-flash / deepseek-v4-pro（响应中可见，deepseek-chat / deepseek-reasoner 为 v4-flash 的 OpenAI 别名）
> 用途：对比两种协议经 DeepSeek 的**请求结构、SSE 响应结构、usage/缓存字段语义**（M4 F1/F4 占用率与缓存展示口径的依据）。

## 一、如何重新生成

```bash
cd docs/DeepSeek-OpenAI-vs-Anthropic
DEEPSEEK_API_KEY=sk-xxx python3 抓取样例.py   # 需联网；自动覆盖 samples/ 下文件
```

请求 JSON 与原始 SSE 响应保存在 `samples/`。**API key 只出现在请求头，不落盘**（请求体无 key）。

## 二、样例清单

| 文件 | 协议 | 模型 | 模式 | 说明 |
|---|---|---|---|---|
| `01-openai-chat.*` | OpenAI | deepseek-chat | 非思考 | 基础请求/响应，含 usage 末块 |
| `02-openai-reasoner.*` | OpenAI | deepseek-reasoner | 思考 | `delta.reasoning_content` 思考 + `reasoning_tokens` |
| `03-anthropic-plain.*` | Anthropic | deepseek-v4-flash | 非思考（无 cache_control） | 注意：端点仍返回 thinking 块 |
| `04-anthropic-thinking.*` | Anthropic | deepseek-v4-pro | 思考（cache_control 断点） | system 块数组 + thinking 参数 |
| `05-anthropic-cache.1/2.*` | Anthropic | deepseek-v4-flash | 非思考 + 长前缀 | 第 2 轮同前缀，演示 `cache_read_input_tokens` |
| `06-openai-cache.1/2.*` | OpenAI | deepseek-chat | 非思考 + 长前缀 | 第 2 轮同前缀，演示 `prompt_cache_hit_tokens` |

## 三、请求结构差异

| | OpenAI（`POST /v1/chat/completions`） | Anthropic（`POST /anthropic/v1/messages`） |
|---|---|---|
| 鉴权头 | `Authorization: Bearer <key>` | `x-api-key: <key>` + `anthropic-version` |
| system | 首条 `role=system` 消息 | 顶层 `system` 字段（字符串或带 `cache_control` 的块数组） |
| 流式 | `stream: true` + `stream_options.include_usage` | `stream: true` |
| 思考 | 模型名切换（deepseek-reasoner），无专门字段 | `thinking: {type: enabled, budget_tokens: 32000}` |
| max_tokens | 仅显式配置才发（默认不发） | 总是发（模型表：plain 8192 / thinking 64000） |
| 缓存断点 | 无（DeepSeek 隐式前缀缓存） | system/工具定义上 `cache_control: {type: ephemeral}` |

## 四、响应 & usage 字段差异（核心）

### usage 语义

| 字段 | OpenAI | Anthropic |
|---|---|---|
| 主输入 | `prompt_tokens` = **缓存命中 + 未命中**（含缓存） | `input_tokens` = **仅未缓存部分** |
| 缓存命中 | `prompt_cache_hit_tokens`（另见 `prompt_tokens_details.cached_tokens`） | `cache_read_input_tokens` |
| 缓存创建 | （无） | `cache_creation_input_tokens` |
| 输出 | `completion_tokens`（+ `completion_tokens_details.reasoning_tokens`） | `output_tokens` |

### 实测缓存数字（同前缀两轮，长 system + 工具定义）

| 第 2 轮 | OpenAI（deepseek-chat） | Anthropic（deepseek-v4-flash） |
|---|---|---|
| 主输入 | `prompt_tokens = 723` | `input_tokens = 42` |
| 缓存命中 | `prompt_cache_hit_tokens = 640`（**已含在 723 内**） | `cache_read_input_tokens = 768`（**额外，不含在 42 内**） |
| 缓存未命中 | `prompt_cache_miss_tokens = 83` | — |
| 真实输入合计 | 723 | **42 + 768 = 810** |

> 结论：**OpenAI 端点的 `prompt_tokens` 已把缓存包含在内**（723 = 640 + 83，官方文档原文即 `prompt_tokens = prompt_cache_hit_tokens + prompt_cache_miss_tokens`）。
> **Anthropic 端点的 `input_tokens` 只算未缓存部分**，缓存命中单独在 `cache_read_input_tokens`。若只用 `input_tokens` 算上下文占用，会严重低估（本例 810 只显示 42）。

## 五、其他发现

1. **`deepseek-v4-flash` 在 anthropic 端点上即使不传 `thinking` 也返回 thinking 块**（见 `03-anthropic-plain.response.sse.txt`：`content_block_start` 直接是 `type=thinking`）——v4 系列在 anthropic 端点为默认思考模型，`thinking` 参数更像开关/提示而非强制。
2. OpenAI 思考（deepseek-reasoner）思考内容走 `delta.reasoning_content`，统计在 `completion_tokens_details.reasoning_tokens`；Anthropic 走 `content_block_delta(delta.type=thinking_delta)`，无独立 reasoning 统计字段。
3. 缓存需要**足够长的公共前缀**才会命中（真机实测短 prompt 两轮均 cache=0；长 system+工具定义后第 2 轮命中）。Anthropic 端点还需显式 `cache_control` 断点。
4. 响应里 `model` 字段可确认别名映射：`deepseek-chat` / `deepseek-reasoner` → `deepseek-v4-flash`。

## 六、对 M4 占用率口径的影响

- **OpenAI 协议路径**（`deepseek` / `deepseek-reasoner` provider）：`prompt_tokens` 含缓存 → 现有占用率计算**正确，无需改**。
- **Anthropic 协议路径**（`deepseek-anthropic` / `claude` provider）：`input_tokens` 不含缓存 → 占用率**低估**，告警/自动压缩阈值可能永不触发 → 需按协议感知调整（anthropic 占用基数 = `input_tokens + cache_read_input_tokens`）。
