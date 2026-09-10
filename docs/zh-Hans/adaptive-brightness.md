> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../adaptive-brightness.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# 自适应亮度

自适应亮度是一项可选的面板端控制功能，它会学习面板周围环境光的日常变化规律并调节屏幕，无需设置 Home Assistant 自动化。此功能默认关闭；关闭时，仍可使用常规的手动亮度控制或 Home Assistant 亮度控制。

## 选择环境光源

打开**配置 → 显示**，然后选择**环境光源**：

- 如果当前配置文件和实时功能检测表明面板自带环境光传感器，请将此项留空以使用该传感器。
- 如果面板没有合适的本地传感器，或者安装在房间内的传感器更能反映用户所处位置的光照，请选择一个 Home Assistant 照度实体。

The Home Assistant source uses one exact authenticated entity subscription rather than the full state stream. If neither source is available, ha-paneld leaves adaptive control unavailable instead of guessing from time alone.

When a Home Assistant illuminance source is selected, ha-paneld can seed the on-panel pattern with up to seven days of its existing Home Assistant history. This gives automatic brightness a useful starting point instead of waiting for fresh readings to accumulate. It depends on the source being recorded and the Home Assistant history service being available; if no usable history is returned, learning simply starts from new readings.

## 启用并调节

Enable **Auto-brightness** in the same Display card. The controller retains up to seven days of bounded, on-panel ambient history and learns the normal pattern for the time of day. Short positive deviations, such as a room light being switched on, can raise the proposed level above that baseline.

**Minimum level** sets the lowest level proposed by automatic control and rescales the learned range from that floor to full brightness. It ranges from 4% to 99%. The 4% floor is where the backlight's own never-blank minimum sits, so anything lower would move the control without changing the screen. It does not limit manual brightness, which can still be set lower.

**Sensitivity** is the percentage of a difference from the learned pattern that is applied to the screen, once the controller has decided the difference is real. At 0% it ignores the difference and uses the learned pattern alone. Lower values make the response steadier, and the default of 50% leaves equal room to tune in either direction. It is not a raw follow-the-light control: a brief brightening is only acted on once it has been sustained or risen sharply enough to be admitted, and while the daily pattern is still being learned the screen follows the measured light regardless of this setting. The seven-day chart previews the observed range, learned baseline and proposed level before or while the controller is active. Unsaved Minimum level and Sensitivity changes are reflected in the preview without rewriting stored ambient history.

The history is tied to the selected source and a coarse room/time context derived from the configured Home Assistant location and timezone. A material location, timezone or source change starts a separate history rather than silently applying evidence learned for another room context.

## 手动更改与恢复

A manual brightness change records a four-hour temporary preference, so the learner does not immediately fight the user. Subsequent automatic changes retain 20% influence at first, then regain full authority through a smooth four-hour fade. Select **Resume full auto** in the adaptive-brightness panel to end that preference immediately instead of waiting for the fade to complete.

Select **Reset learned history** after moving the panel, replacing its light source or when the retained week no longer represents the room. The confirmation deletes the seven-day ambient history for the current source and room/time context, then restarts learning; it does not change the selected source or the Auto-brightness setting.

Adaptive brightness uses Android's normal screen-brightness path and does not require root. Reading a particular panel sensor can still depend on that sensor's hardware access path; the active profile and live capability checks remain authoritative.

## Home Assistant 控制

When exposed, `switch.<panel>_auto_brightness` enables or disables the same on-panel controller. Screen brightness remains `light.<panel>_screen`. A manual brightness command can therefore create the same temporary preference as a local change; use the Configure page's **Resume full auto** action to return immediately to the learned target.
