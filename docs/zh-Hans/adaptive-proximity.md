> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../adaptive-proximity.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# 自适应接近感应与挥手唤醒

Android 墙壁面板上的接近传感器并不共用同一种有效量程。有些报告距离，有些只报告两个值；极性可能不同，不同设备或固件版本之间的空闲读数也可能有偏移。ha-paneld 会学习已连接传感器的无遮挡基线、近距离参考值、极性和报告方式，无需为特定型号设置阈值。

## 学习与示教

打开**配置 → 在场检测与唤醒**以查看当前阶段。学习在本地运行，通常无需设置：

1. 在面板识别空闲信号和房间基线时，请与面板保持距离。
2. 正常使用面板，以便系统将有意靠近和远离的动作与空闲抖动区分开来。
3. 如果需要更快地完成设置，请选择**示教挥手动作**，并在提示时有意挥手三次。
4. 准备就绪后，**测试挥手动作**可在不唤醒显示屏的情况下检查一次手势。

Touch-to-wake remains available throughout learning. **Forget learned proximity** deletes the evidence for that sensor and returns it to the learning journey; use it after moving the panel, changing firmware or replacing the sensor route.

## Home Assistant 实体

当模型可信且活动配置文件提供可用的接近信号源时，Home Assistant 会接收：

- `binary_sensor.<panel>_proximity`，表示学习得出的近距/远距占用状态；以及
- `sensor.<panel>_proximity_level`，表示在设备群中归一化的 0–100 数值，其中 0 表示远，100 表示近。

当证据不足或传感器路径异常时，这些实体将保持不可用。ha-paneld 不会根据不可信的模型发布看似可信的值。仅支持二元输出的传感器仍可提供占用状态和唤醒手势；只有当观测到的信号支持时，系统才会发布有意义的归一化级别。

## 挥手唤醒

**挥手唤醒**可识别一次有明确边界的远→近→远移动。长时间处于近距离、示教、测试以及短暂的空闲抖动都不会唤醒显示屏。这样可以避免将站在面板附近的人或噪声较大的多值传感器视为连续的唤醒请求。

此功能需要实时接近信号源和已就绪的学习模型，但该信号源本身既可以是普通的 Android 传感器，也可以是由配置文件选定的辅助路径。Web UI 和诊断信息会报告实际路径及其就绪状态；仅在配置文件中声明并不能凭空提供传感器。
