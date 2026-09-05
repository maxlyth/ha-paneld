> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../built-in-renderer.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# 内置仪表盘渲染器

> [!NOTE]
> **实验性功能 (0.9)。** 内置渲染器是用于筛选仪表盘实体的集成路径。当面板需要多个 Home Assistant 服务器、Assist 语音控制或原生通知时，HA Companion app 仍受支持。

ha-paneld 可以在自己的 WebView 中显示 Home Assistant 仪表盘，而不是将其交给单独的仪表盘应用。这样，在应用重启后，面板能以更短的延迟返回仪表盘。面板在后台检查当前仪表盘列表时，可以重新打开 Home Assistant 上次针对同一服务器、账号和主仪表盘设置验证过的仪表盘。如果 Home Assistant 报告该仪表盘已被移除或账号默认设置已更改，面板会切换到当前选项。

仪表盘运行后，ha-paneld 可以检测停滞的连接、释放 WebView 累积的内存并遏制渲染器崩溃。内置连接还支持筛选仪表盘实体。面板仍是单应用设备，只需安装、更新和配置一个 APK。

## 启动与恢复

The renderer uses Home Assistant's documented `?external_auth=1` interface, which is the same interface used by the HA Companion app. ha-paneld can therefore tell when the dashboard has connected instead of treating the page as a black box.

- 应用重启后重新打开上次验证过的仪表盘，同时在后台刷新 Home Assistant 的仪表盘列表。仍会先运行简短的兼容性检查。记住的路由与 Home Assistant 服务器、账号和已配置的仪表盘绑定，而显式配置的仪表盘或仪表盘标签页始终具有最高优先级。
- 屏幕关闭时冻结页面，并在唤醒时恢复页面，夜间可节省约 70% 的渲染器 CPU 使用量。
- 重新加载已打开但始终未连接的仪表盘。反复失败后，重试间隔会逐渐延长，而且面板会显示清晰的 **正在重新连接 Home Assistant…** 画面，而不是浏览器错误页面。
- Automatically retries recoverable checks with increasing delays. A permanently rejected login stops the retry loop and shows Browser sign-in instructions. An unsupported Home Assistant version or incompatible WebView names the required update and waits for it.
- 屏幕关闭时，通过不可见的重新加载释放累积的内存。
- Contains and rate-limits renderer crashes. A page that continues to crash falls back to the admin launcher instead of restarting all night.
- When Home Assistant announces that it is stopping or goes offline through MQTT availability, the panel shows a native notice and clears it only after Home Assistant proves it is back.

You can pull down from the very top edge of the screen to refresh, or pull twice for a full reload. The renderer also supports an optional idle return to the Home dashboard, camera-stream autoplay and private-CA HTTPS using user-installed certificate authorities. **Hide Android system bars** provides an edge-to-edge dashboard; swipe from a screen edge to reveal the bars again. On panels using ha-paneld's software navigation bar, **Dashboard** brings the configured renderer to the foreground without reloading it. **Reload** remains a separate recovery action.

渲染器采用与 Home Assistant Companion app 相同的方式设置仪表盘尺寸，因此从 Companion 切换过来时会保留布局。 **缩放（%）** 可调整结果，其中 100% 与 Companion 的默认设置一致。渲染器会在 Home Assistant 侧边栏中添加一个 **应用设置** 条目，用于打开面板的配置页面。首次运行时，它会隐藏停靠的侧边栏，并在空闲时保持连接。之后你仍可以打开侧边栏或更改这些默认设置。单独的 **隐藏 Home Assistant 导航（原生）** 选项会在原生信息亭模式处于活动状态时要求前端移除其导航。

## 要求与兼容性

从 ha-paneld 0.9.6 开始，内置渲染器同时需要满足以下两项要求：

- **Home Assistant 2026.4.2 或更高版本**；以及
- 支持 Home Assistant 原生主机接口所用安全 WebMessage 侦听器的 Android System WebView。

大多数用户只需使用当前版本的 Android System WebView。ha-paneld 会在加载仪表盘之前检查所需的 WebView 功能，并验证 Home Assistant 的兼容性。

If the panel shows **Home Assistant upgrade required**, upgrade Home Assistant and select **Retry**. Nothing on the panel substitutes for that.

如果显示**此面板的网页查看器版本过旧**，屏幕会说明这个面板可以采取哪些措施，因为具体措施因型号和面板设置方式而异：

- **The panel can repair itself.** When a known-good Android System WebView is pinned in the panel profile and ha-paneld is permitted to install it, the screen offers **Update the web viewer**. Select it and the panel downloads and installs that version, then ha-paneld restarts once to use it. If the screen comes back afterwards, the pinned version did not resolve the fault and the manual routes below still apply.
- **The panel cannot, and the screen says why.** Once ha-paneld has confirmed that automatic repair is unavailable, it names one of three reasons, and the update has to be done by hand, after which you select **Retry**: a known-good version is pinned but ha-paneld is not permitted to install it; no known-good version is pinned for this panel; or the panel takes its Android System WebView from a store, which will replace it more safely than ha-paneld would. Reinstalling the same version repairs a damaged one.

How Android System WebView is updated by hand depends on the panel: some take it from Google Play, others only from a vendor firmware update or a manually installed build.

The built-in renderer does not fall back to the older, less isolated bridge. Another renderer may help when Home Assistant itself cannot be upgraded. The Companion app uses the same system WebView, so it cannot bypass an obsolete WebView on the panel.

## 启用

On a new or reset panel, open `http://<panel>:8888/setup` from a laptop or phone, or select **Set up** on the panel itself. The guided journey chooses the renderer, signs in to Home Assistant, selects the account default, a dashboard or a specific dashboard tab, and asks about the entity filter before the first dashboard load. Authorization happens in the administrator's browser, so credentials do not need to be typed on the panel.

对于现有面板，请打开面板的 `:8888` **设置**页面。在**Home Assistant 连接**下，输入 Home Assistant URL 并选择**浏览器登录**，然后选择**内置渲染器**作为仪表盘应用。

Existing rooted installations that already imported a signed-in Companion session remain supported as a compatibility path. New installations should use Browser sign-in.

若要从管理员计算机进行无人值守设置，请替换这条无需检出代码的命令中的示例面板地址和 Home Assistant 详细信息（请参阅[预配置](provisioning.md)）：

```bash
# First create an owner-only password file as shown in the linked provisioning guide.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt
```

The password never reaches the panel because the login happens on your machine. The panel holds a revocable refresh token. A long-lived access token works too: `--ha-token-file ha-token.txt` instead of `--ha-user/--ha-pass-file`. See [Provisioning and fleet updates](provisioning.md) for securely creating credential files and the trusted-LAN transport boundary. Literal `--ha-pass` and `--ha-token` values remain compatibility options, but expose the value in the original shell command and process list.

For automated provisioning, a token or username/password flow remains available as an advanced fallback. Interactive installations should use Browser sign-in.

## 仪表盘外观与 Android 锁定

高级设置提供三个相互独立的控件。每个控件影响不同的层：

| 设置选项 | 更改的内容 | 它**不**更改的内容 |
|---|---|---|
| **隐藏 Home Assistant 导航（原生）**（默认开启） | After Home Assistant connects, asks its native frontend to hide its navigation. Built-in renderer only. | Does not lock Android, hide Android system bars, or inject or modify dashboard CSS. If Home Assistant rejects or does not support the command, the dashboard is left unchanged. |
| **隐藏 Android 系统栏**（默认开启） | Hides Android's status and navigation bars for an edge-to-edge dashboard. Swipe from an edge to reveal them. Built-in renderer only. | Does not prevent someone leaving the app and does not hide Home Assistant's own menus/navigation. |
| **将 Android 锁定到仪表盘（实验性）**（默认关闭） | With root, hides Android system bars and returns to the selected dashboard within about three seconds when another app or Recents opens. This is a casual-use deterrent, not an adversarial security boundary. | Does not change the Home Assistant dashboard appearance. It has no effect without root. Reboot provides a 60-second unlocked recovery window, then the saved lock is reasserted. |

For a cleaner dashboard, start with **Hide Home Assistant navigation (native)** and/or **Hide Android system bars**. Enable **Lock Android to dashboard** only when discouraging casual escape from the app is required and you have tested the documented release routes: Configure, the Home Assistant switch, adb, seven rapid taps in the top-left corner, or the unlocked window after reboot.

## 实验性实体筛选器

> [!WARNING]
> This is an opt-in tester feature. Automatic learning cannot prove every custom-card or dynamic-template dependency, and an incomplete entity set can leave cards missing or stale. Review it on a non-critical panel first and keep the filter-disable rollback available.

The filter applies only to ha-paneld's built-in renderer. It changes the frontend's Home Assistant subscription, so Home Assistant filters the states before serializing and sending them to the panel. The Companion app and other dashboard applications are unaffected.

### 自动工作流程

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Entity filtering**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and use its controls, pop-ups and conditional content so ha-paneld can observe runtime dependencies.
4. Review the current, suggested and excluded lists. Pin entities used indirectly by custom cards or templates, and resolve any entity-filter checks shown above the tables.
5. Select **Apply policy set** when the candidate is ready. ha-paneld shows the old and new entity counts before asking for confirmation, then reloads the dashboard with the filtered subscription.

The Entities page explains why each entity was found, records manual pin and exclusion choices, and keeps recognized broad or dynamic rules visible until the user fixes them or explicitly chooses how to proceed. Unrecognized behavior can still exist, so test every dashboard tab after activation. If anything is missing, turn off **Entity filtering** in Configure and reload before revising the candidate.

### 面板暂缓显示仪表盘时

With automatic filtering on, the built-in renderer never opens Home Assistant unfiltered. Until a scan has produced a set it can vouch for, the panel shows a native hold screen instead of the dashboard, and the hold has three distinct causes. While the first scan is running or has failed, the panel retries it on a widening schedule, because the usual reason is that Home Assistant is not up yet. If Home Assistant rejects the panel's credential, the hold names the sign-in. If the scan finished and found a rule it cannot bound, such as a strategy-generated dashboard or an unbounded selector, the hold asks for a decision: ignore the flagged rules and continue, turn the filter off, or review them on the Entities page. That decision can be made at the panel or from any device on the network at `http://<panel>:8888/entities`, and the hold screen shows that address.

A hold that is waiting on a decision is settled, so the panel does not rescan the catalogue while it waits. It asks Home Assistant whether the dashboard changed, five minutes after the hold settles and then at most hourly, and rescans only when the dashboard's configuration or the account default has actually changed, when the decision is made, or when the panel's Home Assistant settings change. `GET /api/v1/dashboard/entities/sync` reports the cause in `hold_reason` (`synchronizing`, `synchronization`, `authentication` or `decision`) and sets `resync_suspended` while a decision is the only thing outstanding.

An update can force the panel to re-check a dashboard it was already filtering. When that re-check flags a rule on a dashboard the panel had already been running a filter on, the panel records the rule as ignored, restores the entity set it was running, and opens the dashboard rather than hold it; the rule stays visible on the Entities page and can be re-enabled there. This applies only to the re-check an update forces, only when a previously accepted filter exists, and only when the restored set is not empty. Rules the panel can never ignore, such as a dashboard too large to diagnose, still hold the renderer.

### 模板和手动固定

ha-paneld does not run dashboard templates, so it cannot know which entities a template returns, and it does not guess. What it does with the two kinds of entity a template touches is deliberately different.

Entities a template only **reads**, such as a state tested as a condition, need nothing. Home Assistant renders the template itself and sends the panel the result, over a separate subscription the entity filter does not touch. Those entities are supposed to be absent from the lists on the Entities page, and adding them would only make the subscription larger for no benefit.

Entities a template **returns** are different. They become cards on the dashboard, which read their state through the filtered subscription, so they do have to be in it. ha-paneld cannot discover them without running the template, and choosing to continue past an entity-discovery check does not add them either; that choice only lets automatic updates carry on without them.

To add one, type any part of its name or ID into the search box at the top of the Entities page. The search covers the complete Home Assistant catalogue rather than only the entities already found, and reports how many matches each table holds. Set every entity you need to **Pinned**. A manual pin is kept until you remove it, including across dashboard changes and rescans.

### 重置已学习的数据

Use **Reset learned data** on the Entities page when obsolete dashboard evidence or earlier manual decisions make the candidate misleading. After explicit confirmation it clears learned dashboard membership and evidence, manual pin/exclude overrides, and ignored safety decisions. It preserves the known-good active filter, keeps the Home Assistant catalog used for candidate names, and starts a replacement scan when learning is enabled. This makes reset a rebuild operation rather than an immediate expansion back to the full Home Assistant state stream.

The stronger API reset below can also remove the stored active filter by sending `clear_filter:true`. Use it only when the filter itself must be discarded.

### 手动精确列表

Advanced testers can bypass automatic learning and supply an exact list through the API. Create a JSON file containing every entity required by every dashboard tab, including entities referenced indirectly by custom cards or templates:

```json
{
  "enabled": true,
  "entity_ids": [
    "binary_sensor.front_door",
    "climate.living_room",
    "light.kitchen"
  ]
}
```

将完整列表上传到面板：

```bash
PANEL_IP=192.0.2.10
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data @entity-filter.json \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

内置渲染器会在更新后重新加载。仪表板重新连接后，检查状态：

```bash
curl --fail --show-error \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

A working filtered connection reports `enabled: true`, `runtime.active: true`, `runtime.mode: "native_socket"`, at least one `modifiedSubscriptions`, and zero `failures` and `directFallbacks`. A fallback means the dashboard remains connected but is receiving the ordinary unfiltered stream.

Posting `entity_ids` replaces the complete list. Keep your source JSON because the status endpoint deliberately returns only the count and a stable hash, and config exports do not include the entity IDs.

Disable filtering while retaining the stored list:

```bash
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data '{"enabled":false}' \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

Remove the stored filter, manual overrides, ignored safety decisions and rebuildable learning evidence with the confirmation-gated reset:

```bash
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data '{"confirm":true,"clear_filter":true}' \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entities/reset"
```

Like the rest of ha-paneld's control API, this endpoint is unauthenticated and intended for use on a trusted LAN.

## 主题

**仪表盘主题**（设置 → 内置渲染器）决定由谁选择浅色或深色：

- **Follow Home Assistant**（默认选项）会将选择权交给 Home Assistant。面板只提供一个初始值：在 Android 13+ 上，该值会实时跟随系统设置；在 Android 10-12 上，它会在仪表盘加载时跟随系统设置；在 Android 9 及更早版本上，则由“深色模式”开关（设置 → 显示）进行设置。在 Home Assistant 中选择的主题会覆盖该初始值。
- **Dark** 和 **Light** 会让面板作出选择。这适用于隐藏了侧边栏的自助终端仪表盘，因为此时无法从面板访问 Home Assistant 个人资料页面。

强制使用某个主题只会更改选择中的浅色/深色部分。命名主题及其颜色会原样保留；切换回 Follow Home Assistant 后，浅色/深色部分会恢复为此前的值，如果此前没有值，则恢复为自动。面板绝不会更改存储在你的 Home Assistant *账户*中的主题，因此设为 Dark 的面板无法让你的手机变为深色。

有一种情况它无法覆盖：如果该 Home Assistant 用户明确选择了浅色或深色（而非自动），该选择仍然优先，因为覆盖它就意味着更改一个会与该用户登录的所有其他设备共享的设置。将该用户的主题设为自动，或为面板使用单独的 Home Assistant 用户，面板的选择便会生效。发生这种情况时，面板会明确说明，而不会保持静默：**运行时诊断**卡片会在 `:8888` 页面上注明 Home Assistant 的主题正在覆盖仪表盘主题，而 `GET /api/v1/status` 会在 `renderer` 下将其报告为 `theme_overridden: true`，旁边还会显示 `theme_policy` 和 `theme_effective`，并在 `action` 中列出修复方法。

此 `:8888` Web 界面与上述所有设置相互独立，并始终跟随你用来查看它的浏览器。

## 还原

Open Configure, select an installed Home Assistant Companion app under **Dashboard app** and save the change. The switch takes effect immediately. Do not select **Auto** for this purpose because Auto uses the built-in renderer when it is ready.

## 限制

- **No support for more than one Home Assistant server, Assist voice control or native notifications.** Keep the HA Companion on the panel where those matter.
- **不提供全屏媒体附加功能**，例如文件选择器或投放式播放。这些功能永久不在支持范围内；如果这些功能很重要，请使用 Companion。
- A **current system WebView** is still required to render the Home Assistant frontend. ha-paneld can install a known-good WebView on supported rooted panels; an obsolete WebView produces a health warning in the `:8888` interface.
- Browser sign-in and advanced non-interactive provisioning work without root. Legacy Companion-session import requires root and remains only for existing installations.
