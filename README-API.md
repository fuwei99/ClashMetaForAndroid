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
