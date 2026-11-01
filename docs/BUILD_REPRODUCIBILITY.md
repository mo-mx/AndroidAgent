# 可复现构建与测试（阶段 1 冻结范围 B3）

本文件回答一个问题：**从一个干净检出（clone）出发，需要什么才能重现本仓库报告的构建与测试结果？**
依据是 PR #4 冻结复审 `6555456830` 的 B3：作者报告的 `421/421`、`2511 tests / 6 failed / 8 skipped`
无法被独立复核，因为仓库里缺少部分构建输入。

证据分级沿用 `docs/TEST_RESULTS.md` 的约定：**实机验证** / **JVM** / **源码分析**。

## 1. 结论（本轮已改成可复现）

| 项 | 修复前（干净检出） | 修复后 |
|---|---|---|
| `res/drawable-*/ic_launcher_foreground.png` | 缺失 → AAPT2 `resource drawable/ic_launcher_foreground not found` | 已入库（5 个密度） |
| `res/mipmap-*/ic_launcher.png`、`ic_launcher_round.png` | 缺失 → `@mipmap/ic_launcher`（`AndroidManifest.xml:39/41/151`）无对应资源 | 已入库（5 个密度 × 2） |
| `src/closepaw/tools/termux-bridge/` | 整个目录被忽略 → `preBuild` 的 `copyClosePawBridge` 没有输入 | 已入库（源文件 + pytest） |

原因是 `.gitignore` 的两条**过宽**规则：

```
*.png                                  # 为了不提交设备截图，同时吃掉了启动图标
/src/closepaw/tools/                   # 为了不提交本地工具链，同时吃掉了 bridge 源码
```

修复只做精确反向包含，不放宽其它限制：
`*.png` 仍然忽略全部其它图片（含 `captures/`、设备截图）；
`/src/closepaw/tools/*` 只放行 `termux-bridge/`（该目录 46 KB 源码 + pytest，无二进制、无凭据）。

### 修复前的真实失败（实跑，不是推断）

命令（在 `https://github.com/mo-mx/AndroidAgent` 的 `feat/semantic-gate` `50a4c6a` 干净 clone 里执行）：

```
source env.sh
cd src/closepaw
./gradlew --offline :app:processDebugResources --console=plain
```

结果：`BUILD FAILED in 3s`，`EXIT=1`

```
> Android resource linking failed
  ERROR: .../res/mipmap-anydpi-v26/ic_launcher.xml:4: AAPT: error:
    resource drawable/ic_launcher_foreground (aka ai.closepaw:drawable/ic_launcher_foreground) not found.
  ERROR: .../res/mipmap-anydpi-v26/ic_launcher_round.xml:4: AAPT: error:
    resource drawable/ic_launcher_foreground (aka ai.closepaw:drawable/ic_launcher_foreground) not found.
```

日志：`logs/pr4-round50-clean-before.txt`。**这证明 B3 不是文档瑕疵：干净检出任一构建任务都无法通过资源链接。**

## 2. 从零复现的步骤

前置：JDK 17 + Android SDK（`ANDROID_HOME` / `ANDROID_SDK_ROOT`）。仓库根 `env.sh` 给的是本机路径，
换机器时按需修改第 1–5 行的 `ANDROID_HOME`、`JAVA_HOME`、`PATH`。

```bash
git clone https://github.com/mo-mx/AndroidAgent.git
cd AndroidAgent
git checkout feat/semantic-gate        # 或合并后的 main

source env.sh                          # 导出 JAVA_HOME / ANDROID_HOME / ADB
cd src/closepaw

# 1) 资源链接（最快验证"干净检出能不能构建"）
./gradlew --offline :app:processDebugResources --console=plain

# 2) 单元测试（JVM）
./gradlew --offline :app:testDebugUnitTest --rerun-tasks --console=plain

# 3) 定向回归（语义闸门相关）
./gradlew --offline :app:testDebugUnitTest --tests "ai.closepaw.voice.*" --rerun-tasks --console=plain

# 4) APK（需要本机已安装的 SDK platform/build-tools；本仓库不含 tools/）
./gradlew --offline :app:assembleDebug --console=plain
```

### 仍然属于"本机环境"、不在仓库里的东西

| 项 | 说明 |
|---|---|
| `local.properties`（`sdk.dir`） | 被忽略；用 `ANDROID_HOME` 环境变量即可，或本地生成 |
| `tools/`（根目录 JDK + Android SDK） | 本地工具链，**不入库**，按 `env.sh` 装好即可 |
| `src/main/res/raw/closepaw_bridge_py` | **不要手工提交**：由 `copyClosePawBridge`（`app/build.gradle.kts`，`preBuild` 依赖）从 `tools/termux-bridge/closepaw_bridge.py` 生成 |
| `app/build/assets/open_source_licenses.json` | 由 `licenseReport` 插件任务生成 |
| Gradle 依赖缓存 | `--offline` 需要本机已有依赖缓存；首次构建去掉 `--offline` |

`src/closepaw/tools/termux-bridge/closepaw_bridge.py` 在 git 中存为 **LF**（`core.autocrlf=true` 只影响工作区），
Termux 上可直接执行；不要用 Windows 编辑器把它改成 CRLF 再提交。

## 3. 已知非全绿：6 项失败

全量 `:app:testDebugUnitTest` 在本轮 HEAD 上仍然 **exit=1**：

```
2513 tests completed, 6 failed, 8 skipped
  AgentSkillCatalogTest > entry filePath points to SKILL md
  MemoryStoreTest > append with duplicate heading inserts under last occurrence
  MemoryStoreTest > append on file with missing heading appends new section at EOF
  MemoryStoreTest > append under heading followed by free text inserts before next heading
  MemoryStoreTest > multiple entries append to same section
  MemoryStoreTest > app entries stay inside their target section
```

归因证据（严格区分"提交者报告"与"独立运行"）：

- 本轮**实跑**：`logs/pr4-round50-full.txt`，2513 / 6 failed / 8 skipped，`EXIT=1`。
- 同一环境（同一台机器、同一 wrapper、同一 SDK）的**旧 SHA 基线**：`4d1e78d` 上 `55 tests / 6 failed`，
  失败名一致（`docs/TEST_RESULTS.md` 第㊿轮）。
- **未做到**的部分照实写：旧 SHA 位于 main 之后 2 个提交，不是 main 本身；因此"这 6 项不是本 PR 引入"
  仍属**源码分析 + 同环境对照**，不是从 `main` 干净检出跑出来的结论。要完全闭合，需要在 `main` 干净
  检出上跑同一组 6 个测试——这需要一次独立运行，本轮未做。

## 4. 本轮新增的定向证据（JVM）

| 阶段 | 命令 | 结果 |
|---|---|---|
| RED | `--tests TorchUndoConsumptionTest` | 7 tests / **2 failed**（两条新测试全红，旧 5 条仍绿） |
| GREEN | 同上 + `TorchUndoDispatchTest` | `BUILD SUCCESSFUL`，exit=0 |
| 定向 | `--tests "ai.closepaw.voice.*"` | **423 / 0 failed / 0 errors / 0 skipped**，exit=0（上轮 421，+2） |
| 全量 | `:app:testDebugUnitTest` | **2513 / 6 failed / 0 errors / 8 skipped**，exit=1 |

## 5. 边界

- 以上全部是 **JVM + 静态源码**证据。**本 SHA 没有构建 APK、没有安装、没有实机操作**（需用户重新授权）。
- 设备矩阵维持历史值：4 通过 / 0 失败 / 3 未测，**不代表当前 HEAD**。
- "干净检出可构建资源链接"已实跑验证；"干净检出可跑完 2513 个 JVM 测试"同样已实跑验证（见 §3 日志）。
  未验证的是 `assembleDebug` 全流程（需本机 SDK build-tools 成功打包），属可选项，未执行。
