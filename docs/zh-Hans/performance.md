> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../performance.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# Home Assistant 墙面面板性能调优

本指南说明为何廉价的 Android 墙面面板可能反应迟缓、滚动时卡顿、出现空白或停止更新，即使同一仪表盘在手机或桌面设备上运行良好。先从 Home Assistant 发送给面板的工作负载着手，再测量仪表盘和硬件的其余限制，而不要直接认定必须更换面板。

> [!TIP]
> 常见原因之一是到达仪表盘的 Home Assistant 实体状态和更新数量。大型安装可能会让低性能面板处理数千个它从不显示的实体。使用 ha-paneld 的内置渲染器时，首先应尝试实体筛选。

## 进行任何更改前先测量

在 `http://<panel-ip>:8888/` 打开面板网页，或使用 Home Assistant 设备页面上的**访问**链接。每次更改前后，各记录一段时长相近的同一仪表盘视图，使比较具有意义。

- **仪表盘响应速度** — 实际交互延迟、最慢交互的输入/处理程序/呈现明细、主线程阻塞、可交互时间以及意外的渲染器重新加载。
- **Home Assistant 状态流** — 每秒状态更新数、未压缩 JSON 有效负载速率、初始加载大小、主线程让出执行权所需时间，以及滚动一小时内贡献最大的三个实体，并同时按更新速率和有效负载量排名。
- **性能**和**资源占用最高的进程** — CPU、GPU、RAM、时钟速度、温度以及最繁忙的进程。
- **摄像头视频流**（仅适用于配置文件声明了摄像头的面板）：保持会话打开的订阅者、正在使用的编码器、请求帧率与实际提供帧率的对比，以及实际提供比特率与编码器比特率上限的对比。如果实际速率远低于请求速率，系统会如实报告，但不会附加原因：昏暗房间中的采集节奏、编码路径以及视频硬件争用都可能是原因，而卡片不会在这些原因之间猜测。请求值本身绝不会被悄然降低。当会话无法为视频流提供其请求的速率时，卡片会并列显示请求速率和分配给编码器的速率，而不会移动目标来与实际速率相符。采集速率由打开摄像头的一方设定，因此，如果视频流加入了由快照打开的会话，则会按已配置的默认值进行编码。卡片特意不提供 CPU 数值，因为硬件编码不仅在应用中运行，还会使用编解码器硬件和合成器，任何单个进程都无法代表摄像头的总开销。请从性能和资源占用最高的进程中查看面板的整体负载。
- **实体页面** — 内置渲染器当前订阅的内容、自动学习发现的内容，以及仍需决定的仪表盘规则。
- **诊断信息**（`/diag`）— 当原因仍不明确时，可复制并粘贴到错误报告中的报告。

内置渲染器直接收集这些浏览器测量数据，无需 root 或 WebView 调试。Companion 应用仍是一种兼容模式：当 root 或辅助程序可用时，ha-paneld 可以显示渲染器 CPU 和 Android 渲染代理指标，但无法声称这些数据代表实际的 Companion 交互延迟。

状态事件的主线程数值从相关 Home Assistant 消息被分派时开始，到所有处理程序和微任务完成后浏览器让出执行权时结束。它包含 ha-paneld 的少量观察器开销以及该消息任务中的其他所有工作，因此特意不将其标记为仅限 Home Assistant 的处理时间。有效负载速率是前端处理的未压缩应用 JSON，而不是经过压缩的网络流量。

使用对齐图表查找相关性。交互峰值若与高状态流量和状态事件占用同时出现，则表明问题可能源于大量涌入的状态数据。数据流较少但处理程序耗时较长，则表明问题可能源于仪表盘 JavaScript。呈现延迟和长渲染帧表明问题可能源于布局、动画、摄像头或媒体工作。渲染器反复重新加载表明可能存在内存或渲染器稳定性问题。应结合这些测量数据进行判断；单次 CPU 快照本身无法确定原因。

名为**可能原因**的行会自动应用这些保守规则，并报告其置信度。当证据不足以支持某个原因时，它会有意指出没有明确的主要原因。

### 带外已筛选与未筛选 A/B 对比

如果未筛选的仪表盘使自身 WebView 过载，页面内的渲染数值可能停止变化。主机端收集器会改为轮询由 ha-paneld 服务管理的性能 API，因此仍可观察整个面板的 CPU；在 root 或辅助程序可用时，也仍可使用原生 Chromium 渲染器线程探针。它还会记录最后一批浏览器遥测数据的存在时长；超过 15 秒的值会报告为停滞，而不会悄然将旧的浏览器样本视为当前样本。

The collector is read-only with respect to user configuration. It never enables, disables or rewrites the entity filter; the first binding request may create one private installation key in ha-paneld's internal state. Select the same dashboard view and workload for both arms, change the filter through the panel UI, wait for the dashboard reload, then run one command from a repository checkout for each state:

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect filtered --label filtered --output filtered.json
```

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect unfiltered --label unfiltered --pair-with filtered.json --output unfiltered.json
```

比较这两个有界的机器可读结果：

```bash
python3 scripts/measure-dashboard-performance.py compare filtered.json unfiltered.json --output comparison.json
```

Each arm defaults to three minutes with a 30-second warm-up and 10-second polling. `--pair-with` reuses an opaque comparison id from the first result, allowing the comparison to reject a different physical panel, configured Home Assistant/dashboard target or measurement-relevant setting without recording those private values. It cannot detect a different view selected manually within the same configured dashboard, so keep the visible view and workload unchanged. The collector rejects the arm if the expected mode is not active, the filter revision or renderer generation changes, the build/configuration changes during collection, filtering falls back, or it retains fewer than three samples, fewer than three whole-panel CPU samples or fewer than three native renderer-main CPU samples. It still writes the invalid result and the exact validation reasons. Existing output files are never overwritten.

The JSON intentionally omits the panel URL and id, Home Assistant URL and dashboard path, entity ids, process names and filter hash. The panel computes the opaque fingerprints with a private random 256-bit installation key that never leaves the panel; fingerprints are unique to that comparison pair, so separate measurements cannot be correlated through them or used to guess room-style panel names. A panel that cannot persist this key refuses the measurement binding. The unfiltered entity count is the last synchronized catalog-backed count, not a live count recovered from the overloaded WebView; it is reported as unavailable when the catalog is empty. Network and browser traffic counters are normalized to their actual observed intervals, so a tolerated missed poll cannot skew the comparison. Browser-derived state/render timing and browser traffic rates are omitted from summaries when stale, while service-side CPU, memory, network and renderer-main values continue to be collected.

### 可选的远程 WebView 调试

常规的内置性能卡片不需要 DevTools。仅当需要进行更深入的源代码级检查时，才使用**远程 WebView 调试**卡片。

For the Companion app, first enable **Companion → Settings → Troubleshooting → WebView remote debugging**, then relaunch the dashboard. This setting is easy to miss: without it, the relay cannot discover the Companion WebView. The LAN relay itself also requires root.

### 排除旧版原厂 NSPanel Pro 的 Zigbee 看门狗缺陷

Legacy stock firmware containing a recursive `export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}` assignment can make the vendor's `guard_process.sh` the performance problem itself. A [community investigation](https://github.com/maxlyth/ha-paneld/issues/34) confirmed the defect on an NSPanel Pro 120 running stock 3.8.0 and reported the condition across all 16 panels in that fleet. The script prepends its directory every five seconds; after roughly ten hours in that setup the environment string crosses Linux's per-string execution limit, external commands start failing with `E2BIG`, `sleep` stops delaying the loop and the watchdog can pin one CPU core. At that point it can also fail to restart a dead `zgateway`, leaving Zigbee unavailable. Broader exposure across legacy 1.x–3.x firmware is plausible where the same line exists, but has not been independently verified by the ha-paneld project.

当 ha-paneld 报告原本处于空闲状态的原厂 NSPanel Pro 出现周期性系统负载时，请查找以下模式：

- `guard_process.sh` stays near 100% of one CPU core and its process size grows far above the reported healthy value of about 9 MB;
- `zgateway` is absent or no longer recovers; and
- rebooting helps, but the load returns around ten hours later in the reported stock setup.

A reboot only resets the accumulating environment temporarily. Issue #34 contains a reporter-provided root/ADB workaround, but the project has not yet independently validated that mutation and recovery sequence. Do not apply it unless the exact recursive assignment is present once in the vendor-native script. Any repair must first verify a non-empty backup and preserve ownership, mode and SELinux metadata. Abort before mutation on an unexpected match; after mutation, roll back if restart or verification fails, return `/vendor` to read-only, and verify both `zgateway` and its availability topic. A firmware update that rewrites `/vendor` removes the local patch; the defect returns only if the target firmware still contains the vulnerable assignment. Community inspection of firmware 4.0.12 and 4.6.0 did not find it.

This watchdog defect is distinct from a stable `zgateway` busy-looping against an unresponsive radio. ha-paneld's Zigbee health sensor distinguishes the guard and gateway CPU, join evidence and restart history. It warns about the exact recursive assignment but does not edit vendor scripts automatically. A configured, explicitly unjoined gateway that remains above 50% of one core for five minute-level samples after its 15-minute grace is automatically switched OFF and contained; a joined high-CPU router is warning-only.

## 修复方法（按影响大小排序）

### 1. 筛选内置渲染器的实体订阅

Home Assistant's frontend normally subscribes to the state of every entity visible to the signed-in user. The panel must receive and process those states even when its dashboard uses only a small subset. ha-paneld's built-in renderer can add the dashboard's learned entity set to that native subscription, so Home Assistant filters the stream before serializing and sending it. The panel keeps its ordinary authenticated Home Assistant connection; no proxy or additional server is involved.

The automatic filter is opt-in and applies only to the built-in renderer:

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer (ha-paneld)**, then enable **Entity filtering**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and exercise controls, pop-ups and conditional content so runtime dependencies have a chance to be observed.
4. Review the current, suggested and excluded entities. Pin anything required indirectly by a custom card or template.
5. Resolve any entity-filter checks. Narrow a broad or dynamic dashboard rule where practical, or make an explicit choice while accepting the warning shown by the panel.
6. Apply the policy-selected set, let the dashboard reload and compare the same views using the performance cards on the Dashboard tab.

Automatic learning cannot prove every custom card or dynamic template dependency. A missing entity may leave a card stale or unavailable, so review the result on a non-critical panel first and keep filtering disabled until the candidate is credible. The previous subscription remains available as the safe rollback: turn off **Entity filtering** and reload the dashboard.

If old learned evidence or manual choices no longer describe the dashboard, use **Reset learned data** on the Entities page. The confirmed reset clears learned membership, pins/exclusions and ignored safety decisions, preserves the known-good active filter and starts a replacement scan. The filter therefore stays as the rollback boundary while the candidate is rebuilt; use the stronger API reset documented below only when the stored filter itself must also be removed.

Advanced testers can supply and inspect an exact list through the API. The UI workflow, manual API format, runtime status and rollback commands are documented in [The built-in dashboard renderer](built-in-renderer.md#实验性实体筛选器).

### 2. 精简仪表盘本身

- 将内容密集的仪表盘拆分成用途明确的视图，并避免挂载面板永远不需要的卡片。
- 如果自定义卡片持续播放动画、创建大型文档树或频繁执行 JavaScript，请优先使用内置卡片。
- Watch interaction processing, long animation frames and renderer reloads. If one view repeatedly dominates them, simplify it or use `button.<panel>_reload` as a temporary recovery path while finding the expensive card.
- 分别测试摄像头和图表卡片。即使实体筛选工作正常，其解码、历史记录查询和渲染开销仍可能占据主要部分。

### 3. 从源头减少不必要的更新

Entity filtering protects the panel from unrelated entities, but it does not make a required entity cheaper. If a dashboard really displays a power meter, BLE distance sensor, rapidly changing template or noisy diagnostic entity, reduce that source's update rate where the integration supports it. This can also reduce recorder and database work for the whole Home Assistant installation.

Useful controls include ESPHome throttling or delta filters, Zigbee reporting intervals, integration `scan_interval` settings and less frequent template updates. Confirm the change does not make an automation or history view less useful before applying it globally.

### 4. 使其余仪表盘内容与硬件相匹配

PX30 和 rk3566 面板可以很好地运行用途明确的仪表盘，但其单线程性能仍然有限，并且通常只有 2 GB RAM。实体筛选可消除不必要的状态处理工作，但无法消除超大摄像头视频流、复杂动画或超大历史记录图表的开销。请根据面板的逻辑显示尺寸进行设计，并测试开销最大的视图，而不要只根据主页选项卡作出判断。

## 检查清单

- [ ] The built-in renderer is selected where Assist voice control and native notifications are not required
- [ ] Automatic dashboard entity filtering is enabled, scanned, reviewed and explicitly applied
- [ ] Every dashboard tab, pop-up and conditional path has been exercised during learning
- [ ] Custom-card and template dependencies are pinned or otherwise accounted for
- [ ] Entity-filter checks have been resolved deliberately rather than ignored accidentally
- [ ] The same views have been compared before and after filtering using the performance cards on the Dashboard tab
- [ ] If the page itself stalls unfiltered, the two arms have been collected and validated with the out-of-band measurement script
- [ ] 已分别测试高负载卡片、摄像头流和图表
- [ ] Required high-frequency entities have been tuned at the source where appropriate
- [ ] If the panel uses a legacy stock NSPanel Pro Zigbee stack, its `guard_process.sh` has been checked
- [ ] A reload and filter-disable recovery path has been verified

## 内置筛选器所取代的方案

Before ha-paneld could filter its own subscription, one deployment used a dedicated dashboard-only Home Assistant instance fed through the `remote_homeassistant` integration. It reduced the panel-facing feed from about 3,410 entities and 7.3 updates per second to about 310 entities and 0.77 updates per second, making the dashboards usable. It also required a second Home Assistant installation, bridged entities, separate configuration, authentication, updates, backups, monitoring and another failure path.

The built-in filter addresses that panel-load problem inside ha-paneld, so the split-instance system is no longer needed or maintained in that deployment and is not recommended for built-in-renderer users. This comparison remains here to show the amount of infrastructure the integrated solution replaces. Users who must retain the Companion app or another renderer cannot use ha-paneld's filter on that renderer; source-side tuning still applies, while any external filtering arrangement remains outside ha-paneld's supported setup.

---

## 参考

### 大型 Home Assistant 安装为何会拖慢面板

Home Assistant 前端维护一个 WebSocket 订阅，其中包含用户可用实体的当前状态及后续更新。如果不限制实体集，渲染器接收的数据会远多于专用墙面仪表盘通常使用的数据。其 JavaScript 主线程必须解析消息、更新前端状态模型，并判断可见内容是否发生变化。

低成本面板尤其敏感，因为 JavaScript 执行、布局和绘制高度依赖单个渲染器线程。额外的 CPU 核心有助于处理其他工作，但无法消除这种延迟；随着 WebView 堆增长，有限的 RAM 会使垃圾回收造成的干扰越来越严重。

### 常见症状

| 症状 | 可能原因 |
| --- | --- |
| 点按响应延迟、导航缓慢或滚动卡顿 | 大量实体流、高负载卡片或两者共同导致渲染器主线程负载过重 |
| 某个仪表盘视图的表现远差于其他视图 | 开销大的卡片、摄像头解码、历史数据，或该视图中庞大的文档树 |
| 整个视图变为空白，但外层界面仍然存在 | WebView 堆内存压力或渲染器故障 |
| 仪表盘在数天内逐渐变差 | 堆增长、内存碎片，或不断累积工作的卡片 |
| 实体停止更新，随后其更新集中涌入 | WebSocket 中断、重新连接，或渲染器无法跟上 |
| 类似的面板表现不同 | 不同的仪表盘内容、实体订阅、WebView 版本、运行时长或热状态 |
| Legacy stock NSPanel Pro becomes janky around ten hours after boot and `guard_process.sh` uses one core | check for the recursive vendor Zigbee-watchdog assignment leading to `E2BIG` |

垃圾回收会定期暂停 JavaScript 以回收未使用的内存。接近堆上限时，这些暂停会变得更长且更频繁，即使 Android 进程本身尚未崩溃，也可能导致渲染无法获得足够的处理时间。
