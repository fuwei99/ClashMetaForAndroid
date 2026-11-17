# CMFA 合并控制面（fork 扩展）

> 目标：App 进程一启动就暴露 `127.0.0.1:9091`，RikkaHub / workspace 脚本 / 定时 process 直接操控。
> **不是 mihomo 9090 的简单透传**：VPN 开关、订阅、选节点、模式切换全部走 App 自己的 Binder / VpnService 路径，
> 只有 `/api/clash/*` 才代理内核 REST，且自动注入 `Authorization: Bearer <secret>`。

## 架构

```
workspace (proot)  ──127.0.0.1:9091──►  ApiControlService (App 主进程, 前台服务保活)
                                          │
                    ┌─────────────────────┼──────────────────────┐
                    ▼                     ▼                      ▼
        startClashService()      IProfileManager           IClashManager
        stopClashService()       create/patch/commit/       patchOverride(注入
        VpnService.prepare()     setActive/queryAll/        9090 + secret)
        StatusClient             update/delete             patchSelector
                                                              │
                                          ┌───────────────────┘
                                          ▼
                             127.0.0.1:9090 mihomo External Controller
                             （external-controller 强制注入、allow-lan=false、随机 secret）
```

- `ApiControlService`：前台服务，App 一启动 `MainApplication.onCreate()` 就拉起，退到后台仍活。
- `ApiBootReceiver`：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 自拉，不用人手点开 App。
- `ApiControlServer`：纯 `java.net.ServerSocket` 写的极简 HTTP，零新依赖，只绑 loopback。
- 首次启动随机生成 `core_secret` 落 `SharedPreferences`，注入 mihomo override（Persist slot），重开 VPN 仍生效。

## 端点

| 方法 | 路径 | 行为 | 是不是透传 |
|---|---|---|---|
| GET | `/` `/api/hello` | 能力清单 | — |
| GET | `/api/status` | **合并状态**：VPN 运行 / 权限 / active profile / profile 数 / 内核 /version / 鉴权 | 合并 |
| GET | `/api/secret` | 取 9090 的 Bearer secret（给排障用） | — |
| POST | `/api/vpn/start` | `startClashService()`（真 VpnService），顺带重注入 9090 | **App 操作** |
| POST | `/api/vpn/stop` | `stopClashService()` | **App 操作** |
| POST | `/api/vpn/toggle` | 按 `StatusClient` 判运行态再 start/stop | **App 操作** |
| GET | `/api/profiles` | 列出全部 profile（uuid/name/source/active…） | **App 操作** |
| DELETE | `/api/profiles/{uuid}` | 删 profile | **App 操作** |
| POST | `/api/subscription` | `{"url","name","interval_min","activate"}` → 建 Url profile → 拉订阅 → 设为 active | **App 操作** |
| POST | `/api/subscription/refresh` | 刷新当前 active 订阅 | **App 操作** |
| GET | `/api/groups` | 策略组名列表 | **App 操作** |
| POST | `/api/proxy/select` | `{"group","name"}` → `patchSelector`（写 SelectionDao，重开 VPN 仍记得） | **App 操作** |
| POST | `/api/mode` | `{"mode":"rule\|global\|direct"}` → 改内核 mode 后**回吐合并状态** | 合并 |
| POST | `/api/controller/inject` | 重新注入 9090 密钥 | **App 操作** |
| GET/POST | `/api/token` | 可选鉴权：POST `{"token":"..."}` 设，置空关闭 | — |
| ANY | `/api/clash/{path}` | 代理 mihomo 9090，自动带 Bearer | 透传（带密） |

`GET /api/status` 返回例：

```json
{
  "ok": true,
  "vpn_running": false,
  "vpn_permission_granted": true,
  "enable_vpn": true,
  "active_profile": "我的订阅",
  "active_profile_uuid": "…",
  "active_profile_source": "https://…",
  "profile_count": 1,
  "api_port": 9091,
  "core_controller": "127.0.0.1:9090",
  "core_reachable": true,
  "core_version": "{\"version\":\"Meta v1.19.x\"}",
  "api_auth": false
}
```

## 首次使用

1. 装 CI 出的 APK，**手动打开一次 App，授予 VPN 权限**（Android 硬要求，只能人点一次）。
2. 之后 9091 立即就绪，不需要 App 在前台、不需要 VPN 已连：

```bash
cmfa hello
cmfa status
cmfa sub 'https://你的订阅链接' 我的订阅 720
cmfa on
cmfa groups
cmfa use GLOBAL 节点名
cmfa mode rule
cmfa clash /connections          # 裸透传，自动带密
```

> 订阅链接别写进任何会被 git / Obsidian / 日志收录的地方。`cc sub` 只从命令行参数读，
> 脚本不落盘、不打日志。

## 安全边界

- 9091 只绑 `127.0.0.1`，`allow-lan` 强制 `false`，不开 `0.0.0.0`。
- 默认只信 loopback；要额外鉴权：`curl -XPOST 127.0.0.1:9091/api/token -d '{"token":"..."}'`。
- `core_secret` 随机生成落盘，不硬编码、不进 git。
- `/api/clash/*` 支持 PATCH（用裸 socket 发，绕开 `HttpURLConnection` 的 method 限制）。

## 已知边界

- `/api/vpn/start` 在权限未授予时返回 `409 vpn_permission_required`，不会自己弹 UI（Android 不允许后台弹 VPN 授权）。
- App 刚起来的 1–2 秒内 `RemoteService` binder 可能没连上，相关端点返回 `503 RemoteService not bound yet`，重试即可。
- VPN 接管后 proot 出网也走 tun0：R2 / K70 隧道 / ngrok / 飞书 MCP 的域名必须在规则里放行 DIRECT，否则会把自己的同步链路绕死。


---

## 流量观测 / 精准封杀（2026-11-17 追加）

> `TunModule.queryUid()` 用 `ConnectivityManager.getConnectionOwnerUid` 把 UID 喂进内核，
> `AppListCacheModule` 把 uid→包名表推给 `nativeNotifyInstalledAppChanged`，
> 所以 mihomo `/connections` 每条自带 `metadata.process` / `uid` / `host` / `sniffHost`。
> **零内核改动，纯 App 层 merge。**

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/connections` | 内核实时连接原始快照 |
| GET | `/api/traffic` | **按 app 聚合**：连接数 / 上下行 / 访问过的域名 → 一眼看清哪个 app 在出流量 |
| GET | `/api/apps?uid=` | uid → 包名 / 应用名 / 是否系统 app |
| GET | `/api/history?limit=200&app=&host=` | **访问历史**：域名级 URL + app + 字节 + 首见/末见 + 规则链 |
| POST | `/api/history/clear` | 清空历史 |
| POST | `/api/app/kill` | `{"package":"..."}` 立刻掐该 app 所有现有连接 |
| POST | `/api/app/block` / `unblock` | 持续封杀 / 解封（黑名单常驻内存，watchLoop 每 2s 自动掐新连接） |
| GET | `/api/blocks` | 当前黑名单 |
| GET | `/api/capture` | **抓包能力边界说明**（不撒谎） |
| POST | `/api/capture/http` | `{"enable":true,"port":7890}` 可选开本地 mixed-port 代理，明文 HTTP 完整 URL/Header 可见 |

```bash
cmfa traffic                    # 哪个 app 在出流量
cmfa history 100 com.tencent.mm # 微信访问过哪些域名
cmfa kill com.tencent.mm        # 当场掐
cmfa block com.tencent.mm       # 持续封杀
cmfa caphttp on 7890            # 开明文代理给工具导流量
```

### 抓包边界（必读，别装完骂人）

- **9091 是控制面，不承载流量**，不能当反向代理；数据流在 tun0/内核，API 只读连接元数据 + 发指令。
- **能拿**：按 app 的实时流量统计、访问过的**域名级 URL**（SNI sniff / HTTP Host / CONNECT host）、精准掐连接。
- **拿不到**：HTTPS 的 path / body / payload —— TLS 加密，非 root 做不了 MITM（Android 7+ 其他 App 不信任用户 CA，小黄鸟那套要系统 CA + 独立 MITM 栈）。
- 明文 HTTP 想看完整 URL，用 `/api/capture/http` 开 mixed-port 把流量导过去；HTTPS 仍只有域名。


---

## v2：按 app 流量观测修复 + 订阅/节点补齐（2026-11-17 晚）

### 根因（run#4 实测发现的残废点）
`/connections` 里 `uid` 全 0、`process` 全空 → `/api/traffic` 把流量全糊成 `app="0"`。
- mihomo `tunnel.go:400-405`：`find-process-mode: strict` 只在规则命中 PROCESS 时才调 `DefaultPackageNameResolver`；
  CMFA 默认 strict，普通规则永不解析。
- `tunnel.go:375-382` 的 CMFA 分支只写 `metadata.Process`、**不写 `metadata.Uid`**。

### 修复
- `injectController()` 按开关注入 `find-process-mode: always`（默认开）。改 override 会发
  `ACTION_OVERRIDE_CHANGED` → `ConfigurationModule` 自动 `Clash.load()` 重载，无需重启 VPN。
  `PATCH /configs` 改这个字段是 405，必须走 override。
- `delegate/init.go` 的 resolver 补写 `metadata.Uid = uint32(uid)`。
- `GET|POST /api/observe`：`{"find_process":"always|strict|off"}` 开关（always 有每连接反查开销，留退路）。

### 订阅 / 节点（链接导入首版就有，补齐「列节点」与「贴文本」）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/subscription` | `{"url","name","interval_min","activate"}` 建/拉/激活订阅链接 |
| POST | `/api/subscription/refresh` | 刷新当前订阅 |
| GET | `/api/profiles` / DELETE `/api/profiles/{uuid}` | 配置列表 / 删除 |
| GET | `/api/groups` | 组名列表 |
| **GET** | **`/api/group/nodes?group=xxx`** | **组内节点 + 当前选中 + 延迟**（走 `queryProxyGroup`，非透传） |
| POST | `/api/proxy/select` | `{"group","name"}` 选节点，写 `SelectionDao` 持久化 |
| POST | `/api/mode` | `{"mode":"rule|global|direct"}` |
| **POST** | **`/api/subscription/import`** | **`{"yaml","name"}` 直接贴配置文本**：API 起一次性 `_raw/<uuid>` HTTP 源喂给 Url profile，走原生 `fetchAndValid` 校验+激活，不落盘不写日志 |

```bash
cmfa observe                  # 看 find-process-mode
cmfa observe always           # 开按 app 观测（默认已开）
cmfa traffic                  # 现在能看到具体包名了
cmfa nodes "🚀节点选择"        # 列节点 + 当前选中
cmfa use "🚀节点选择" "🇯🇵日本节点 01"
cmfa sub https://xxx/xxx 我的订阅 720   # 链接导入
cmfa import ./config.yaml 我的配置       # 贴 YAML 导入
cmfa groups / profiles / refresh / rm / mode / inject / secret
```

> 装新包后需手动开一次 App 授 VPN；刚起时 binder 未连返回 503，等 1-2s 重试。
> 首次 `cmfa observe` 若 `find_process_core` 仍是 strict，手动 `cmfa observe always` 触发一次重载。
