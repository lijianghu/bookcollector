# 微信读书采集后台 · bookcollector-admin

把「微信读书分类 / 榜单图书采集」做成一个**可视化后台**的单机应用。

后端 Spring Boot + MongoDB，前端 Vue 3 + Element Plus。可以按分类或榜单建立采集任务、
随时启动 / 暂停 / 恢复 / 取消、查看采集进度与运行日志、浏览与检索采集到的图书，
并对每一次上游接口调用留下审计记录。

> **第一期范围**
> 做：分类 / 榜单管理 → 启动采集任务 → 看进度 → 浏览采集到的图书。
> 不做：SSE 实时推送（手动刷新）、定时采集、权限体系（`roles` 存在但不参与鉴权）、ETL 迁移、
> 数据导出、模糊搜索、Docker 部署。

---

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [数据模型](#数据模型)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [接口约定](#接口约定)
- [设计约定](#设计约定)
- [测试](#测试)
- [常见问题](#常见问题)

---

## 功能特性

### 仪表盘

- 4 个指标卡：图书总数、分类数、任务数、今日新增
- 4 张 ECharts 图表：评分分布、年份分布、分类分布、采集趋势
- 指标卡与图表**分两个接口**返回，让数字先出、图表后到，避免整页等最慢的那个

### 图书库

- 12 个查询参数：书名、作者、分类、评分区间、年份区间、排序方式等
- 服务端分页与排序（排序字段走白名单，不开放任意字段）
- 列显隐配置，偏好落 `localStorage`，刷新后保持
- 详情抽屉：60 个字段分 5 组展示
- 编辑：**只提交被改过的字段**，不会用整本覆盖（避免把并发写入的字段冲掉）
- 批量删除，返回实际删除条数

### 任务中心

- 任务增删改查；一个采集目标（分类 / 榜单）**同时只能有一个任务**
- 7 状态机：`PENDING` / `RUNNING` / `PAUSED` / `SUCCESS` / `FAILED` / `CANCELED` / `INTERRUPTED`
- 动作：启动 / 暂停 / 恢复 / 取消 / 重试 / 编辑 / 删除
- 状态机在前端有一份镜像（按钮可用性、标签文案、颜色），与后端逐条对齐
- 详情抽屉 3 个 Tab：概览、运行历史、运行日志，支持手动刷新
- 运行历史保留最近 50 次，日志保留最近 500 条
- 应用重启时自动把遗留的 `RUNNING` / `PAUSED` 任务标记为 `INTERRUPTED`（清理假死状态）

### 分类榜单（采集目标字典）

- 分类与榜单的增删改查、启用 / 停用
- 删除时有任务引用则拒绝；只有游标引用时顺带清理游标
- 首次启动自动 seed 21 个分类 + 7 个榜单（只补缺失，不覆盖已有修改）

### 采集游标

- 查看每个目标的采集进度（已采到的 `maxIndex`）
- 支持「重置到 0」与「指定起点」——两者合成同一个 `PUT` 接口
- 游标**归属「目标」而不是「任务」**，所以重建任务不会丢掉进度

### 请求审计

- 每一次上游接口调用记一条：页码、状态码、耗时、结果条数、失败原因
- 只读，不提供删除
- 失败行在界面上高亮

### 登录

- 账号存在 MongoDB 的 `sys_user` 集合（密码存 **MD5 摘要**，不存明文），
  由 **Sa-Token** 签发 token 并维护服务端会话（Redis）—— 所以**登出会真的让 token 失效**
- 首次启动播种一个初始管理员（`bookcollector.auth.initial-*`）；之后改密码改库即生效，不用重启
- 服务端**不做权限体系**：`roles` 字段存在，但不参与任何鉴权判断
- token 存 `localStorage`，axios 拦截器统一处理失效跳转

---

## 技术栈

### 后端

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | **8**（1.8） | `maven.compiler.source/target = 1.8` |
| Spring Boot | **2.3.12.RELEASE** | 父 POM |
| Spring Data MongoDB | 由 Boot 管理 | 驱动 **4.0.6** |
| Apache HttpClient | 4.5.x | ⚠️ 必须是 4.5.x —— Spring Framework 5.2 的 `HttpComponentsClientHttpRequestFactory` **不接受 HttpClient 5.x** |
| fastjson2 | 2.0.60 | 上游响应解析 |
| springdoc-openapi-ui | 1.6.15 | Spring Boot 2.x 只能用 1.6.x |
| Lombok | 由 Boot 管理 | `optional`，不打进产物 |
| 构建 | Maven 3.8+ | |

> 刻意**没有**引入 Guava —— 只为限速一个功能背 3MB 依赖不划算，
> 自写的 `SimpleRateLimiter` 十行就够。

### 前端

| 组件 | 版本 |
|---|---|
| Vue | 3.4 |
| Vite | 5.2 |
| TypeScript | 5.4（严格模式） |
| Element Plus | 2.7 |
| Pinia | 2.1 |
| Vue Router | 4.3（hash 模式） |
| ECharts | 5.5（按需引入） |
| Axios | 1.6 |
| 包管理器 | npm |

### 存储

| 组件 | 版本 | 说明 |
|---|---|---|
| MongoDB | 7.0 | 唯一权威存储 |
| Redis | 3.2 | 依赖与配置保留，**主代码零引用**，见[设计约定](#redis-目前没有被用到) |

---

## 系统架构

### 分层

```
┌───────────────────────────────────────────────────────────┐
│  前端  Vue 3 + Element Plus（hash 路由）                    │
│  views / api(axios) / store(Pinia) / components(4 个基础组件) │
└──────────────────────────┬────────────────────────────────┘
                           │ HTTP  /api/**   （业务码恒在 body，见「接口约定」）
┌──────────────────────────▼────────────────────────────────┐
│  后端  Spring Boot 2.3                                     │
│                                                            │
│  Controller      收请求 + 参数校验 + 拼统一响应              │
│      │                                                     │
│  Service         业务编排（状态机、级联、查询语义）            │
│      │                                                     │
│  Repository      MongoTemplate 数据访问（复杂查询 / 聚合）     │
│      │                                                     │
│  Entity          @Document 持久化映射                       │
│                                                            │
│  横切：AuthInterceptor（鉴权）/ TraceIdFilter（链路 id）       │
│        GlobalExceptionHandler（统一异常 → 业务码）            │
└──────────┬───────────────────────────────┬─────────────────┘
           │                               │
    ┌──────▼──────┐                 ┌──────▼──────────────────┐
    │  MongoDB    │                 │  采集引擎 collector/      │
    │  9 个集合    │◀────────────────│  CollectLoop + 重试/限速  │
    └─────────────┘                 └──────┬──────────────────┘
                                           │ HTTP（自定义 Header）
                                    ┌──────▼──────────┐
                                    │  微信读书接口     │
                                    └─────────────────┘
```

### 一次采集任务的完整链路

```
① 建任务        POST /api/tasks           写 collect_tasks（(targetType,targetId) 唯一）
② 启动          POST /api/tasks/{id}/start
                  └─ TaskService   → 读游标 collect_cursors 得到起点
                  └─ TaskRegistry  → 申请采集权（同目标互斥）
                  └─ TaskRunner    → @Async 提交到采集线程池，立即返回
③ 执行          CollectLoop 分页主循环（每页）：
                  限速 → 请求 → 解析 → 先 upsert 落库 → 再推进游标 → 写审计 + 进度日志
④ 观察          前端手动刷新读 collect_task_runs（进度）与 collect_task_logs（日志）
⑤ 收尾          写 run 终态 → 更新任务状态 → 释放采集权
```

关键点：**先落库、再推游标**。这样即使中途崩了，游标也不会跑到数据前面去，
重跑时最多重复写入（upsert 幂等），不会漏数据。

### 采集引擎的三个关键设计

**1. 幂等写入**
`books.bookId` 上有唯一索引，写入走 `bulkOps().upsert()` 分片批量执行。
对同一份响应重复执行结果完全一致：同 `bookId` 不重复插入，也不覆盖 `firstCollectedAt`。
唯一索引是**正确性依赖**，不是性能优化。

**2. 断点续传**
游标按「目标」持久化在 `collect_cursors`。任务取消 / 失败 / 应用重启后重新启动，
都从上次的位置继续，不从头重采。

**3. 限速与重试**
默认 1 页 / 秒（沿用原 Python 实现的节奏），避免被上游限流。
失败按 1s → 3s 退避重试，最多 3 次；**4xx 不重试**（重试没有意义）。
「这一页算不算失败」由采集循环统一判定，不交给 HTTP 客户端库替我们下结论。

### 任务编排

- 采集线程池独立于 Web 线程池：core 1 / max 2 / 队列 16，**满了直接拒绝**而不是阻塞 HTTP 线程
- 同一目标用内存注册表（`TaskRegistry`）互斥，避免两个任务共享一个游标产生静默的意外行为
- 暂停用 `ReentrantLock` + `Condition` **阻塞**实现，可以原地恢复，而不是抛异常中断
- 取消是**协作式**的：立刻置任务状态为 `CANCELED`，采集线程在当页跑完后退出
- 应用关闭时唤醒所有暂停线程并取消，避免线程泄漏

---

## 数据模型

8 个 MongoDB 集合（库名 `bookcollector`）：

| 集合 | 内容 | 关键约束 |
|---|---|---|
| `books` | 采集到的图书（46 个业务字段 + 采集元数据） | 唯一索引 `bookId` |
| `taxonomy_config` | 分类 / 榜单字典 | 唯一索引 `(type, code)` |
| `collect_tasks` | 任务定义 | 唯一索引 `(targetType, targetId)` |
| `collect_task_runs` | 每次运行的流水（进度、终态、耗时） | 索引 `taskId`、`startedAt` |
| `collect_task_logs` | 运行日志（界面「日志」Tab 读的就是它） | 索引 `runId` |
| `collect_cursors` | 采集游标 | 唯一索引 `(targetType, targetId)` |
| `api_requests` | 上游请求审计（每页一条） | 索引 `createdAt`、`runId` |
| `settings` | 预留 | 唯一索引 `key` |
| `sys_user` | 登录用户（密码存 MD5 摘要） | 唯一索引 `username` |

索引在应用启动时由 `MongoIndexInitializer` 显式创建（共 18 个），
并带「字段漂移自愈」：索引字段路径改过时先 `drop` 再建。

> ⚠️ `ensureIndex` 的语义是「按名字 upsert」—— 同名索引已存在时**什么都不做**，不比较字段。
> 所以字段路径写错过一次，光改代码修不好已建出的索引；而且 MongoDB 对「同名不同 key」
> 会直接报 `IndexKeySpecsConflict` 拒绝重建。

---

## 目录结构

```
bookcollector-admin/
├── backend/                      Spring Boot 2.3 + JDK 8
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/bookcollector/
│       │   ├── BookCollectorApplication.java
│       │   ├── common/           ResultBean / PageResult / BizException /
│       │   │                     GlobalExceptionHandler / TraceIdFilter
│       │   │   └── enums/        TargetType / TaskStatus
│       │   ├── auth/             登录鉴权：AuthStpUtil / AuthInterceptor / AuthController
│       │   │                     ExtractLoginUser / SysUser / LoginUser
│       │   ├── collector/        采集引擎：CollectLoop / WereadClient / BookParser /
│       │   │                     BookWriter / SimpleRateLimiter / ProgressReporter ...
│       │   ├── task/             任务编排：TaskService / TaskRunner / TaskRegistry /
│       │   │                     TaskHandle / TaskRepository / InterruptedTaskDetector
│       │   ├── book/             图书查询与编辑：BookController / BookService /
│       │   │                     BookRepository / BookQuery
│       │   ├── taxonomy/         分类榜单字典
│       │   ├── cursor/           采集游标
│       │   ├── stats/            仪表盘聚合（MongoTemplate 聚合管道）
│       │   ├── audit/            请求审计（写 / 查分离）
│       │   ├── setting/          预留
│       │   └── config/           Mongo / MongoIndexInitializer / SeedRunner /
│       │                         RestTemplateConfig / AsyncConfig / WebMvcConfig / Swagger
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── logback-spring.xml      日志格式与按天滚动
│       └── test/java/com/bookcollector/  单测 4 个 + 集成测试 6 个
└── frontend/                     Vue 3 + Vite + TS + Element Plus
    ├── package.json
    ├── index.html
    ├── vite.config.ts            /api → 127.0.0.1:8080 代理
    └── src/
        ├── api/                  request.ts（axios 封装 + 失效处理）+ 8 个业务模块
        ├── components/           BaseChart / ProTable / DetailDrawer / SearchForm
        ├── composables/          useColumnPrefs.ts（列显隐持久化）
        ├── config/               menu.ts（菜单唯一真相来源）
        ├── layouts/              BasicLayout + 侧边栏 / 顶栏 / 面包屑
        ├── router/               hash 路由 + 登录守卫
        ├── store/                Pinia：user / app
        ├── types/                手写 TS 类型
        ├── utils/                format / echarts（按需引入）/ taskStatus
        └── views/                Login + dashboard / book / task / taxonomy / cursor / audit
```

---

## 快速开始

### 前置要求

| 组件 | 版本要求 |
|---|---|
| JDK | **8**（必须是 8，Spring Boot 2.3 不支持更高版本编译目标） |
| Maven | 3.8+ |
| Node.js | 18+（推荐 20 / 22） |
| MongoDB | 7.x（5.x / 6.x 亦可） |
| Redis | 可选 —— 主代码未使用，不启动也不影响运行 |

### 1. 准备 MongoDB

确保 MongoDB 已在本地 `27017` 运行。**不需要手工建库建表** ——
首次启动时应用会自动建索引（18 个）并 seed 字典（21 个分类 + 7 个榜单 + 1 个初始管理员）。

### 2. 改配置

编辑 `backend/src/main/resources/application.yml`，按自己的环境调整：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `spring.data.mongodb.uri` | `mongodb://localhost:27017/bookcollector` | 数据库地址 |
| `server.port` | `8080` | 后端端口（改了要同步改前端 `vite.config.ts` 的代理） |
| `bookcollector.auth.initial-username` | `admin` | 初始管理员登录名（仅首次启动、库为空时播种） |
| `bookcollector.auth.initial-password` | `admin123` | **⚠️ 请改成自己的**（落库前做 MD5，库里不存明文） |
| `bookcollector.auth.initial-nickname` | `管理员` | 前端顶栏展示名 |
| `sa-token.timeout` | `2592000` | 登录有效期（秒），默认 30 天 |
| `bookcollector.weread.rate-limit-per-second` | `1.0` | 采集限速（页/秒），调高有被上游限流的风险 |
| `bookcollector.weread.max-retry` | `3` | 单页失败重试次数 |

> `bookcollector.auth` 只是**首次启动时播种初始管理员**的参数，
> **不是登录校验的来源** —— 登录校验读的是 `sys_user` 集合。
> 库里已有同名用户时这段配置**完全不生效**（所以改密码改库即可，不用重启）。
> 默认值只是为了开箱能跑，**公开部署前务必修改**。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
```

启动后：

- 服务地址：<http://127.0.0.1:8080>
- 健康检查：<http://127.0.0.1:8080/api/ping> → `{"code":200,...,"data":{"message":"pong"}}`
- 接口文档：<http://127.0.0.1:8080/swagger-ui.html>
  （先调 `POST /api/auth/login` 拿 `data.token`，再点右上角 **Authorize** ——
  只粘 token 本身，Swagger UI 会自动补 `Bearer ` 前缀）

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 <http://127.0.0.1:5173>，用 `application.yml` 里配置的账号密码登录。

`vite.config.ts` 已配好 `/api` → `127.0.0.1:8080` 的代理，开发期不用处理跨域。

### 5. 试一次采集

1. 打开「分类榜单」，确认已有 21 个分类 + 7 个榜单
2. 打开「任务中心」→ 新建任务 → 选一个分类 → 保存
3. 点「启动」，进详情抽屉看「概览」Tab 的进度；想看细节切「运行历史」/「日志」
4. 采完到「图书库」查书，到「请求审计」看每一次调用的状态码与耗时

### 日志

日志默认写在**启动时工作目录**下的 `logs/bookcollector.log`，按天滚动、保留 30 天。

- 格式与滚动策略定义在 `backend/src/main/resources/logback-spring.xml`
- 用 `mvn spring-boot:run` 时，`pom.xml` 已注入 `-DLOG_DIR=${project.basedir}/../logs`，
  日志会稳定落在 `<项目根>/logs/`（因为 fork 出的 JVM 工作目录是 `backend/`）
- 用 `java -jar` 直接跑时没有这个属性，请显式传 `-DLOG_DIR=<绝对路径>`

---

## 接口约定

所有接口统一返回 `ResultBean<T>`：

```json
{
  "code": 200,
  "message": "",
  "traceId": "cb58abbb0e614501",
  "data": {}
}
```

| code | 含义 |
|---|---|
| **200** | 成功 ← **注意是 200，不是 0** |
| 400 | 参数错误 |
| 404 | 资源不存在 |
| 405 | 主键重复 |
| 500 | 系统错误 |
| 4100 | 身份已失效，前端跳登录 |

### HTTP 状态码恒为 200，业务结果看 body 里的 `code`

这样前端只需要在 axios 拦截器里判断一处。

**鉴权失败返的是 HTTP 200 + `code=4100`，不是 401** —— 这一条反直觉，但很重要：

- 后端用 `AuthInterceptor`（`HandlerInterceptor`，不是 Filter）拦 `/api/**`，
  放行 `/api/auth/login` 与 `/api/ping`；校验失败时**自己往 response 写 JSON**，
  HTTP 状态码设 200、body 里 `code=4100`
- token **只从请求头读**，格式必须是 `Authorization: Bearer <token>`
  （改造前还支持 `X-Token: xxx` 与 `?token=xxx`，现在都不支持了）
  （`preHandle` 返回 `false` 后 `GlobalExceptionHandler` 不会介入，所以必须自己写）
- 用 Interceptor 而不是 Filter：Filter 只能按 `urlPatterns` 前缀匹配，
  而 `/api/auth/login`、`/api/ping` 与要拦的其余 `/api/**` 混在同一前缀下，
  Interceptor 的 `excludePathPatterns` 表达力正好够
- 前端响应拦截器**只看 body 里的 `code`，不看 HTTP 状态码**；
  收到 4100 → 提示重新登录 → 清 token → 跳登录页

### 其他约定

- **参数校验消息**排序 + 限 3 条 + 换成人话（Validator 不保证 `getFieldErrors()` 顺序）
- **编辑一律「只写传了的字段」**，绝不整本 `save()`
- **删除语义**：删任务级联删 runs + logs，**不删游标**；
  删字典项时有任务引用则拒绝，只有游标则顺带清掉
- **`code` / `targetType` / `targetId` 不可改** —— 用 DTO 字段缺失让「不能改」在类型层面成立
- **聚合管道直接写 `Document`**，方便复制到 mongosh 单独调试

---

## 设计约定

### `books` 的语义是「历史累计的并集」，不是「当前榜单的快照」

上游分类列表按 `readingCount` 排序，**并列时 tie-break 不稳定**。
实测同一个分类的前 100 名在几分钟内就会换掉一两本，而且是**来回抖**的
（掉出去的书过一会儿还会回来）。

| 时间尺度 | 行为 |
|---|---|
| 秒级（同一轮内） | **完全稳定** —— 同范围两次请求，`bookId` 集合一模一样 |
| 分钟级（跨轮） | **会漂移** —— 边界上有书进出 |

因此：

- **不要**拿 `books` 的总数去跟接口返回的 `totalCount` 对账（一个是并集，一个是当前快照）
- 跨较长时间重采同一范围，`books` 总数可能 ±N —— **这不是 bug**
- 幂等性的准确表述是「**对同一份响应，upsert 幂等**」，
  而不是「集合总数恒定」

这也是**永不删除**这条约定的由来：掉出榜单的书几分钟后还可能回来，
若按「以最新榜单为准、删掉不在榜的书」实现，数据会被反复删建。

### 上游接口每个分类**最多只给 500 本（25 页）** —— 上游硬上限

`/web/bookListInCategory/{id}` 对所有分类都只给到 `maxIndex=480` 那一页：

| maxIndex | 返回 |
|---|---|
| 0 / 480 | 20 本，`hasMore=1`，`totalCount=54086` |
| **500 起** | **`{"books":[],"hasMore":0}`（36 字节）** |

实测多个分类（文学 / 精品小说 / 计算机 / 童书）行为完全一致；榜单更短
（飙升榜 `totalCount=39`，只有 2 页）。

**所以**：

- 一个分类任务的天然跑道是 **25 页 ≈ 29 秒**（限速 1 页/秒），
  把页数上限设成 60 也没用 —— 第 26 页拿到空列表就正常结束了
- 界面上的「该目标共 54086 本」是**上游报的总数**，不代表能采完，不要拿它当进度分母
- **这不是 bug**：代码对空页的处理是对的（`hasMore=false` → 正常结束，不报错、不死循环）

### Redis 目前没有被用到

`spring-boot-starter-data-redis` 依赖和 `spring.redis.*` 配置都留着，
但**主代码零引用**。这是**有意的**：

- 任务状态以 **MongoDB 为权威**（要能重启后恢复，Redis 3.2 没有持久化保证）
- 采集锁用进程内注册表（单机单进程，够用）
- 限速用自写的 `SimpleRateLimiter`（十行，不需要分布式）

Lettuce 是懒连接，留着几乎零成本。若后续也用不到，删掉 `pom.xml` 的 4 行依赖
与 `application.yml` 的配置段即可。

---

## 测试

### 单元测试（离线，不联网，不依赖上游）

```bash
cd backend
mvn test
```

覆盖：数据层（集合 / 索引 / seed / 幂等 upsert）、字段映射解析（46 字段逐一断言）、
任务状态机与并发拒绝、接口契约（35 个端点清点 / 错误码 / 响应体不泄漏内部字段）。

### 集成测试（**联网**，打真实上游接口）

集成测试类以 `*IT` 结尾，不在 `mvn test` 的默认扫描范围内，需要显式指定：

```bash
cd backend
mvn test -Dtest='*IT'
```

覆盖：真实采集（幂等 / 断点续传 / 页数上限）、分页列表短时稳定性、
任务启停暂停恢复取消、字典与游标生命周期、图书查询语义、
以及**对端异常**（5xx 重试满次数 / 4xx 只请求一次 / 断网重试）。

> 其中「对端异常」那组用一个本地 `HttpServer` 冒充上游，
> 通过 `@DynamicPropertySource` 改掉 `bookcollector.weread.base-url` ——
> 这样能精确数出服务端实际收到几次请求，是验证「重试几次」唯一诚实的办法。

---

## 常见问题

### 1. `mvn` 报「找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher」

在 Git Bash / MSYS 环境下，Maven 自带的 POSIX 启动脚本可能识别不出 MSYS，
把 `/c/...` 这种 Unix 路径原样喂给了 Windows 版 `java.exe`。

**改用 `mvn.cmd`**（或直接在 PowerShell / CMD 里执行），即可绕过。

### 2. 中文乱码

- HTTP 响应：已通过 `server.servlet.encoding.force: true` 解决
- 应用 JVM 的 `file.encoding`：`spring-boot:run` 会 fork 新 JVM，`MAVEN_OPTS` 传不进去，
  已在 `pom.xml` 的 `<jvmArguments>` 里显式设 `-Dfile.encoding=UTF-8`
- 读取上游响应体：刻意用 `byte[]` + 手工 UTF-8 解码，
  避开 `StringHttpMessageConverter` 在响应头没带 charset 时退回 ISO-8859-1 的坑
- **日志**：logback encoder 的 charset **独立于** `file.encoding`，
  所以 `logback-spring.xml` 里每处都显式写了 `<charset>UTF-8</charset>`

### 3. `mvn spring-boot:run | tail -20` 看不到任何输出

`tail` 会缓冲，进程不退出就一行都不输出。改成重定向到文件：

```bash
mvn spring-boot:run > backend.log 2>&1
tail -f backend.log
```

### 4. MongoDB 连不上（驱动版本）

Spring Boot 2.3.12 管理的驱动是 **4.0.6**，对 MongoDB 7.0 是官方 **⊛ 部分兼容** ——
能连上、能跑 CRUD，只是用不到 7.0 的新特性。

真要连不上，把 `backend/pom.xml` 里这行的注释去掉（4.10+ 才是「✓ 完全兼容」，
且 4.11 仍支持 Java 8）：

```xml
<mongodb.version>4.11.1</mongodb.version>
```

### 5. Redis 配置前缀写错

Spring Boot 2.3 用 **`spring.redis.*`**，不是 Spring Boot 3 的 `spring.data.redis.*`。

### 6. 启用 Lettuce 连接池后报 `ClassNotFoundException: GenericObjectPoolConfig`

配了 `spring.redis.lettuce.pool.*` 就必须加 `org.apache.commons:commons-pool2` 依赖。
本项目**没有用连接池** —— 单机单任务场景下 Lettuce 的共享单连接足够。

### 7. 前端改了代码但页面没变化

Vite 的 HMR 对**新增文件**和**路由表改动**经常不生效（尤其 `router/index.ts`
和 `main.ts`）。手动 `Ctrl+Shift+R` 强刷一次，或在 dev server 终端按 `r` 重启。
改了 `vite.config.ts` 则**必须**重启 dev server。

### 8. 采集任务启动就失败 / 一页都采不到

按顺序排查：

1. 看「请求审计」页的状态码与失败原因 —— 上游是 4xx 还是 5xx 一目了然
2. 看「任务中心 → 详情 → 日志」Tab 的具体报错
3. 网络是否可达 `weread.qq.com`
4. 是不是同一目标已经有任务在跑（同一目标只允许一个任务）

---

## 说明

本项目仅用于个人学习与技术研究，采集的数据请勿用于商业用途。
请遵守目标站点的服务条款与相关法律法规，并自行控制采集频率。
