# 本地构建 agentscope service 管理台

问数 mesh 通过 aistio 接入 agentscope service 的控制面，管理台才能看到
`chatdata-ask` 并和它对话。这里记录**如何在本机把这个管理台跑起来**。

适用范围：agentscope-java **2.0.4**（不要升版本，A2A 协议版本与 starter 绑在一起）。

## 为什么需要打 patch

上游的 `docker compose build control` 是容器内三阶段构建，在国内网络下有
三处必然失败：

| 步骤 | 症状 |
|---|---|
| `npm ci` | 跑 8–20 分钟后 npm 自身崩溃：`Exit handler never called!` |
| `FROM gcr.io/distroless/static:nonroot` | 拉不到 |
| `GOPROXY=proxy.golang.org` | 超时 |

真正的阻塞点是 npm：同样的 410 个包，`pnpm` 装 2 秒，npm 20 分钟装不完还崩。
所以改成**宿主机产出产物，镜像只做 COPY**。

## 步骤

### 1. 应用 patch

```bash
cd ~/selfProjects/agentscope-java-2.0.4
patch -p1 < ~/selfProjects/chatdata-agent/docs/patches/agentscope-service-local-build.patch
```

改动三个文件：

- `docker/Dockerfile.control` → 12 行的只 COPY 版本
- `docker-compose.yml` → `control` 补上 `15010:15010`
- `.dockerignore` → 放行 `agentscope-service/aistio/ui`

### 2. 构建前端

```bash
cd agentscope-service/frontend
pnpm install
pnpm rebuild esbuild          # pnpm 默认跳过 esbuild 安装脚本，不跑 build 会失败
pnpm run build                # 产物落在 agentscope-service/aistio/ui/
```

### 3. 编译控制面

```bash
cd agentscope-service/aistio
export GOPROXY=https://goproxy.cn,direct GOTOOLCHAIN=auto
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
  go build -trimpath -ldflags="-s -w" -o ../../.build/aistiod ./cmd/aistiod
```

`go.mod` 要求 go 1.26，本机是 1.21 也没问题——`GOTOOLCHAIN=auto` 会自动
下载 1.26.0 的 toolchain。`GOSUMDB` 不要关，关掉后 toolchain 下载会因
「checksum database disabled」被拒。

Intel Mac 用 `GOARCH=amd64`。

### 4. 出镜像并启动

```bash
cd agentscope-service
docker compose build control     # 秒级：只有两个 COPY
docker compose up -d
```

### 5. 验证

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18081/    # 200
```

管理台用 `admin` / `admin` 登录。

## 端口

| 端口 | 用途 |
|---|---|
| 18081 | REST + 管理台 SPA（容器内 8081） |
| **15010** | ASDP gRPC，数据面长连接（容器内 15010） |

**15010 最容易被漏掉。** 只映射 8081 的话，agent 能完成 HTTP 自注册、
管理台能看到它，但发对话报 `503 asdp: instance not connected`——因为
gRPC 长连接从没建立。原版 compose 就只映射了 8081。

## mesh 侧的对应配置

`chatdata-agent` 的 `.env`：

```bash
MESH_AISTIO_ENABLED=true
AISTIO_CONTROL_PLANE=127.0.0.1:15010          # gRPC，只填 host:port，不能带 http://
AISTIO_CONTROL_PLANE_HTTP=http://127.0.0.1:18081
AISTIO_INTERNAL_TOKEN=...                      # = control 的 BUILDER_INTERNAL_TOKEN
AISTIO_CONTRACT_PORT=18090
AISTIO_PUBLIC_BASE_URL=http://host.docker.internal:18090
```

最后一项必须写 `host.docker.internal`：control 在容器里，写 `127.0.0.1`
会让它回调到容器自己，表现为 Dashboard 里有 agent、对话能跑，但 Sessions
页永远是空的。

验证数据面连上了：

```bash
JWT=$(curl -s -X POST http://127.0.0.1:18081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

curl -s -H "Authorization: Bearer $JWT" \
  "http://127.0.0.1:18081/api/v1/dataplanes?tenant=default&namespace=default"
```

空数组 = gRPC 没连上。

## patch 之外的副作用

- `agentscope-service/frontend/package-lock.json` 里的 registry URL 被改成了
  `registry.npmmirror.com`（用 npm 时改的）。pnpm 不看它，不影响构建。
- `frontend/.npmrc` 新增了 `registry=` 和 `onlyBuiltDependencies[]=esbuild`。
- `agentscope-service/aistio/ui/` 是构建产物，不进版本库。
- 根目录 `.build/aistiod` 是编译产物。

重新拉源码后，这些都要重做一遍。

## 相关

- `.scratch/agent-mesh/spec.md` §13 —— aistio 接入的设计与踩过的坑
- `docs/local-dev.md` § 接入 agentscope service 管理台
