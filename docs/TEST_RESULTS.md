# TEST_RESULTS — 实机验收记录

更新：2026-10-31　维护方式：每次真机验证后**追加**一条，写清"测什么/怎么测/结果/证据在哪"。
规则：没有真机日志的项目只能写"未验证"，不得写"通过"。

---

## 2026-11-02 第63轮（全量 JVM 在 `efd0f9c` 本体实跑；未实机）

测什么：消除「全量结果只属于 `9c389e3`」这条限定——复审 `6576888477` 指出现 HEAD `efd0f9c` 只加了 docs 与证据登记、未在当前 SHA 上实跑。

怎么测：`rm -rf app/build/test-results/testDebugUnitTest` → `JAVA_HOME=tools/jdk-17.0.20.1+1 ANDROID_HOME=tools/android-sdk ./gradlew --offline :app:testDebugUnitTest --rerun-tasks --console=plain`；统计读 XML 求和（不采信控制台摘要），汇总入仓 `docs/evidence/pr4-efd0f9c-junit-summary.txt`。

| HEAD | 结果 | exit | Gradle |
|---|---|---|---|
| `efd0f9c` | **2529 tests / 0 failed / 0 errors / 8 skipped**（271 类） | **0** | `BUILD SUCCESSFUL in 6m 35s` |

8 条 skipped 与 `197dd90`/`9c389e3` 同名同数（`ShellRunnerTest` 4 + `ShellToolExecutionTest` 4）。未验证：Linux/POSIX 未跑、独立 CI 仍空、本 SHA 未构建/未安装 APK、未操作手机（设备矩阵仍为历史 4 通过 / 0 失败 / 3 未测）。


## 2026-11-01 第56轮（JVM 全量首次全绿于 `197dd90` 本体 + 6 项历史失败的真实修复，未实机）

测什么：第55轮把 6 项失败定性为「Windows 宿主语义、与本 PR 无关」，但全量始终 `exit=1`。本轮不再只归因——**先补测试再改生产代码**，把这 6 项真正修绿，并让全量在同一 HEAD 上给出 `exit=0`。

怎么测：`source env.sh`（JDK `17.0.20.1+1`、SDK 在仓库 `tools/`），`src/closepaw` 下 `./gradlew --offline :app:testDebugUnitTest --rerun-tasks`；统计读 `app/build/test-results/testDebugUnitTest/*.xml` 求和，不采信控制台摘要。机器生成汇总：`docs/evidence/pr4-197dd90-junit-summary.txt`（271 类逐类计数 + 8 条 skipped 名单）。

| 阶段 | HEAD | 结果 | exit |
|---|---|---|---|
| 基线（改动前，两个失败类） | `be6d87b` | 55 tests / **6 failed** / 0 errors / 0 skipped | 1 |
| 新增回归测试后、生产未改（RED） | `be6d87b`+工作区 | MemoryStoreTest **34 tests / 6 failed**（含新用例 `expected to be true`） | 1 |
| 修后定向 `ai.closepaw.memory.*` + `ai.closepaw.agent.cognition.skills.*` | 工作区 | **118 tests / 0 failed / 0 errors / 0 skipped** | 0 |
| **全量 `:app:testDebugUnitTest`** | **`197dd90`** | **2529 tests / 0 failed / 0 errors / 8 skipped**（`BUILD SUCCESSFUL in 7m 5s`） | **0（首次全绿）** |

根因（不是推测，是探针 + 测试实跑）：`MemoryStore.atomicWrite` 用 `File.renameTo` 覆盖已存在目标，Windows 返回 false → 抛 `IOException("Failed to replace …")` → `append()` 返回 false。第55轮的 JDK 探针已经实测过 `renameTo over EXISTING = false`；本轮据此把写法换成 `Files.move(REPLACE_EXISTING[, ATOMIC_MOVE])`（`AtomicMoveNotSupportedException` 才降级，替换本身从不跳过），并把 `AgentSkillCatalogTest` 里硬编码 POSIX 分隔符的 `endsWith("my-skill/SKILL.md")` 换成**更严的全路径相等**。

8 条 skipped 全部是既有 `@Ignore`/环境跳过（`ShellRunnerTest` 4 + `ShellToolExecutionTest` 4，Android shell 依赖），本轮未增未减。

未验证：Linux/POSIX 宿主上仍未跑过（WSL 无 `java`/`curl`/`wget`/`python3`/`unzip`，`sudo` 需密码，无 `docker`）；本 SHA 未构建 APK、未装、未操作手机；设备矩阵维持历史 4 通过 / 0 失败 / 3 未测（旧 APK）。



## 2026-11-01 第55轮（JVM 实跑于当前 HEAD `a6a498f` 本体 + JDK 平台语义探针，未实机）

测什么：复审 `6557244443`（HEAD `a6a498f`）指出两条可核对事实：①第54轮的 JVM 数字实际跑在**父提交** `179f762` 上，不能写成「在 `a6a498f` 上实跑」；②日志只在本机 gitignored 目录，「审查方无法独立核验」。本轮把这**两条都消掉**，不改代码。

怎么测：`source env.sh`（JDK `17.0.20.1+1`、SDK 在仓库 `tools/`），`src/closepaw` 下
`./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.*' --rerun-tasks --console=plain`，
再跑一次不带 `--tests` 的全量；统计直接读 `app/build/test-results/testDebugUnitTest/*.xml`（求和 `tests/failures/errors/skipped`），不采信控制台摘要。汇总已入仓库：`docs/evidence/pr4-a6a498f-junit-summary.txt`（271 个测试类逐类计数 + 6 条失败断言原文，机器生成）。

实际结果（HEAD = `a6a498f2dcd434b781230c4c797f7ebb8a55cbe5`，`git diff --stat 179f762 HEAD` 仅 3 个 docs 文件）：

| 任务 | HEAD | 结果 | exit | 耗时 |
|---|---|---|---|---|
| 定向 `ai.closepaw.voice.*` | `a6a498f` | **423 tests / 0 failed / 0 errors / 0 skipped** | 0 | 5m27s |
| 全量 `:app:testDebugUnitTest` | `a6a498f` | **2528 tests / 6 failed / 0 errors / 8 skipped** | **1（不是全绿）** | 6m20s |

全量 6 项失败（与 `179f762`、`fafe365`、`5789be4` 逐条同名，本轮未改未跳）：

```
ai.closepaw.agent.cognition.skills.AgentSkillCatalogTest :: entry filePath points to SKILL md
ai.closepaw.memory.MemoryStoreTest :: append with duplicate heading inserts under last occurrence
ai.closepaw.memory.MemoryStoreTest :: append on file with missing heading appends new section at EOF
ai.closepaw.memory.MemoryStoreTest :: append under heading followed by free text inserts before next heading
ai.closepaw.memory.MemoryStoreTest :: multiple entries append to same section
ai.closepaw.memory.MemoryStoreTest :: app entries stay inside their target section
```

失败断言原文（取自 XML，不是转述）：`AgentSkillCatalogTest` → `expected to end with: my-skill/SKILL.md | but was: C:\...\my-skill\SKILL.md`；`MemoryStoreTest` → 4 条 `expected to be true`，1 条 `expected: 2 | but was: 1`。

平台语义探针（`docs/evidence/pr4-a6a498f-platform-probe/Probe.java` + 同目录 `output-windows11-jdk17.txt`，JDK 17 真实执行；第57轮按复审 follow-up 改名并脱敏）：

```
renameTo over EXISTING target = false ; target content = old ; tmp still exists = true
renameTo over MISSING target  = true ; target content = new
Files.move(REPLACE_EXISTING) = true ; content = v3
getPath() = C:\...\probe16571310999341493481\my-skill\SKILL.md
endsWith("my-skill/SKILL.md") = false ; File.separator = '\' ; os.name = Windows 11
```

**验证级别**：这两类失败在 Windows 宿主上的成因是**机制级实证**（JDK 自身 API 复现 `MemoryStore.atomicWrite` 的三行 + 断言用正斜杠做后缀比较），不是设备或真机验证。**Linux 宿主上这 6 项仍未被跑过**——WSL 实测无 `java`/`curl`/`wget`/`python3`/`unzip`，`sudo` 需要密码，本机无 `docker`，故自动执行不可行。

**未验证（明确列出）**：Linux/POSIX JVM 上是否转绿；独立 CI（本 SHA 无 workflow run / commit status）；真机（当前 SHA 未构建 APK、未安装、未操作手机，设备矩阵维持历史 4 通过 / 0 失败 / 3 未测，来自旧 APK）。

## 2026-11-01 第54轮（JVM 实跑：HEAD `179f762` 定向 + 全量，未实机）

测什么：**当前 HEAD 在 Kotlin 侧此前没有任何实跑证据**（第51–53轮只跑 Python；第53轮明写未跑 Gradle），而 PR 正文/复审引用的 423 / 2528 属于 `5789be4`、`fafe365`。复审 B3 要求的「同一 HEAD 可复核」在这一环上是空的，本轮补上。不改代码，只补证据。

怎么测：`source env.sh`（JDK `17.0.20.1+1`、SDK 在仓库 `tools/`），在 `src/closepaw` 下
`./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.*' --tests 'ai.closepaw.termux.*'`，
再跑一次无 `--tests` 的全量；统计直接读 `app/build/test-results/testDebugUnitTest/**/*.xml`（JUnit XML 的 `tests/failures/errors/skipped` 求和），不采信控制台摘要。日志：`logs/pr4-round54-targeted.txt`、`logs/pr4-round54-full.txt`（gitignored，本机）。

实际结果：

| 任务 | HEAD | 结果 | exit |
|---|---|---|---|
| 定向 `ai.closepaw.voice.*` + `ai.closepaw.termux.*` | `179f762` | **459 tests / 0 failed / 0 errors / 0 skipped**（voice 423 + termux 36） | 0 |
| 全量 `:app:testDebugUnitTest` | `179f762` | **2528 tests / 6 failed / 0 errors / 8 skipped** | **1（不是全绿）** |

全量 6 项失败逐条（与 `fafe365`、`5789be4` 同名，本轮未改未跳过）：

```
ai.closepaw.agent.cognition.skills.AgentSkillCatalogTest :: entry filePath points to SKILL md
ai.closepaw.memory.MemoryStoreTest :: append with duplicate heading inserts under last occurrence
ai.closepaw.memory.MemoryStoreTest :: append on file with missing heading appends new section at EOF
ai.closepaw.memory.MemoryStoreTest :: append under heading followed by free text inserts before next heading
ai.closepaw.memory.MemoryStoreTest :: multiple entries append to same section
ai.closepaw.memory.MemoryStoreTest :: app entries stay inside their target section
```

过程问题（如实）：第一次跑用前台调用，被 180 s 上限截断；残留 Gradle/Kotlin 守护进程占着
`app/build/intermediates/.../processDebugResources/R.jar`，下一跑在 `:app:processDebugResources`
直接 `java.io.IOException: Couldn't delete ...R.jar`（BUILD FAILED in 25s）——**那次不是测试失败，是构建没起来**。
`./gradlew --stop` + 删该 `R.jar` + 清空 `test-results` 后重跑，上表数字全部来自这次干净重跑；
被截断那次残留的 423 条旧 XML 在重跑前删除，未混入统计（所以 459 是重跑的真实求和，不是旧结果）。

未验证 / 做不了：

- **POSIX（Linux）宿主上这 6 项是否转绿：未验证**。本机 WSL Ubuntu 实测 `java: command not found`、`~/.gradle` 不存在（无 caches）、无 Android SDK，`--offline` Gradle 构建在该环境不成立；联网拉 JDK/Gradle/SDK 超出本轮边界，故**未做**，不写成「已在 POSIX 验证」。
- 6 项失败的性质仍是**源码机制归因**（Windows 路径分隔符断言 + `File.renameTo` 覆盖既有目标返回 false）+ **Windows 同环境旧 SHA `4d1e78d` 对照**（`55 tests / 6 failed`，失败名一致）。没有一次在 Linux JVM 上跑过同一批用例。
- 真机：未构建 APK、未安装、未操作设备。设备矩阵仍是 **4 通过 / 0 失败 / 3 未测**，且属旧 APK/旧 SHA；本 SHA 实机需用户重新授权。

## 2026-11-01 第53轮（Python 双平台实跑：Windows + WSL Ubuntu，未实机）

测什么：第52轮遗留的「7 条 Python 失败**逐条归因未闭环**」（复审 `6556347173` 的 B3 要求，第52轮自己写明 5/6/7 未逐行确证、且「POSIX 上是否转绿未验证」）。本轮补的是**证据**，不是新功能。

怎么测：把 `src/closepaw/tools/termux-bridge` 原样复制到 WSL Ubuntu 的**原生文件系统**（`~/bridge-test`，避开 `/mnt/c` 的 drvfs 语义），复制前后 `closepaw_bridge.py`、`tests/conftest.py`、`tests/test_bridge_auth.py`、`tests/test_closepaw_bridge.py`、`pytest.ini` 的 sha256 **逐一相同**（`cf92bb2c…` / `6a243868…` / `1dbc0b6b…` / `9c9622d3…` / `e69d42a2…`），即两侧跑的是同一份源码。WSL 侧没有 pytest，用 Windows 侧 venv 的纯 Python `site-packages` 以 `PYTHONPATH` 注入（pytest 9.2.0 及依赖均无 C 扩展），不是项目依赖变更。

实际结果：

| 环境 | 时间点 | 结果 |
|---|---|---|
| Windows 11 / CPython 3.11.15 / pytest 9.2.0 | 修前（HEAD `fc2cef5`） | **21 passed / 7 failed / 1 skipped**（exit=1） |
| WSL Ubuntu / CPython 3.12.3 / Linux 6.18 WSL2 / 原生 fs | 修前（同一份源码） | **28 passed / 1 failed**（exit=1） |
| WSL Ubuntu（同上） | 修后 | **29 passed / 0 failed**（exit=0） |
| Windows 11（同上） | 修后 | **21 passed / 7 failed / 1 skipped**（exit=1，失败项名称不变） |

**结论（实测，不是分析）**：7 条 Windows 失败里 **6 条是宿主语义**（在 POSIX 上全绿），**1 条是测试缺陷**——`test_client_disconnect_kills_process_group_and_releases_exec_lock` 在 POSIX 上也失败，原因不是环境。

逐条归因（本轮有实跑支撑，替换第52轮的「未确证」）：

| # | 测试 | Windows | POSIX | 归因 |
|---|---|---|---|---|
| 1 | `test_stale_daemon_pidfile_is_overwritten_by_new_daemon` | fail | **pass** | 宿主：`Popen.pid ≠ 子进程 os.getpid()`（第52轮已实证） |
| 2 | `test_live_old_bridge_is_health_probed_then_replaced` | fail | **pass** | 同上 |
| 3 | `test_cwd_is_constrained_to_resolved_workspace` | fail | **pass** | 宿主：`~` 在 Windows 读 `USERPROFILE`，fixture 只改 `HOME` |
| 4 | `test_port_in_use_exits_non_zero_and_reports_error` | fail | **pass** | 宿主：WSAEACCES 10013，`errno` 不是 `EADDRINUSE` |
| 5 | `test_exec_timeout_kills_process_group` | fail | **pass** | 宿主：Windows 无 POSIX 进程组 |
| 6 | `test_busy_exec_returns_http_409_without_waiting` | fail | **pass** | 宿主：同上（标志文件由 shell 子进程产出） |
| 7 | `test_client_disconnect_kills_process_group_and_releases_exec_lock` | fail | **修前也 fail** | **测试缺陷**：手写 HTTP 请求**不带 `Authorization`**，daemon 直接 401、命令根本没有启动 |

第 7 条的证据链（真跑）：

- WSL 下用同一 fixture 起 daemon，手写请求**不带** token → `HTTP/1.1 401 Unauthorized`，`disconnect_child.pid` **不存在**；
- 带 `Authorization: Bearer <TOKEN>` 发同一命令 → 命令真的执行（进程组被杀、锁释放，测试全绿）；
- 因此第51轮加鉴权时漏改了这一条手写请求，而它在 Windows 上又被路径问题掩盖：命令里的 pidfile 参数是 Windows 路径 `C:\Users\<user>\disconnect_child.pid`，`bash -c` 吃掉反斜杠后写成一个**字面文件名** `C:Users<user>AppDataLocalTemppid.pid`（本机 probe 直接复现：`bash -c 'sleep 30 & echo $! > C:\Users\<user>\win.pid'` 后目录里只有 `C:Users<user>…win.pid`），所以断言找不到 `child_pid_file`。

修了什么：只改测试——那条手写请求补上 `Authorization: Bearer {TOKEN}`（`TOKEN` 本来就是 conftest 写进 `~/.closepaw/token` 的值），**没有改任何断言**、没有放宽鉴权、没有动生产代码。Windows 侧该条仍红，原因是上面的路径转义，属宿主语义，不修。

未验证：真实 Android/Termux 上跑同一套测试（无实机、未装包）；Windows 侧 7 条仍红，其中第 7 条的 Windows 归因是**本次 probe 实证**，其余 6 条以「POSIX 全绿」作为宿主语义的判定依据（未逐条给出 Windows 内核层面的直接证据）。

## 2026-11-01 第52轮（仅 Python 本地 + 接管脚本，未实机；修的是第51轮自己引入的回归）

测什么：复审 `6555767212`→ 之后冻结复审（评论 `6556347173`，HEAD `fafe365a`）的 **B3 测试归因**。该复审指认：第51轮给 `/v1/health` 加 token 鉴权后，`health_is_bridge()` 仍发**不带 Authorization** 的 GET，必然 401，于是「旧 daemon 是合法的」永远判不出来 —— 因此 `test_live_old_bridge_is_health_probed_then_replaced` 的失败**不是**环境问题，而是本轮改动引入。

结论：**复审 B3 成立**，且影响不止测试：pidfile 指向合法旧 daemon 时，新 daemon 一律 `port_in_use` 退出，App 的 `TermuxBridgeManager.restart()` 链路会被自己挡住。已修（探针带上本地 token），并**没有**放宽鉴权边界（无 token / 错 token 的探针仍然判「不是我们」）。

实际结果（本机 Windows 11，`uv venv` + pytest 9.2.0，非项目依赖变更）：

| 步骤 | 结果 |
|---|---|
| 修前基线（HEAD `fafe365`，`python -m pytest -q`） | **20 passed / 7 failed / 1 skipped**，exit=1 |
| 新测试 `test_probe_with_the_daemon_token_recognizes_our_own_daemon`，生产未改 | **RED**：`TypeError: health_is_bridge() takes 1 positional argument but 2 were given` |
| 修后 Python 全套 | **21 passed / 7 failed / 1 skipped**，exit=1；失败项名称与修前**完全相同的 7 条**（新增 1 条通过） |
| 接管脚本（修前，`%TEMP%/probe_bridge.py`） | 无 token GET → **401**；`health_is_bridge(port)` → **False**（daemon 实际在跑）；第二个 daemon **exit=1 / stderr=`port_in_use`**，第一个仍在跑，pidfile 未变 |
| 接管脚本（修后，`%TEMP%/takeover_probe.py`） | 无 token → `False`，带 token → `True`；第二个 daemon **存活并接管**，pidfile pid `415144`→`316828`，旧 pid `pid_alive=False`，带 token health **200 / identity=closepaw-bridge** |

7 条仍失败的**逐条归因（本轮实测，不再笼统写「POSIX 语义」）**：

1. `test_stale_daemon_pidfile_is_overwritten_by_new_daemon` / 2. `test_live_old_bridge_is_health_probed_then_replaced`：**本机解释器怪癖** `Popen.pid ≠ 子进程 os.getpid()`。实证：`Popen([python,'-c','import os;print(os.getpid())'])` → popen `417856` / 子进程自报 `413252`；换成系统 python 也复现（`16180` / `404648`）。测试断言 `pidfile["pid"] == bridge.process.pid` 因此必然不等。**这条与代码、与 POSIX 语义都无关**；接管逻辑本身已由上面的脚本实证可用。**POSIX 上是否已转绿未验证**（本轮没有 POSIX 环境）。
3. `test_cwd_is_constrained_to_resolved_workspace`：实得 `400 invalid_request`（不是 `workspace_escape`）。`cwd="~/closepaw/workspace/sub"` 中的 `~` 在 Windows 由 `USERPROFILE` 解析，而 fixture 只改 `HOME` → 解析结果落在 workspace 外。**平台差异，未改代码**。
4. `test_port_in_use_exits_non_zero_and_reports_error`：实得 stderr `internal_error: 以一种访问权限不允许的方式做了一个访问套接字的尝试。`（WSAEACCES 10013，`errno` 不是 `EADDRINUSE`），所以没打印 `port_in_use`。**平台差异，未改代码**；Android 侧无影响。
5. `test_exec_timeout_kills_process_group`：实得 `http.client.RemoteDisconnected`（Windows 无 POSIX 进程组语义）。**未逐行确证**。
6. `test_client_disconnect_kills_process_group_and_releases_exec_lock` / 7. `test_busy_exec_returns_http_409_without_waiting`：子进程产出的标志文件（`disconnect_child.pid` / `busy.ready`）**没有被创建**，与 5 同族（shell/进程组），**未逐行确证**。

修了什么：`closepaw_bridge.py` 的 `health_is_bridge(port, token=None)` 在拿到 token 时发 `Authorization: Bearer`，否则不发（失败关闭）；`kill_old_bridge(pidfile, token)` 与 `main()` 传同一个 token。新增 1 条单测。**未改**鉴权相关的 401 行为、未改端口/身份判定顺序。

未验证：真实 Android/Termux 上 `restart()` 全链路（本轮无实机，也未装包）；POSIX 上这 7 条是否只剩 6 条。上面 3/4/5/6/7 的归因是**本机实测到的现象**，只对第 1/2 条和平台报错文本给了直接证据，5/6/7 未做逐行确证，因此**不**宣称「全部是环境问题」。

## 2026-11-01 第51轮（仅 JVM + Python + 干净 clone，未实机）

测什么：复审 `6555767212`（针对 HEAD `5789be4`）的两条 BLOCKER —— **B2/P1 Termux bridge 未认证 exec**、**B3 失败归因仍不能独立复核**。

怎么测（Python 侧）：`src/closepaw/tools/termux-bridge` 下 `python -m pytest -q`。本机 Hermes venv 无 pytest，用 `uv venv` 建临时环境再 `uv pip install pytest`（9.2.0），不是项目依赖变更。

怎么测（Kotlin 侧）：仓库根 `source env.sh` → `cd src/closepaw` → `./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.termux.*' --tests 'ai.closepaw.tool.impl.TermuxShellToolTest'`，再定向 `ai.closepaw.voice.*`，最后全量 `--rerun-tasks`。

实际结果：

| 阶段 | 结果 |
|---|---|
| 新 `tests/test_bridge_auth.py`(9)，生产未改 | **RED：5 failed / 3 errors / 1 passed**（无 token 也能 exec、无 token 也能 health、version 仍是 `1`、`--host 0.0.0.0` 照收、缺 token 文件照启动） |
| 实现后 Python 全套 | **20 passed / 7 failed / 1 skipped**。9 条 auth 用例全绿（1 条 POSIX 文件权限用例在 Windows 上 skip）；7 条失败全部是既有用例的 **POSIX 语义**依赖，见下（⚠ **第52轮更正**：`test_live_old_bridge_is_health_probed_then_replaced` 实为本轮鉴权改动引入的回归，不是环境问题；见上方第52轮） |
| 改动前同机 Python 全套（`git archive HEAD` 到临时目录跑） | **1 passed / 7 failed / 11 errors**：`CLOSEPAW_HOME` 未隔离时所有 worker 共用真实 `~/.closepaw/bridge.pid`，互相 kill，fixture 直接报错 |
| Kotlin 定向 `ai.closepaw.termux.*` + `TermuxShellToolTest` | **52 tests / 0 failed**，BUILD SUCCESSFUL，exit=0 |
| 定向 `ai.closepaw.voice.*` | **423 / 0 failed / 0 errors / 0 skipped**，exit=0（与第㊿轮持平，本轮未改 voice） |
| 全量 `:app:testDebugUnitTest`（`--rerun-tasks`） | **2528 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（第㊿轮 2513，新测试 +15）；6 项失败名与第㊿轮完全一致 |
| `main` 干净 clone + 仅构建输入（第 5789be4 的 PNG、`tools/termux-bridge`、生成 `res/raw`）跑两个失败类 | **BUILD FAILED in 16s**，`:app:compileDebugKotlin` **53 处 `Unresolved reference`**（`ai.closepaw.voice.tools` 包在 `main` 树上根本不存在） |
| 6 项失败的归因取证 | 失败类的生产/测试文件 `git rev-parse origin/main:<path>` 与 `HEAD:<path>` **完全相同（5/5 文件）**；`renameTo` 探针在 Windows 11 上 `renameTo` 覆盖已存在文件返回 **false**、目标仍是旧内容 |

7 条 Python 失败逐条（**均为 POSIX 语义依赖，不是本轮引入**——⚠ 该结论已被第52轮推翻并逐条更正，原文保留以免改写历史）：`test_exec_timeout_kills_process_group`、`test_client_disconnect_kills_process_group_and_releases_exec_lock`（进程组信号语义）、`test_cwd_is_constrained_to_resolved_workspace`（`Path.home()` 在 Windows 读 `USERPROFILE`、不读 `HOME`）、`test_port_in_use_exits_non_zero_and_reports_error`（Windows 抛 WSAEACCES 10013 而不是 `EADDRINUSE`）、`test_stale_daemon_pidfile_is_overwritten_by_new_daemon`、`test_live_old_bridge_is_health_probed_then_replaced`、`test_busy_exec_returns_http_409_without_waiting`。

修复（B2）：bridge 只接受 `Authorization: Bearer <token>`，token 由 App 生成（32 字节 base64url）、部署时写入 `~/.closepaw/token`（`umask 077` + `chmod 600`，Termux 私有目录）；**没有 token 文件或长度 < 32 时 daemon 直接 `token_missing` 退出**，`--host` 只接受回环地址（否则 `host_not_allowed` 退出）；`BRIDGE_VERSION` 升到 `2`，App 的 health 探针 401 → `HealthProbe.Unauthorized` → 按 `BRIDGE_OUTDATED` 重新部署；`TermuxShellTool` 无 token 时本地拒绝（`bridge_auth_unavailable`），401 报 `bridge_auth_rejected`。

未验证（照实写）：真实 Termux 上 `~/.closepaw` 目录权限、`chmod` 是否被 `com.termux` 的 SELinux 域允许、Android 跨应用 loopback 可达性（这既是威胁模型也是未实测前提）、老 daemon 被 401 触发重新部署的完整链路 —— **全部未实机**。Kotlin 侧测试是与实现同批写的，**没有单独取 RED**（只有 Python 侧做了测试先行 RED→GREEN）。

## 2026-11-01 第㊿轮（仅 JVM + 干净 clone，未实机）

测什么：复审 `6555456830`（HEAD `50a4c6a`）的 **B2/P1** 与 **B3**。

- **B2/P1**：`ActionMemory.undo()` 只在 `dispatched != false` 时消费记忆。但 `dispatched == false` 同时覆盖两种相反事实：①设备在**下发前**拒绝（相机不可用）；②逆操作的**目标已经成立**（用户自己把手电关了，`TorchControl.set(false)` 走 `alreadySatisfied`，`SUCCESS + verified=true + dispatched=false`）。第②种情况下旧 Entry 被保留，越过 120 s 窗口前用户再把灯打开并第二次说「撤销」，就会下发 `set(false)` 关掉用户新开的状态，并把这次变更归到助手更早的动作上。开/关两个方向对称。
- **B3**：干净检出无法复现构建/测试。根 `.gitignore` 的 `*.png` 把启动图标（`res/drawable-*/ic_launcher_foreground.png`、`res/mipmap-*/ic_launcher{,_round}.png`）一并忽略，`/src/closepaw/tools/` 把 `copyClosePawBridge` 需要的 `tools/termux-bridge/closepaw_bridge.py` 一并忽略。

怎么测：仓库根 `source env.sh` → `cd src/closepaw` → 新测试先写，`./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.tools.TorchUndoConsumptionTest' --rerun-tasks`（取 RED），实现后同任务 + `TorchUndoDispatchTest`（取 GREEN），再 `--tests 'ai.closepaw.voice.*'`，最后全量。B3 用两次真实 clone（`https://github.com/mo-mx/AndroidAgent`）验证：`50a4c6a`（修复前）与 `5789be4`（修复后）。

实际结果：

| 阶段 | 结果 |
|---|---|
| `TorchUndoConsumptionTest` RED | **7 tests / 2 failed / 0 errors**，两条新用例全红（旧 5 条仍绿） |
| 实现后同任务 + `TorchUndoDispatchTest` | `BUILD SUCCESSFUL`，exit=0 |
| 定向 `ai.closepaw.voice.*` | **423 / 0 failed / 0 errors / 0 skipped**，exit=0（第㊾轮 421，+2） |
| 全量（本机） | **2513 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿** |
| 干净 clone `50a4c6a` `:app:processDebugResources` | **`BUILD FAILED in 3s`**，`AAPT: error: resource drawable/ic_launcher_foreground not found`（`ic_launcher.xml:4`、`ic_launcher_round.xml:4`） |
| 干净 clone `5789be4` 资源链接 | **`RES_LINK_EXIT=0`**，且 `res/raw/closepaw_bridge_py` 由 `preBuild` 生成（14109 B） |
| 干净 clone `5789be4` 全量 | **2513 / 6 failed / 8 skipped**，`TESTS_EXIT=1`，失败名逐条相同 |

修复：`ActionMemory.undo` 的消费条件从"是否下发"改为"是否成功"——`keepForRetry = !r.ok && r.dispatched == false`；
成功的逆操作（已下发 或 目标已成立）一律消费记忆，只有**证明未触达设备**的失败才保留以便诚实重试。
`.gitignore` 两处改为精确反向包含（`*.png` 仍忽略其它所有图片；`/src/closepaw/tools/*` 只放行 `termux-bridge/`），
新增 `docs/BUILD_REPRODUCIBILITY.md` 记录复现步骤、修复前的真实失败、以及 6 项失败的归因边界。

未验证：真实相机在"用户手动改变状态后助手再撤销"时的回调时序；`alreadySatisfied` 在真机上是否总能在
1200 ms 基线窗口内拿到 baseline——**均未实机**。6 项失败的"非本 PR 引入"仍是源码分析 + 同环境旧 SHA 对照，
**没有在 `main` 干净检出上复跑**。未构建 APK、未安装、未操作手机、未调用付费 API。设备矩阵仍 4 通过 /
0 失败 / 3 未测（属旧 APK/旧 SHA），本 SHA 需用户**重新授权**才能上机。

证据：本机 gitignored `logs/pr4-round50-{green1,targeted,full,clean-before,clean-after}.txt`，不入库。
RED 那一跑（7/2 failed）只留在终端输出、未落盘，本条记录即为其内容。

## 2026-11-01 第㊾轮（仅 JVM，未实机）

测什么：复审 `6555010682`（HEAD `8b48f3a`）的 B1/B2 与 B3。**B1/B2**：手电「本来已经是目标状态」仍被记成「本轮打开了灯」——注册回调给出 `baseline=true`，用户说「打开手电筒」，随后同状态回调让 `confirmed && observed == on && baseline != null && baselinePreRequest` 全真 → `SUCCESS + verified=true` → `ActionMemory` 记下可撤销 `ENABLE/TORCH`，「撤销」随后 `TorchControl.set(false)` 关掉用户自己开的灯；关闭方向同理。**B3**：交付证据门槛——全量 6 项失败需给出同环境旧 SHA 基线。同时推进同类的离线项：`IntentActions.setAlarm` 的 6 s 等待用墙上时钟、且 `nextAlarm(ctx)` 丢掉 `known`，把「读不到闹钟状态」说成「没有登记」，并播「已经打开闹钟界面，可能还需要你按一下保存」（请求带 `EXTRA_SKIP_UI=true`，可能根本没有界面）。

怎么测：仓库根 `source env.sh` → `cd src/closepaw` → `./gradlew --offline --rerun-tasks :app:testDebugUnitTest --tests 'ai.closepaw.voice.tools.AlarmTriggerWaitTest'`（新测试先写、生产只加保持旧行为的脚手架，取 RED）；再 `--tests 'ai.closepaw.voice.tools.TorchAlreadyAtTargetTest'`（生产未改，取 RED）；实现后 `--tests 'ai.closepaw.voice.*'`，最后全量同任务不带 `--tests`。B3 基线：`git worktree add ../AndroidAgent-baseline-4d1e78d 4d1e78d`，把本机 gitignored 的 `res/drawable-*dpi`、`res/mipmap-*dpi`、`res/raw`（`*.png` 与 `raw/` 被根 `.gitignore` 排除，工作树里有、git 里没有）复制进该 worktree，然后跑 `--tests 'ai.closepaw.agent.cognition.skills.AgentSkillCatalogTest' --tests 'ai.closepaw.memory.MemoryStoreTest'`。

实际结果：`AlarmTriggerWaitTest`(6) **RED 6 tests / 4 failed / 0 errors**（Truth 断言失败）。实得：反向校时后读数 `35562739` 次（期望 3，即「有界等待被拉成无界」）、前跳后 `96949444` 次（期望 1）；闹钟读失败与可读空态都播「已经打开闹钟界面，可能还需要你按一下保存」。首跑还有 3 条 `NullPointerException`（我的夹具没桩 `Intent.putExtra(String, Boolean)`），属夹具错误，补桩后以第二跑为准。`TorchAlreadyAtTargetTest`(6) **RED 6 tests / 3 failed / 0 errors**：已在目标态时播「手电筒已打开」、detail 无 `already-at-target`、`verified=true + dispatched=false` 的结果仍被 `recordResult` 记成 `Entry(action=ENABLE, target=TORCH,…)`（即真实存在的撤销记忆漏洞）。实现后：定向 `ai.closepaw.voice.*` **421 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㊽轮 409，+12）；全量 `:app:testDebugUnitTest` **2511 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（失败名与旧 SHA 完全一致，见下）。

B3 同环境旧 SHA 基线（**本轮首次真正跑出来**）：`4d1e78d` 上两个失败类 **55 tests / 6 failed / 0 errors / 0 skipped**，失败名逐条一致（`AgentSkillCatalogTest > entry filePath points to SKILL md` 1 项、`MemoryStoreTest` 5 项）。故当前 HEAD 的这 6 项失败不是本 PR 引入。首次基线跑不起来是**环境**问题而非测试问题：`4d1e78d` 的 res 树缺少被 gitignore 的 launcher PNG（AAPT `resource drawable/ic_launcher_foreground not found`）与 `res/raw`（Kotlin `Unresolved reference 'raw'`），补齐后才编译。

过程问题（如实）：B1/B2 首版把「已满足」分支放在 `observed != on` 矛盾分支之前，导致 3 条既有断言变红（`SwitchReadbackEvidenceTest` 反向状态应 `EXECUTION_ERROR`、`TorchAttributionTest` 未确认应 `TIMEOUT`、`TorchLateRegistrationAttributionTest` 迟到基线不应 `verified=true`）。改法是把新分支移到矛盾分支之后并要求 `confirmed`；另外 `TorchLateRegistrationAttributionTest` 的第一条用例没把 `baselinePreRequest = t.baselinePreRequest` 传进去（夹具把「迟到」声明成「请求前」，与生产调用不一致），补上该参数，**断言一条未改**。

未验证：真实 CameraManager 是否会在「重复设置同一状态」时再发一次同状态回调（本轮的错归属反例来自代码路径与可输入事件序列，未在手机复现）；真机上注册事件是否总先于 `setTorchMode`、`baseline` 是否可靠；闹钟路径上 `nextAlarmClock` 何时抛异常、三星时钟是否会弹保存界面——全部**未实机**。本轮未构建/安装 APK、未操作手机、未调用付费 API。设备矩阵仍 4 通过 / 0 失败 / 3 未测（属旧 APK/旧 SHA），本 SHA 需用户**重新授权**才能上机。

证据：本机 gitignored `logs/pr4-round49-{red-alarm,red-torch,green-tools,green-directed,full,baseline-4d1e78d}.txt`，不入库。

## 2026-11-01 第㊽轮（仅 JVM，未实机）

测什么：复审 `6554387042` 的 1 条 P1 + 2 条 P2。P1：手电 `RequestTracker` 把收到的第一条回调无条件当基线，不看它是否早于 `markRequested()`；基线窗口超时后下发请求，迟到事件可能已是请求后状态 → 被记成基线 → 下一条匹配事件判 `verified=true` 并写出可撤销 `ENABLE/TORCH`（反向则吞掉真实事后事件 → 假超时）。P2：蓝牙 `awaitState` 的 1.2 s 窗口用 `System.currentTimeMillis()`，校时可延长/缩短；P2：蓝牙 `enable()`/`disable()` 抛非 `SecurityException` 异常被吞成 `false`，落进「API 拒绝」分支播「Android 不允许普通应用直接切换蓝牙」。

怎么测：仓库根 `source env.sh` → `cd src/closepaw` → `./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.tools.TorchLateBaselineTest' --tests 'ai.closepaw.voice.tools.BluetoothSettleClockTest' --rerun-tasks --console=plain`（新测试先写、生产仅旧行为脚手架，取 RED）；实现后跑 `--tests 'ai.closepaw.voice.tools.*'`，再 `--tests 'ai.closepaw.voice.*'`，最后全量同任务不带 `--tests`。

实际结果：新 `TorchLateBaselineTest`(4) + `BluetoothSettleClockTest`(5) **RED 9 tests / 3 failed / 0 errors**（Truth 断言失败，非编译错误；6 条控制组/时钟用例本就通过）。实得：迟到基线后 `baselinePreRequest` 实得 `true`、`verified` 实得 **`true`**（期望 null）；两次命中态回调后 `verified` 实得 **`true`** 且 `observed` 非空；蓝牙 `enable()` 抛 `IllegalStateException` 实得 **`SYSTEM_RESTRICTED`** 且播报含「不允许普通应用」。实现后 `ai.closepaw.voice.tools.*` **GREEN**（exit=0）；定向 `ai.closepaw.voice.*` **409 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㊼轮 400，+9）；全量 **2499 tests / 6 failed / 0 errors / 8 skipped**（Gradle exit=1，**不是全绿**，失败仍是既有 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，未重跑旧 SHA 基线）。

过程问题（如实）：`awaitState` 默认时钟先写成裸 `System.nanoTime()`（纳秒）却与毫秒窗口比较，1.2 s 窗口变成 1.2 µs；既有控制组 `turn on accepted and settled …`、`turn off accepted and settled …`、`bluetooth evidence is what the terminal gate needs to confirm` 因此变红（`verified` 实得 null）。改成 `System.nanoTime() / 1_000_000` 后转绿——是我本轮引入又被既有测试抓住的错误。另：P2 的时钟修复**没有真正的 RED**，因为改前没有注入点、无法在 JVM 模拟校时；注入点与实现同批提交，测试断言「注入的单调时钟驱动 deadline（前跳立即结束）」「时钟不前进时由 10 次读数上限兜底」「正常 settle 仍返回读次数」，而默认源从 `currentTimeMillis` 换成 `nanoTime` 只有源码审查证据。

未验证：真实 CameraService 是否总在 `setTorchMode` 之前送注册事件、迟到多久、注册事件丢失时设备行为；目标机（SM-F731U1 / Android 17）上蓝牙 `enable()` 是返回 false 还是抛异常、`BLUETOOTH_CONNECT` 是否已授予——全部**未实机**。可用性代价：注册事件不到的设备上，手电结果只能停在「已下发、未确认」，不再报成功。本轮未构建/安装 APK、未操作手机。

证据：本机 gitignored `logs/pr4-round48-{red,green1,green2,targeted,full}.txt`，不入库。

## 2026-11-01 第㊼轮（仅 JVM，未实机）

测什么：`queryTimers` 兜底分支播「页面上至少能看到 N 个倒计时在运行」时，N 是怎么来的。它来自 `visibleTimerCards`（纯文本计数：任一时长文本 + 任一卡片控件 + `取消` 个数），既把**暂停**卡片（显示 `继续`、保留冻结读数）算成运行中，也完全不看树来自哪个窗口。`cancelTimer` 已在第㊻轮收紧到 `cardRunningState`，查询路径没有。

怎么测：仓库根 `source env.sh` → `cd src/closepaw` → `./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.semantics.TimerQueryRunningBoundTest' --rerun-tasks --console=plain`（新测试先写、生产未改，取 RED）；实现后再跑同组 + `TimerCountShapeTest`/`TimerCancel*`/`TimerPausedFrozenReadoutTest`，然后 `--tests 'ai.closepaw.voice.*'`，最后全量同任务不带 `--tests`。

实际结果：新 `TimerQueryRunningBoundTest`(6) 首跑 **RED 6 tests / 5 failed / 0 errors**，其中 1 条是我自己的断言写错（`doesNotContain("倒计时在运行")` 把诚实的兜底句「没法确认现在有几个倒计时在运行」也判失败），改为 `doesNotContain("至少能看到")` 后重跑 **RED 6 / 4 failed / 0 errors**，以第二跑为准；2 条控制组（截断读、明示表头）两次都通过。实得值：两张暂停卡 → `SUCCESS` + 「页面上至少能看到2个倒计时在运行，但没法确认一共有几个」（期望 `DEVICE_UNAVAILABLE`）；运行卡+暂停卡 → 播 2（期望 1）；绑定树属于 `ai.closepaw` → 仍 `SUCCESS` 并播运行中计数（期望拒绝）；纯读数卡 → 状态已拒绝但 detail 无状态事实。实现后同组 **GREEN**（exit=0）；定向 `ai.closepaw.voice.*` **400 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㊻轮 397，新测试 +6、`TimerCountShapeTest` 下界 4 条改 1 条 −3）；全量 **2490 tests / 6 failed / 0 errors / 8 skipped**（Gradle exit=1，**不是全绿**，失败仍是既有 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，未重跑旧 SHA 基线）。

未验证：三星真实定时器卡片是否同时暴露 `暂停`/`继续` 与 `初始时长/总时长`、暂停态控件是 `text` 还是 `contentDescription`、虚拟化列表未加载卡片的真实比例——全部**未实机**；本轮未构建/安装 APK、未操作手机。可用性代价：真实卡片不给状态专属控件时，查询只能答「没法确认现在有几个倒计时」（本轮之前会给一个可能错的数字）；下界仍不是总数，不参与「那个倒计时没了」的判断。

证据：本机 gitignored `logs/pr4-round47-{red,red2,green1,targeted,full}.txt`，不入库。

## 2026-11-01 第㊺轮（仅 JVM，未实机）

测什么：蓝牙开关的**回读是否进了终态闸门**，以及「一次立即读」是否被当成平台的最终答复。`LocalCapabilities.btSet` 本来就读 `isEnabled` 后置 `verified=true`，但 `observed` 为空；解析器为「打开蓝牙」声明 `on:BLUETOOTH` + 身份 `bluetooth`，所以真翻了的开关会被判 `UNPROVEN` 并改写为「请求已经发出，但没能确认结果」。另一条：`BluetoothAdapter.enable()` 返回 true 只代表**流程已开始**，紧接着读 `isEnabled` 仍可能是旧值，旧代码据此播「Android 不允许普通应用直接切换蓝牙」——把一次未稳定的采样说成平台限制；`SecurityException`（缺 `BLUETOOTH_CONNECT`）也落进同一分支，权限问题被说成平台不允许。

怎么测：仓库根 `source env.sh` → `cd src/closepaw` → `./gradlew :app:testDebugUnitTest --offline --rerun-tasks --tests "ai.closepaw.voice.tools.BluetoothReadbackEvidenceTest"`（新测试先写、生产未改，取 RED）；实现后再跑同组，然后 `--tests "ai.closepaw.voice.*"`，最后全量同任务不带 `--tests`。

实际结果：新 `BluetoothReadbackEvidenceTest`(9) **RED 9 tests / 7 failed / 0 errors**（Truth 断言失败，非编译错误；2 条控制组本就通过）。实得值：`打开蓝牙` 端到端判 `NOT_APPLICABLE`（因为旧代码返回 `SYSTEM_RESTRICTED`）、permission 用例实得 `SYSTEM_RESTRICTED`（期望 `PERMISSION_DENIED`）、`observed` 实得 `null`、`states=[false,false,true]` 实得 `SYSTEM_RESTRICTED` 且播报含「不允许」、未稳定用例 `verified` 实得 `false`（期望 `null`）→ 实现后 **GREEN 9 / 9**（exit=0）；定向 `ai.closepaw.voice.*` **382 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㊹轮 373，+9）；全量 **2472 tests / 6 failed / 0 errors / 8 skipped**（Gradle exit=1，**不是全绿**，失败仍是既有 `AgentSkillCatalogTest > entry filePath points to SKILL md` 1 + `MemoryStoreTest` 5，本轮未改未跳过，未重跑旧 SHA 基线）。

未验证：`BluetoothAdapter.enable()/disable()` 在目标机（SM-F731U1 / Android 17）上是否被平台直接拒绝、蓝牙真实开关的稳定时间是否落在 1200 ms 窗口内、`BLUETOOTH_CONNECT` 是否已授予、`Settings.ACTION_BLUETOOTH_SETTINGS` 跳转是否成功——全部**未实机**；夹具是 MockK 合成的 adapter，只证明 `btSet` 内部契约。可用性代价：接受请求但未在窗口内稳定时，改为 `SUCCESS + verified=null + dispatched=true` 并明确说「没能看到状态变化」，不再替平台下结论。本轮未构建/安装 APK、未操作手机。

证据：本机 gitignored `logs/pr4-round45-{red,green,targeted,full}.txt`，不入库。

## 2026-11-01 第㊱轮（仅 JVM，未实机）

测什么：打开 App 内功能时，「界面有没有变」这个结论是否还从**被截断的读屏**里推出来。`AppNavigator.openFeatureResolved` 用的是 `UiAutomator.screenTexts(120)`（`readScreen` 的投影，丢掉 `textsTruncated`/`nodesTruncated`/`serviceAvailable`）：截断样本的集合差既不能证明「变了」，也不能证明「没变」，无障碍服务不可用时的空投影还被播成「界面上没有找到X」。
怎么测：仓库根 `source env.sh` 后 `cd src/closepaw`，`./gradlew :app:testDebugUnitTest --offline --rerun-tasks --tests '*AppFeatureReadCompletenessTest' --console=plain`（新测试先写、生产未改，取 RED）；实现后再跑同组，然后 `--tests 'ai.closepaw.voice.*'`，最后全量同任务不带 `--tests`。
实际结果：新 `AppFeatureReadCompletenessTest`(6) **RED 6 tests / 3 failed / 0 errors**（Truth 断言失败，非编译错误；3 条控制组本就通过）→ 实现后 **GREEN 6 / 6**（exit=0）；定向 `ai.closepaw.voice.*` **288 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㉟轮 282，+6）；全量 **2378 tests / 6 failed / 0 errors / 8 skipped**（Gradle exit=1，**不是全绿**，失败仍是既有 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，本轮未改未跳过，未重跑旧 SHA 基线）。首跑 RED 里有 1 条失败是我自己的控制组断言写错（原句含「已经进入」，被 `doesNotContain("已经进入")` 判失败），改的是断言不是生产句子，如实记录；有效 RED 以第二跑 3 failed 为准。
未验证：真机上 `readScreen(120)` 的实际截断率、各 App 的无障碍节点规模、「完整」是否等同看到整屏（可滚动/虚拟化列表）、无障碍服务被杀后的表现；完整读取下 `openFeature` 仍然只播「界面有变化，但没能确认已经进入」并 `verified=null`，没有新增对象级证据。本轮未构建/安装 APK、未操作手机。
证据：本机 gitignored `logs/pr4-round36-{red2,green1,targeted,full}.txt`（另有首跑 `red.txt`），不入库。

## 2026-11-01 第㉞轮（仅 JVM，未实机）

测什么：手电与 Wi-Fi 的**真实回读是否真的进了终态闸门**。这两处本来就有读回（`onTorchModeChanged`、`isWifiEnabled`），但 `observed` 一直为空，声明了 `on:TORCH`/`on:WIFI` 的任务被 `TerminalState` 一律判 `UNPROVEN` 并改写为「请求已经发出，但没能确认结果」。本轮把它们填进 `ObservedState`，并用解析器原话 + 闸门做端到端断言。
怎么测：仓库根 `source env.sh`，`cd src/closepaw`，`./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.voice.tools.SwitchReadbackEvidenceTest' --offline --rerun-tasks`（RED，生产代码行为未变）；实现后再跑同组，然后 `--tests 'ai.closepaw.voice.*'`，最后全量同任务不带 `--tests`。
实际结果：新测试 **RED 10 tests / 6 failed / 0 errors**（Truth 断言失败，非编译失败；4 条控制组本来通过）→ 实现后 **GREEN 10 / 10**（exit=0）；定向 `ai.closepaw.voice.*` **265 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㉝轮 255，+10）；全量 **2355 tests / 6 failed / 0 errors / 8 skipped**（Gradle exit=1，**不是全绿**，失败仍为既有 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，本轮未改未跳过）。
未验证：手电的 CameraManager 回调时序/阻塞等待（JVM 无 Looper，本轮只覆盖「回调证据 → 结论」）；Wi-Fi 的 `SYSTEM_RESTRICTED` 分支（`openWifiPanel` 需要真实 `Intent`，JVM 跑不了）；真机上这两个开关的实际播报。本轮未构建/安装 APK、未操作手机。
证据：本机 gitignored `logs/pr4-round34-{red,green1,targeted,full}.txt`，不入库。

## 2026-11-01 第㉜轮（仅 JVM，未实机）

测什么：撤回创建/取消两条「同类证据当对象结论」的判定——创建路径的「基线无同长度卡 + 表头 +1 + 回读一张该长度卡」不再判 `CONFIRMED`；取消路径的「回读仍有同长度标签」不再判 `CONTRADICTED`。两条都改为 `verified=null` 的 `UNPROVEN`，事实写进 `detail`。
怎么测：仓库根 `source env.sh` 后 `cd src/closepaw`，`./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.tools.Timer*' --rerun-tasks --console=plain`，再跑 `--tests 'ai.closepaw.voice.*'`，最后全量同任务不带 `--tests`。新/改测试 `TimerCreateReadbackTest`(10)、`TimerCancelReadbackTest`(8) 先写后实现。
实际结果：新契约先写 → RED **28 tests / 5 failed / 0 errors**（Truth 断言失败，非编译失败；5 条正是本轮改的契约）；实现后同组 GREEN **28 / 28**（exit=0）——其中一条因测试自身换绑定的根节点后 `selectedState` 需重打桩，第一次修后仍 1 failed（`DEVICE_UNAVAILABLE`），补桩后转绿，如实记录；定向 `ai.closepaw.voice.*` **248 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㉛轮 245，+3 净增）；全量 **2338 tests / 6 failed / 0 errors / 8 skipped**（Gradle exit=1，**不是全绿**，失败仍是既有 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，本轮未改未跳过）。
未验证：三星时钟是否暴露稳定 timer 对象 ID、可滚动列表是否全量枚举、表头计数刷新时序、真机创建/取消的实际播报；本轮未构建/安装 APK、未操作手机。结论：创建与取消在真机上都只播报「没法确认」，属安全拒绝，不是功能完成。
证据：全量日志本机 gitignored `logs/pr4-r33-full.txt`（不入库）；RED/GREEN/定向三段为本轮控制台输出，未单独存盘。

## 2026-11-01 第㉛轮（仅 JVM，未实机）

测什么：创建倒计时的基线/回读判定——只有「基线无同长度卡 + 表头计数恰好 +1 + 回读恰好一张该长度卡」才算创建成功，其余一律 `verified=null`。
怎么测：仓库根 `source env.sh` 后 `cd src/closepaw`，`./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.*' --rerun-tasks --console=plain`；再跑全量同任务不带 `--tests`。新测试 `ai.closepaw.voice.tools.TimerCreateReadbackTest`(9) 先写后实现。
实际结果：新测试 RED 9 tests / 8 failed / 0 errors（Truth 断言失败，非编译失败），实现后 GREEN 9/9；定向 245 tests / 0 failed / 0 errors / 0 skipped（exit=0）；全量 2335 tests / 6 failed / 0 errors / 8 skipped（Gradle exit=1，**不是全绿**，失败仍是 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，本轮未改未跳过）。
未验证：三星时钟表头计数刷新时序、卡片是否暴露 `初始时长/总时长` 原始时长标签、双标签卡结构、并发第三方创建同长度倒计时的归因漏洞；本轮未构建/安装 APK、未操作手机，创建功能在真机上很可能仍播报「没法确认」，属安全拒绝而非功能完成。
证据：本机 gitignored `logs/pr4-round31-{red,green1,targeted,full}.txt`，不入库。

## 2026-11-01 第㉚轮（仅 JVM，未实机）

测什么：撤回「取消倒计时即成功」的判定，并把计时器总数限制为页面明示陈述（表头 / 明确空态句）。
怎么测：仓库根 `source env.sh` 后 `cd src/closepaw`，`./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.semantics.*' --tests 'ai.closepaw.voice.tools.*' --rerun-tasks --console=plain`；再跑全量同任务不带 `--tests`。
实际结果：定向 236 tests / 0 failed / 0 errors / 0 skipped（exit=0）；全量 2326 tests / 6 failed / 0 errors / 8 skipped（Gradle exit=1，**不是全绿**，失败仍是 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，本轮未改未跳过）。
未验证：三星时钟是否暴露稳定 timer 对象 ID、页头计数刷新时序、可滚动列表完整性、导航栏与正文双标签卡片结构；本轮未构建/安装 APK、未操作手机。取消动作在真机上仍只会播报「无法确认最终状态」——这是安全拒绝，不是功能完成。
证据：本机 gitignored `logs/pr4-review29-{red,targeted,targeted3,full}.txt`，不入库。

## 2026-10-31 阶段1授权部署：锁屏阻塞（功能未测）

测什么：PR4 head 2ff514ff0b333f9dafe6947685c059fff91cf475 的debug构建、保留配置覆盖安装、设备基线及非破坏性回归。
怎么测：mDNS发现TLS目标后显式-s连接；getprop/dumpsys trust/accessibility；Gradle :app:assembleDebug；备份原APK与shared_prefs后install -r；重新pull已安装APK计算SHA256。
实际结果：构建exit=0、安装Success，已安装APK哈希与构建一致（98f06f77df538244db3c57e1a8a65a928b76196969d89d5d2ce273a5dbdf49e3）；shared_prefs归档前后字节相同；无障碍服务安装前后均Bound。当前用户deviceLocked=1，未绕过。
七项功能全部未测：取消不存在目标、查询不修改、300秒单位、计时器对象绑定/歧义、商店身份、二级页面反馈、创建undo拒绝。debug文字注入与真人语音均未测，不能以部署成功替代。未创建/删除/修改任何个人时钟对象。
证据：本地gitignored logs/stage1-device-build.txt、logs/stage1-device/及baseline日志；脱敏详情与逐项安全设计见 docs/phase6-stage1-device-acceptance.md。需要用户解锁后继续。没有重跑JVM全量，历史6失败不改为通过。

## 0. 测试环境（所有条目共用）

| 项 | 值 |
|---|---|
| 设备 | Samsung SM-F731U1（Z Flip5 美版，XAA），序列号不入库 |
| 系统 | Android 17（SDK 37）/ One UI 9，安全补丁 2026-10-05 |
| SoC / 内存 | SM8550（8 Gen 2）/ 8 GB 物理（MemAvailable ≈1.9 GB） |
| 屏幕 | 1080×2640 @480dpi；折叠态 `app_accessible=false` |
| 系统角色 | 默认助手 = Google `googlequicksearchbox`；无障碍由用户手动授权 |
| 电脑 | Windows 11 + git-bash，adb 37.0.1；无线调试走官方 TLS + mDNS |
| 网络 | 手机与电脑同 Wi-Fi（局域网 IP 见各报告）；另有 cloudflared 公网隧道用于蜂窝测试 |
| 模型 | 在线：阿里云百炼 `qwen3.8-flash`（OpenAI 兼容端点）；离线：sherpa-onnx paraformer-zh-small |

**日志位置说明**：原始日志、截图、投屏视频都在本机 `C:\Users\<user>\AndroidAgent\logs\`
与手机端 `inspection-trace/`，**不随仓库分发**（含设备画面与个人信息）。
仓库只保存结论与可复现命令；需要原始证据时在真机上按命令重跑。

---

## 1. 阶段一：环境勘察

| 项 | 结果 | 证据 |
|---|---|---|
| ADB 连接/型号/系统/内存/存储读取 | ✅ | `logs/phase1-20261030-193658.txt`、`logs/phase1b-20261030-193723.txt`；报告 `docs/phase1-device-report.md` |
| 折叠态枚举（`dumpsys device_state`） | ✅ 合盖 `app_accessible=false` | 同上 |
| 默认助手角色可替换性 | ✅ 可替换（`fallback_enabled=true`），但后续实测角色授予被拒（见 §3） | 同上 |

## 2. 阶段二：基础 Agent（在线，纯无障碍感知）

| 用例 | 结果 | 硬数据 |
|---|---|---|
| B：设置→存储→读剩余容量 | ✅ 通过 | 6 轮 / 6 次工具调用 / 0 失败 / 51.2 s / `GoalAchieved`；答复 34.2 GB 已用、剩余约 477.8 GB，与 `df` 的 445 GiB 一致 |
| A：Chrome 搜索 GitHub 并进入指定项目页 | ✅ 通过 | `outcome=GOAL_ACHIEVED`，读回 stars/forks/issues 实值 |
| C：页面加载失败→刷新→再识别 | ✅ 通过 | 准确指认 `ERR_UNSAFE_PORT` 并说明刷新无法修复 |
| 顺带验证 | ✅ | `shell`(toybox)、`scratchpad`、`remember_experience`、`ask_user`（goal 被截断时正确追问） |

延迟实测（同端点）：`qwen3.8-max` 111.8 s/轮（不可用）；`qwen3.7-flash` 7.0 s；
**`qwen3.8-flash` 3.9 s/轮**（选用，多步任务整体 50–90 s）。

## 3. 阶段三：语音

| 用例 | 结果 | 说明 |
|---|---|---|
| `fake_text` 注入全链路（语音入口→Agent→播报） | ✅ | `VoiceActivity dispatch=true` → `Task started` → `speaking outcome=GOAL_ACHIEVED chars=188` |
| ASR 机制（无人说话） | ✅ 链路通 | `onEndOfSpeech` → `#onResults empty` → `onError NO_SPEECH_DETECTED` → 提示"没有听到说话" |
| 真人口述识别率 | ⏳ **未验证** | 需本人在设备前说一句中文，机器侧无法代替 |
| 设为默认助手（`cmd role add-role-holder`） | ❌ 失败 | `RoleControllerServiceImpl: Package does not qualify for the role` |
| 设置列表里出现 ClosePaw | ❌ 不出现 | 「数字助理应用」列表中「其他」为空 |
| 强写 `settings put secure voice_interaction_service` | ❌ 系统崩溃，已回滚 | `VoiceInteractionManagerService$getDefaultAssistantFlagsFromInfo()` NPE（系统进程） |
| 锁屏下打开语音 Activity | ❌ 被锁屏挡住 | 普通 Activity 无法在锁屏上显示 |

## 4. 阶段四：无障碍稳定性与通用能力

### 4.1 无障碍掉绑根因（单变量实验）

| 操作 | `enabled_accessibility_services` |
|---|---|
| 基线（已绑定） | `ai.closepaw/.app.AgentService` |
| `am force-stop ai.closepaw` | **null（注册被系统删除）** |
| `adb install -r`（不 force-stop） | 保持，自动重绑 |

修复后完整循环实测（`scripts/a11y_cycle_test.sh`）：Wi-Fi 关/开各一次、锁屏、App 切换，
全程 `setting=` 不变，`wifi_restored=1`，绑定**零丢失**。

### 4.2 通用能力（逐条真机）

| 指令 | 结果 | 复核方式 |
|---|---|---|
| 打开计算器 | ✅ SUCCESS verified | 前台包名 `com.sec.android.app.popupcalculator` |
| 打开时钟 | ✅ SUCCESS verified | 前台包名 `clockpackage` |
| 设一个四分钟的倒计时 | ✅ SUCCESS verified | 时钟页出现「N 个定时器正在运行 / 4 分钟」 |
| 查看闹钟 | ✅ SUCCESS verified | `AlarmManager.nextAlarmClock` = 10-31 07:30 |
| 打开蓝牙设置页 / Wi-Fi 设置页 | ✅ SUCCESS verified | action + 前台 `com.android.settings` |
| **设置九点二十的闹钟** | ❌ **未创建，verified=false** | `AlarmManager` 仍为 07:30（三星时钟停在编辑页，保存按钮节点未找到） |
| 锁屏下系统面板自动化 | ⚠️ 明确拒绝 | `SYSTEM_RESTRICTED (keyguard locked)`，如实播报 |

## 5. 离线 ASR / 本地小模型评测（PC 与真机隔离测试）

### 5.1 离线中文 ASR（30 条中文命令，PC 同音频，同一套 16 kHz WAV）

| 模型 | CER | 精确匹配 | 备注 |
|---|---|---|---|
| streaming-zipformer-zh-14M | 5.3% | 80.0% | 旧链路 |
| **paraformer-zh-small** | **1.3%** | **93.3%** | 采用（体积/延迟最优） |
| zipformer-ctc-zh-int8-2025 | 0.9% | 93.3% | 351 MB，过重 |
| sensevoice-small | 3.1% | 83.3% | 63 MB |

数据：`asr_eval/*.json`。设备端真机识别率**未系统评测**（仅验证链路通）。

### 5.2 本地小模型（15 条中文任务，llama.cpp b11960 / aarch64 / 4 线程 / temp=0 / max_tokens=240）

| 模型 | action+target | 关键语义通过 | JSON 解析 | 加载 s | 完成中位 s | RSS MiB |
|---|---|---|---|---|---|---|
| Qwen3-0.6B Q4_K_M | 6/15 | 1/15 | 14/15 | 4.78 | 1.13 | 1262 |
| Qwen3-1.7B Q4_K_M | 2/15 | 0/15 | 15/15 | 4.47 | 2.78 | 2623 |
| FunctionGemma-270m-it | 0/15 | 0/15 | 0/15 | 1.33 | 3.30 | 580 |

典型错误：0.6B「五分钟」输出 `seconds=5`、「暂停三分钟」`seconds=3000`；1.7B「恢复计时器」输出
`CREATE`；FunctionGemma 工具格式崩塌（重复 parameters、action 嵌进 parameters）。
**结论：三款均不得接入执行层**（`llm_eval/verification-report.txt`、`results.json`）。

### 5.3 Google AI Edge Mobile Actions（官方 270M 微调 + LiteRT-LM 0.18.1，隔离 APK）

| 组 | 结果 |
|---|---|
| 英文受控（官方 7 工具内） | 4/4 ✓（手电开/关、Wi-Fi 设置、地图显示北京） |
| 中文受控（官方 7 工具内） | 1/4（仅 Wi-Fi 设置 ✓） |
| 中文业务用例 15 条 | 0/15（11 条"无该工具"属预期，4 条误读） |
| 危险反向 | 0 次实际反向调用 —— 但这是"不工作"，不等于安全 |

数据：`mobile-actions-eval/official-results.jsonl`、`docs/mobile-actions-official-eval.md`。

## 6. 无线开发与救援

| 用例 | 结果 | 证据 |
|---|---|---|
| 拔 USB 后无线安装 235 MB APK | ✅ 14 s | `docs/wireless-acceptance.md` |
| 无线授权无障碍并生效 | ✅ | `dumpsys accessibility` Bound services |
| 纯无线跑完整 Agent 任务 | ✅ | `GOAL_ACHIEVED` + 播报 188 字 |
| 锁屏状态下 install/shell/logcat | ✅ | — |
| 重启后服务自动恢复 | ✅ | TLS 端口 45563（mDNS 发现），无障碍自动重绑 |
| 重启**未解锁**时中继心跳 + diagnostics | ✅ | `user_unlocked:false`、`direct_boot:true`、`relay_roundtrip_ms:83` |
| 蜂窝数据 + cloudflared 公网隧道 | ✅ | `network: cellular, validated: true`，status/start_agent/speak 均成功 |
| 解锁后能力恢复 | ✅ | `launch_installed_app/speak/accessibility_enabled` 全部转 true |
| 电脑整机重启后重建连接 | ⏳ 未做 | 会杀掉当前会话进程 |
| 换 Wi-Fi / 纯移动数据远程开发 | ⏳ 未做 | 需另一个 AP 与热点凭据 |

## 7. 投屏（scrcpy 5.0.1）

| 用例 | 结果 |
|---|---|
| 无线发现与连接 | ✅ `[samsung] Samsung SM-F731U1 (Android 17)` |
| 视频解码与真实画面 | ✅ D3D11VA，448×1088；录像 440×1080 / 458 帧 / 19.57 s，ffprobe 可解析 |
| 控制通道注册 | ✅ `dumpsys input` 出现 UHID 设备 `scrcpy` |
| 合成事件端到端注入 | ⏳ **未验证**（前台化被系统拒绝，需人工在投屏窗口点一下确认） |

## 8. 下次要补的验证

1. 真人口述：解锁 → 桌面「语音助手」→ 说中文，确认识别/回执/播报。
2. 唤醒词：前台服务常驻 + 息屏功耗与唤醒率（阶段 5）。
3. 三星闹钟创建的 UI 兜底按钮定位。
4. 投屏控制注入的人工确认（10 秒）。


## 解锁后续验收（2026-10-31，真实 debug 文字注入）

需要复审。阶段1未全部通过；本轮没有重建、重新安装或修改生产代码。

- 持有同一 `.git/semantic-gate-writer.lock`，以 O_CREAT|O_EXCL 取得；工作区初始干净，pull --ff-only 已最新。已读取 PR4 comments/reviews/inline 与 Issue3，最新方案提问尚无新增审查回复；第四轮仅有限源码复审，不是实机批准。
- 开始/结束当前用户 deviceLocked=0；前后无障碍 Bound services 有 ai.closepaw/.app.AgentService，未改注册、角色或其他高风险设置。
- 重新 pm path 并 pull 已安装 APK：SHA256 `98f06f77df538244db3c57e1a8a65a928b76196969d89d5d2ce273a5dbdf49e3`，与上轮源码 `2ff514ff0b333f9dafe6947685c059fff91cf475` 构建产物相同；git diff 2ff514f HEAD -- src 为空。哈希链依赖上轮已记录的构建，不把本轮说成新构建。
- 实际执行6条文字入口：闹钟查询、计时器查询、五分钟 parse_only、两家商店、微信扫一扫。日志 voice=false，均结束；不是真人语音/物理侧键验收。未派发在线 Agent、未调用付费API、未delegate、未启用PR5/阶段2/3。

| 项目 | 结论 | 脱敏实测与边界 |
|---|---|---|
| 取消可证明不存在目标不得创建 | 未测 | 既存闹钟列表非空且可滚动；没有把局部不可见当目标不存在。未建立完整清单/唯一不存在证据，不执行取消。 |
| 查询不修改 | 失败（业务查询）；不修改仅部分证据 | QUERY/ALARM SUCCESS verified=false，detail=next=null visible=[] explicitEmpty=false readable=false；明确说无法读取列表。QUERY/TIMER DEVICE_UNAVAILABLE，ui unavailable。外部只读打开闹钟页后同视口时间/开关/content-desc逐字段前后一致，但不是全列表完整性证明，不能计整项通过。 |
| 五分钟真实工具参数300秒 | 未测（解析子项通过） | 设备 PARSEONLY CREATE/TIMER{seconds=300} ok=true；未调用创建工具，不能把解析300秒当实际工具参数或倒计时成功。 |
| 计时器唯一对象绑定/歧义安全拒绝 | 未测 | 当前定时器查询UI不可读，没有本轮安全可定位/可清理测试对象；未对个人对象做取消试验。 |
| Google Play/Galaxy Store身份 | 通过（文字入口） | 两次 runner SUCCESS verified=true；分别 pkg=com.android.vending foreground=true、pkg=com.sec.android.app.samsungapps foreground=true。同期 dumpsys window windows保存对应包窗口记录。无下载、购买或登录操作。 |
| 二级页面不能只首页就报成功 | 通过（未到达时如实失败） | OPEN/APP_FEATURE name=微信 feature=扫一扫；DEVICE_UNAVAILABLE verified=false，control-not-found；反馈“打开了微信，但界面上没有找到扫一扫”。只证明不虚报，不证明扫一扫可用。 |
| 创建undo安全拒绝 | 未测 | 没有本轮创建的唯一对象与安全清理路径，不创建、不撤销；既有JVM拒绝测试不替代实机。 |

按完整项目计：通过2、失败1、未测4；“通过”仅限所述文字验收边界。没有制造危险对象换通过。真人语音全部未测。当前阻塞不是锁屏/未绑定，而是时钟业务UI不可读及缺少安全对象身份/清理证据。未作生产修复，因此无新TDD/构建/全量测试记录；历史2138 tests / 6 failed / 8 skipped仍有效，不宣称本轮全绿。

原始日志、窗口内容、APK与XML只存gitignored logs/stage1-resume-*、logs/stage1-alarm-{baseline,after}.xml，未提交个人对象时间、名称或截图。uiautomator dump仅在无任务执行时作前后只读基线，未在Agent任务中抢连接。未force-stop、清数据、改助手角色、关Wi-Fi或绕过锁屏。后续需在安全测试对象方案明确后补4项未测，并诊断时钟读取缺口；不能合并或据此启动后续阶段。

## 第⑦轮语义闸门回归（2026-10-31，JVM + 真机只读）

依据 PR #4 复审 `6539848799`（head `3253319`）三项遗留；本轮提交 `54e4b77` / `f122920` / `525ca49`。

### JVM（真实执行）

| 项目 | 结果 | 证据 |
|---|---|---|
| `ClockWindowGateTest`(8) | 通过 | 上一提交上 4 项红（外来窗口绑定计时器/闹钟、节点属他包、截断下「添加定时器」、无开关证据的时钟页报数），修后全绿 |
| `ClockAlarmPageEvidenceTest`(6) | 通过 | `checkableCount>0` 才枚举闹钟；明确空态可判空；窗口被抢 → 重开+重选 tab 再点；无法确认窗口零点击 |
| `TabSelectedStateTest`(8) | 通过 | 含精确标签优先（先红后绿） |
| 定向 `voice.semantics.*` + `voice.tools.*` | **101 tests / 0 failed** | `--tests` 两包 |
| 全量 `:app:testDebugUnitTest` | **2194 tests / 6 failed / 8 skipped / 0 errors** | 失败=既知 `AgentSkillCatalogTest`(1)+`MemoryStoreTest`(5)，与基线一致，**不是全绿** |

### 真机（SM-F731U1 / Clock 12.5.60.27，APK SHA256 `c42d7222…d00347a`）

| 用例 | 结果 | 实测 |
|---|---|---|
| `install -r` + 无障碍 | 通过 | `Success`；`dumpsys accessibility` 仍 `id=ai.closepaw/.app.AgentService, isDefault=true` |
| 助手角色未变 | 通过 | `settings get secure voice_interaction_service` = `ai.closepaw/.voice.ClosepawVoiceInteractionService`（安装前后一致） |
| ALARM QUERY ×3（只读） | 2 次通过 / 1 次如实拒读 | `verified=true visible=[6:00, 7:30, 8:00, 9:20, 10:00, 11:00] checkables=6 tab=clicked nodes=67 rootPkg=clock`；拒读那次 `verified=false visible=[] tab=clicked-not-selected` |
| TIMER QUERY 空态 | 通过 | `verified=true tab=clicked nodes=44`（0 个在运行）；另有 1 次 `tab=label-not-found window=not-clock` 拒绝 |
| 自建对象（2 分钟倒计时）查询 | 通过 | `verified=true count=1 nodes=27 rootPkg=clock` |
| 自建对象取消 | 失败闭合（未点击） | `BLOCKED duration=120 candidates=0 complete=true`；卡片无「初始时长」证据，安全拒绝 |
| 测试对象清理 | 完成 | 计时器到期后用 `ACTION_DISMISS_TIMER` 结束提示；终态查询 `verified=true nodes=44`（0 个） |
| 未触碰 | — | 未点击个人闹钟/倒计时开关、未 force-stop、未清数据、未关 Wi-Fi、未改助手角色 |

边界：本轮只验证“读”与“tab 切换”与自建可清理对象；`closeAlarm` / `cancelTimer` 在真机仍不可用（BLOCKED）。到达判定仍有残余不确定性（`tab=clicked` 表示点击已投递但选中态未观测为 true）。矩阵：通过 4 / 失败 0 / 未测 3；阶段 1 未整体通过，PR #4 不合并。

## 第⑳轮语义闸门回归（2026-10-31，JVM 合成无障碍树，未实机）

依据 PR #4 第⑱轮复审评论 `6544037799` 要求补的「同包正文两个时钟标签、无导航栏」负向夹具，另修掉夹具暴露的真实误点击。

### 源码分析结论（非实机）

旧 `UiAutomator.navControl/isNavBar` 对内容页（`闹钟` 段标题 + 可点 `定时器` 设置行）返回了 `NavControl`，`ClockControl.clickClockTab("定时器")` 返回 **true** 并对该设置行投递了 `ACTION_CLICK`。查询侧只因 `selectedTabs` 读回为空而未报成功，但**点击已经发生**——这是把内容行当导航 tab 的误操作路径。

### TDD（真实执行）

| 阶段 | 结果 | 证据 |
|---|---|---|
| 先写测试、旧实现跑 | **RED 4 / 7 failed** | `expected null but was true`（`clickClockTab`）、`NavControl(control=…)` 非空（滚动列表内 tab 条）、入口层 detail `tab=clicked`（应 `tab=click-failed-label-only`） |
| 修 `isNavBar` 后 | **7 / 7 通过** | 新增 `ClockContentLabelGateTest` |
| 定向 `voice.semantics.*` + `voice.tools.*`（`--rerun-tasks`） | **151 tests / 0 failed / 0 errors / 0 skipped** | `logs/pr4-review20-targeted.txt`（本地） |
| 全量 `:app:testDebugUnitTest`（`--rerun-tasks`） | **2241 tests / 6 failed / 8 skipped / 0 errors，exit=1** | 不是全绿：`AgentSkillCatalogTest`(1) + `MemoryStoreTest`(5)，与本轮前基线同名同类，未改未跳过 |

新增/变更规则（`UiAutomator`）：导航栏不得位于滚动容器内；每个 peer 标签必须各自拥有栏内点击目标且不得共用；标签→栏的从属关系用父链（`depthBelow`）证明，不再用子树 BFS 猜。

旧夹具冲突：16 项既有测试的合成 tab 条把标签写成不可点（relaxed mock 默认 `isClickable=false`），按真实 UI 补 `isClickable=true`；**断言未删、未弱化**。

### 边界

- 全部为 JVM 合成树契约，不是三星真实 UI；`isNavBar` 仍是**启发式**（窗口根/规模/深度/滚动/点击单元五项约束），不是已证明的导航身份。
- 真机：本 SHA **未构建、未安装、未实测**；设备矩阵维持历史 4 通过 / 0 失败 / 3 未测。实机需用户**重新授权**。
- `closeAlarm` 仍因缺全列表枚举/业务对象身份 BLOCKED，是安全拒绝，不是功能完成。

## 第㉑轮语义闸门回归（2026-10-31，JVM 合成无障碍树，未实机）

依据 PR #4 第⑲轮复审 `6544694121` / `6544641476`：`navControl` 只看全树第一个精确同名节点；`selectedState` 在栏内无选中时扫描整棵窗口树。两项均已改，第三项（小型正文双标签卡片）未做，原因见下。

### TDD（真实执行，`--offline --rerun-tasks`）

| 阶段 | 结果 | 证据 |
|---|---|---|
| 新测试在旧实现上 | **RED 4 / 5 failed** | `navControl` 期望非空实得 null（真 tab 条被正文同名节点抢占）；两条合格 tab 条未判歧义（返回 `NavControl`）；`selectedState` 期望 null 实得 false（两处） |
| 修后同组 | **5 / 5 通过** | 新增 `ClockNavCandidateTest` |
| 定向 `voice.semantics.*` + `voice.tools.*` | **156 tests / 0 failed / 0 errors / 0 skipped**，exit=0 | `logs/pr4-review20-targeted.txt`（本地） |
| 全量 `:app:testDebugUnitTest` | **2246 tests / 6 failed / 0 errors / 8 skipped**，exit=1 | 不是全绿：`AgentSkillCatalogTest > entry filePath points to SKILL md` + `MemoryStoreTest` 5 项，与前轮同类，未改未跳过 |

### 规则变化

- `navControl`：收集全部精确同名候选（≤8），逐个 `barFor` 向上验证；**不同容器都合格 → 歧义 → null**。同一容器内的重复同名节点仍视为同一 tab。
- `selectedState`：未在控件→容器链路上看到 `selected=true` 时，只在**该 tab 条子树**内查找选中上报（含容器自身）；栏内无上报 → null（无法确认），有上报 → false（不是这个 tab 选中）。正文中无关的 `isSelected` 不再产生 false。

### 未做与边界

- 小型正文双标签卡片（≤40 节点、同深度、各自可点）仍可通过结构规则：离线无真实 resource-id / role 证据，继续叠结构条件只会让真 tab 条更容易被拒。需实机采集，属**需授权实机**项。
- `isNavBar` 仍是启发式（窗口根/规模/深度/滚动/点击单元/候选歧义），不是已证明的导航身份。
- 真机：本 SHA 未构建、未安装、未实测；设备矩阵维持历史 4 通过 / 0 失败 / 3 未测。
- `closeAlarm` 仍因缺全列表枚举/业务对象身份 BLOCKED，是安全拒绝，不是功能完成。

## 第㉒轮语义闸门回归：选中态在查询/绑定入口的联动矩阵（2026-10-31，JVM 合成无障碍树，未实机）

依据 PR #4 复审 `6544694121` 第 3 条（`selectedState` 收窄到 tab 条后是否在出口处放宽准入）。本轮不改选中判定本身，只把判定结果在三个出口的行为钉成回归，并拆开 `null`/`false` 的诊断。

### TDD（真实执行，`--offline --rerun-tasks`）

| 阶段 | 结果 | 证据 |
|---|---|---|
| 新 `ClockSelectionEntryMatrixTest` 在上一 head `2cda031` 上 | **RED 3 / 11 failed** | `selectedState` 期望 null 实得 false（正文选中卡片被整窗扫描当证据）；`cancelTimer` 绑定门 `why=` 期望 `binding-tab-unreported`/`binding-tab-not-selected`，实得同一 `binding-tab-unproven` |
| 修后同组 | **11 / 11 通过** | 8 条为既有契约的定点回归（这些在改动前已成立，不计入 RED） |
| 定向 `voice.semantics.*` + `voice.tools.*` | **167 tests / 0 failed / 0 errors / 0 skipped** | BUILD SUCCESSFUL |
| 全量 `:app:testDebugUnitTest` | **2257 tests / 6 failed / 0 errors / 8 skipped**，exit=1 | 不是全绿：`AgentSkillCatalogTest > entry filePath points to SKILL md` + `MemoryStoreTest` 5 项，与前几轮同类，未改未跳过；日志 `logs/cron-full-r21b.txt`（本地） |

### 矩阵（每格都是实际断言的用例）

| 出口 | 栏内无任何选中上报（null） | 栏内另一 tab 选中（false） | 本 tab 选中（true，控制组） |
|---|---|---|---|
| `readTree.selectedTabs` | `null`（正文 `isSelected` 不作证据） | `false` | `true` |
| `queryTimers` / `queryAlarms` / `closeAlarm` | `DEVICE_UNAVAILABLE`，`verified != true`，零点击 | 拒绝（导航期即 `clock not reachable`），零点击 | 放行（`count=2` / 正常查询） |
| `cancelTimer` 绑定门 `verifiedClockWindow` | `DEVICE_UNAVAILABLE`，`why=binding-tab-unreported`，取消节点 ACTION_CLICK = 0 | 导航期拒绝；若绑定期窗口变化后为 `false`，则 `why=binding-tab-not-selected`，ACTION_CLICK = 0 | 唯一卡片绑定成功，`verified=false`（无业务回读） |

### 边界

- 全部为 JVM 合成树契约，不是三星真实 UI；`isNavBar` 仍是启发式，真实选中态是否被 One UI 暴露给无障碍**未验证**。
- 真机：本 SHA 未构建、未安装、未实测；设备矩阵维持历史 4 通过 / 0 失败 / 3 未测，实机需用户**重新授权**。
- `closeAlarm` 仍因缺全列表枚举/业务对象身份 BLOCKED，是安全拒绝，不是功能完成。

## 第㉔轮：`expectedState` 终态校验契约（离线源码 + JVM，未实机）

依据 PR #4 复审 `6545877923`（第⑳轮）末尾「`expectedState` 通用业务终态校验…未完成」一条。本轮新增 `voice/semantics/TerminalState.kt`：变更类任务的结果在 `SemanticRunner` 返回边界按证据分级 —— `verified=true` → CONFIRMED，`verified=null` → UNPROVEN（请求已发出、无回读，仍算成功但不得说成完成），`verified=false` → CONTRADICTED（回读与期望不一致，降级为 `EXECUTION_ERROR`）；查询与拒绝类结果不改写。同时新增 `ToolResult.sentWithoutProof()`（`SUCCESS + verified=null`），替换 11 处「只投递 Intent / 只点控件」却标记 `verified=false` 的调用点。

### TDD（真实执行，`--offline --rerun-tasks`）

| 阶段 | 结果 | 证据 |
|---|---|---|
| 新 `TerminalStateGateTest`(11) 先写、实现未写 | **RED = 编译失败** | `Unresolved reference 'TerminalState' / 'sentWithoutProof'`；是弱 RED，如实记录（不是断言失败） |
| 实现 + `SemanticRunner` 接线 + 11 处替换后首跑 | **183 tests / 6 failed**（exit=1） | 6 条全是旧断言 `verified isFalse` 遇到新语义 `null`：`ClockAlarmPageEvidenceTest` 1、`ClockSelectionEntryMatrixTest` 1、`ClockUndoSafetyTest` 1、`IntentCreationVerificationTest` 3 |
| 6 条旧断言改为 `isNull()` 并补 `status == SUCCESS` 后 | **183 tests / 0 failed / 0 errors / 0 skipped**（exit=0） | 无断言被删除；改的是 `false`（回读后未变）与 `null`（未回读）的语义区分 |
| 全量 `:app:testDebugUnitTest` | **2273 tests / 6 failed / 0 errors / 8 skipped**（exit=1） | **不是全绿**：`AgentSkillCatalogTest > entry filePath points to SKILL md` 1、`MemoryStoreTest` 5，与基线同类，未改未跳过 |
| 基线（本轮改动前，同一套定向过滤） | **172 tests / 0 failed** | 本轮 +11 条新测试 |

日志 `src/closepaw/logs/pr4-r24-{targeted-baseline,terminal-red,targeted-green,targeted-green2,full}.txt` 只留本机，未入库。

### 边界

- 这是**证据分级 + 返回边界的强制判定**，不是业务状态回读本身；`CONTRADICTED → EXECUTION_ERROR` 分支目前只有 JVM 夹具覆盖（现无生产者会在回读后报 `false`），真机从未触发。
- 创建/取消后回读对象存在性与计数、在线 ToolRouter 同门禁（阶段 2）、正文双标签卡片身份仍未完成；后者需实机采集脱敏 UI 树。
- 本 SHA **未构建、未安装、未实机**；设备矩阵维持历史 4 通过 / 0 失败 / 3 未测，新一轮实机需用户**重新授权**。

## 第㉟轮：手电回调归属 + 时长短语整段解析（离线源码 + JVM，未实机）

依据 PR #4 复审 `6550162379`（HEAD `1a77501`）的 2 条 P1。命令：仓库根 `source env.sh` → `cd src/closepaw` → `./gradlew --offline :app:testDebugUnitTest --rerun-tasks --console=plain`。

### 被测缺陷（源码分析）

1. **手电**：`onTorchModeChanged` 无条件写 `observed`；`set()` 拿到 `confirmed` 却未传给 `outcome`；`outcome` 只要 `observed == on` 即 `SUCCESS + verified=true + ObservedState`。注册回调的初始态因此可被当成本次请求的结果，`onTorchModeUnavailable` 之后也不影响结论。
2. **时长**：`半分钟` = 1800 s（`半` 在分钟位被当成半小时），`一个半小时` = 1800 s（小时正则吃掉尾部 `半小时`），`1.5小时` = 18000 s（`\d+` 从 `5` 起匹配）。三值都会成为 `AlarmClock.EXTRA_LENGTH`，且 `IntentGate` 用同一函数复核，因此闸门放行。

### 修复

| 文件 | 改动 |
|---|---|
| `tools/TorchControl.kt` | 新增 `RequestTracker`（baseline / postState / unavailable / requested / confirmed）；`set()` 先有界等 baseline（1200 ms）再发请求，`confirmed` 与 `tracker` 状态一起交给 `outcome` |
| `tools/TorchControl.kt` | `outcome(on, cameraId, observed, unavailable, confirmed, baseline)`：`unavailable` 优先于成功；成功仅当 `confirmed && observed == on`；否则 `TIMEOUT` + `verified=null` + 无 `observed` |
| `tools/ChineseTime.kt` | `durationSeconds` 重写为按整段短语消费：复合形优先、数字支持小数、已消费片段移除、不可读数词整句 `null` |

### TDD

| 阶段 | 结果 | 证据 |
|---|---|---|
| 新测试先写 + 仅加参数/骨架（行为未改） | **RED 31 tests / 12 failed / 0 errors**，全部断言失败 | `logs/pr4-round35-red.txt`；4/5 `ChineseTimePhraseUnitTest`、3/3 `TimerCompoundDurationArgumentTest`、5/9 `TorchAttributionTest`；控制组 `ChineseTimeDurationTest` 4/0、`SwitchReadbackEvidenceTest` 10/0 |
| 实现后同组 | **34 / 34 GREEN，exit=0** | `logs/pr4-round35-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **282 / 0 failed / 0 errors / 0 skipped**，exit=0（上轮 265，+17） | `logs/pr4-round35-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2372 / 6 failed / 0 errors / 8 skipped**，**exit=1，不是全绿** | `logs/pr4-round35-full.txt`；失败仍为 `AgentSkillCatalogTest > entry filePath points to SKILL md` 1 + `MemoryStoreTest` 5 |

### 边界

- 手电真实回调时序、`BASELINE_TIMEOUT_MS` 是否够用、三星相机是否在注册后立刻推初始事件：**均未实机**。JVM 只能覆盖归属判定，`set()` 的 CameraManager 布线不可在 JVM 跑（本仓无 Robolectric）。
- 时长修复只证明「说法 → 工具入参」，不证明三星时钟接受 4200 s / 30 s。
- 全量 6 项失败是历史既知项，本轮未改未跳过，也未重跑旧 SHA 基线。
- 本 SHA 未构建、未安装 APK、未操作手机；设备矩阵维持 4 通过 / 0 失败 / 3 未测。

