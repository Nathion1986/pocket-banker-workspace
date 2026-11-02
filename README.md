# 口袋银行家 PocketBanker

> 2026 深圳国际金融科技大赛（微众银行专场）· AI 赛道参赛作品
> **多银行个人财务 AI 总管** —— 一个能"办事"的银行智能体：不只回答问题，而是替用户跨银行完成转账、缴费、订阅管理等真实操作，并通过短信/邮件/日历感知构建用户画像，主动预测与反诈。

## 在线体验

后端与 5 个 Mock 金融机构已部署公网：

- 服务地址：`https://www.kauson.com`（HTTPS）
- 演示账号：`demo` / `PocketBanker2026`
- Android App 构建时通过 `-PapiBase=https://www.kauson.com` 指向线上服务（见下文）

## 交付文档（docs/，Word 格式可直接下载）

| 文档 | 内容 |
|---|---|
| [`口袋银行家-技术文档.docx`](docs/口袋银行家-技术文档.docx) | 完整技术方案：架构、模块设计、安全体系、API、测试 |
| [`安全自评报告.docx`](docs/安全自评报告.docx) | 安全合规自评 |
| [`口袋银行家-演示视频脚本.docx`](docs/口袋银行家-演示视频脚本.docx) | 演示视频分镜脚本 |

## 核心能力

| 能力 | 说明 |
|---|---|
| **对话办事** | 自然语言 → 意图识别 → 参数核验 → 多银行真实操作（转账/查余额/账单分析），全程二次确认与金额安全校验 |
| **感知画像** | 短信/邮件/日历多通道采集（用户授权、本地优先），构建消费画像，预测现金流 |
| **异常反诈** | 消费行为异常检测 + 交易拦截提醒 |
| **场景编排** | 生日关怀等跨机构任务 DAG 编排（银行扣款 + 商户下单 + 提醒） |
| **20+ 可枚举 Action** | 可枚举操作走注册式 MCP 行动通道，长尾分析走受限 Docker 沙箱 |

## 架构总览

```
Android App (WebView + Java Bridge, 短信感知采集)
        │ HTTPS
        ▼
┌─────────────────────────────┐
│  pocket-banker (Java 17 /   │  Spring Boot 3.2 · 对话引擎 · 权限引擎
│  Spring Boot 3.2)           │  感知/画像/预测 · DAG 编排 · Docker 沙箱
└──────────┬──────────────────┘
           │ Mock API (Token)
   ┌───────┼────────┬──────────┬──────────┐
   ▼       ▼        ▼          ▼          ▼
 bank-a  bank-b  bank-c    flower-shop  cake-shop
   (3 家 Mock 银行, Python)  (2 家 Mock 商户, Python)
```

- **pocket-banker/**：后端主服务，156 个 Java 主源文件 + 38 个测试类
- **mock-banks/**：5 个独立 Mock 金融机构（3 银行 + 2 商户），Python 实现，各有独立账本与健康检查
- **android-app/**：Android 原生 App（四 Tab：对话 / 总览 / 感知 / 网络），WebView 混合架构 + 短信采集 Bridge
- **client-sdk/**：Python 客户端 SDK（联调用）
- **contracts/**：API 契约
- **AGENTS.md**：团队「AI 全流程协作开发」工程规范存档（六条纪律：文档台账/术语权威源/方案先行/bug 必转测试用例等）

## 安全设计（纵深防御）

1. **四道防线**：输入校验 → 意图白名单 → 参数核验（金额/账户安全规则）→ 操作二次确认
2. **加密存储**：敏感凭据 Vault 加密（AES-GCM），密钥仅存环境变量
3. **受限沙箱**：AI 生成的分析代码在受限 Docker 内执行，无网络、限资源
4. **最小权限**：Mock 服务 Token 鉴权，感知数据用户授权、本地优先

详见 [`docs/安全自评报告.docx`](docs/安全自评报告.docx)。

## 快速部署（Docker 一键起）

前置：Docker + Docker Compose。

```bash
# 1. 准备环境变量（本地演示用随机值即可）
export MOCK_SERVICE_TOKEN=$(openssl rand -hex 16)
export MOCK_ADMIN_TOKEN=$(openssl rand -hex 16)
export BACKEND_VAULT_KEY=$(openssl rand -hex 32)
export BACKEND_OTP_KEY=$(openssl rand -hex 32)
export PERCEPTION_USERS_JSON='{"demo":{"password":"PocketBanker2026","banks":[]}}'

# 2. 一键构建启动（6 个容器：1 后端 + 3 银行 + 2 商户）
docker compose up --build -d

# 3. 验证
curl http://127.0.0.1:8000/api/v1/health
```

默认 `BACKEND_MODEL_MODE=mock`（内置拟真模型，无需大模型 Key 即可完整体验流程）；
接入真实大模型时设置 `BACKEND_MODEL_MODE` 等相关环境变量（GLM）。

## Android App 构建

```bash
cd android-app
# 指向线上服务
./gradlew assembleDebug -PapiBase=https://www.kauson.com
# 或本地模拟器（默认 10.0.2.2:8765，需先 docker compose up 并映射端口）
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，登录 `demo / PocketBanker2026`。

## 测试

```bash
# 后端：38 个 JUnit 测试类（意图金样本/金额安全/审查话术/集成契约等）
cd pocket-banker && ./mvnw test

# Mock 银行：pytest 账本一致性
cd mock-banks && python -m pytest tests/

# Android：仪器测试（需模拟器/真机）
cd android-app && ./gradlew connectedAndroidTest
```

## 技术栈

Java 17 · Spring Boot 3.2 · GLM 大模型 · Python (Mock/SDK) · Android WebView 混合架构 · Docker Compose
