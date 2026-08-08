# M2 工具执行本地 Demo（读 / 写 / 改 / 跑命令）

> 目的：在本地真实跑通「模型调工具 → 权限确认 → 执行 → 结果回填」的完整链路。
> 本 Demo 用真实 DeepSeek API + 一个独立工作区，**不污染项目仓库**。
> 依赖：JDK 21+、Maven、`DEEPSEEK_API_KEY` 环境变量。
> 验证日期：2026-08-09（已实测通过）。

## 前置：打包 + 配置

```bash
# 1) 打包（在项目根目录）
cd /Users/huangdazhu/IdeaProjects/zhu-code-agent
mvn -q package -DskipTests          # 产出 target/zhu-code-agent.jar

# 2) 独立 demo 工作区（工具只允许写这里，避免污染项目）
mkdir -p /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects && cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects

# 3) 配置（DeepSeek anthropic 兼容格式，thinking 灰字可用）
cat > /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m2-demo-config.yml << 'CFG'
providers:
  - name: deepseek-anthropic
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: ${DEEPSEEK_API_KEY}
    thinking: true
tool:
  max_calls_per_turn: 60
ui:
  tool_preview_lines: 5
CFG
```

## 启动

```bash
cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects
java -jar /Users/huangdazhu/IdeaProjects/zhu-code-agent/target/zhu-code-agent.jar --config /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m2-demo-config.yml
```

- 出现「选择会话」→ 回车选 **新建对话**（或选中历史会话恢复，也可验证工具上下文）。
- 进入聊天后看到 `> ` 提示符。

## 四步 Demo（照着输入即可）

### ① 写文件：创建 Hello.java（触发 write_file 权限确认）

输入：

```
用 write_file 工具创建 Hello.java，内容是一个打印 "hello world" 的 Java 程序
```

预期：模型思考（灰字）→ `🔧 write_file Hello.java` → 弹出权限确认：

```
[权限] write_file Hello.java → 允许(a) / 拒绝(d) / 总是允许本次(s)？(输入后回车)
```

输入 `a` + 回车（允许本次）。随后看到 `└ write_file → 已写入 117 字节 → /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/Hello.java` 与最终答复。

> 想体验「总是允许」：这里输入 `s`，之后对**同一路径**的写操作不再询问（程序运行内有效，退出重置，可用 `/permissions` 查看）。

### ② 读文件：验证只读工具自动放行（无需确认）

输入：

```
用 read_file 读取 Hello.java 前 5 行
```

预期：**不弹权限确认**（只读自动执行）→ `🔧 read_file Hello.java` → 结果预览 5 行 + 「…已截断，共 N 行」标注。

### ③ 改文件 + 跑命令：edit_file + bash（都需确认）

输入：

```
用 edit_file 把 Hello.java 里的 "hello world" 改成 "hello zhuCodeAgent"，然后用 bash 运行 javac Hello.java && java Hello 看输出
```

预期：两次权限确认（`edit_file` 一次、`bash` 一次），各自输入 `a` + 回车 →
- `└ edit_file → 已替换 1 处 → .../Hello.java`
- `└ bash → hello zhuCodeAgent`
- 最终答复输出 `hello zhuCodeAgent`

### ④ （可选）查看权限状态

```
/permissions          # 查看「总是允许」清单（本次程序运行内）
/permissions reset    # 清空
/exit                 # 退出（自动保存会话）
```

## 验证产物

```bash
cat /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/Hello.java
# public class Hello {
#     public static void main(String[] args) {
#         System.out.println("hello zhuCodeAgent");
#     }
# }

cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects && javac Hello.java && java Hello   # 输出 hello zhuCodeAgent
```

## 安全观察点（可对照代码）

| 观察点 | 预期 | 对应代码 |
|--------|------|---------|
| 只读工具自动放行 | read_file 不弹确认 | `PermissionManager.decide`（readOnly → ALLOW） |
| 写类/bash 弹确认 | write_file/edit_file/bash 均确认 | `SerialToolExecutor` + `PermissionManager` |
| 工具只写工作区内 | 文件落在 /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects | `PathGuard.resolveInWorkspace` |
| 危险命令红线 | 可试 `rm -rf /tmp/xxx` → 立即拒绝不执行 | `DangerGuard.assertFileMutationsInWorkspace` |
| 结果预览截断 | 大输出只显示前 5 行 + 标注 | `ChatApp.TuiAgentUi.resultPreview` |
| 会话恢复 | 退出后重启选该会话，可继续引用工具上下文 | `SessionStore` + `Message` 内容块 |

## 常见问题

- **JLine native-access 警告**：无害，可用 `java --enable-native-access=ALL-UNNAMED -jar ...` 消除。
- **模型不调工具**：提示词里明确说"用 write_file / read_file / edit_file / bash 工具"更稳定（工具定义已随请求下发）。
- **权限确认必须回车**：输入 `a`/`d`/`s` 后按回车（变更记录：raw 单键在部分受限 PTY 下不可靠，改回车确认）。
- **demo 后清理**：在 `/Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects` 目录下执行 `rm -f Hello.java Hello.class m2-demo-config.yml`（可选；不要删除目录本身，它是项目目录）。
