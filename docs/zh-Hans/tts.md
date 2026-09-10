> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../tts.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# 通过 Home Assistant 实现文本转语音（TTS）

Speak any Home Assistant TTS voice (Piper, Home Assistant Cloud, …) on a panel with two `rest_command`s and a small script: HA renders the phrase to an audio URL and the panel downloads and plays it via `POST /play`. Copy the three blocks under [Setup](#设置), then call `script.ha_paneld_say`.

服务端 TTS 可提供现代化的语音，其效果远胜于旧款面板上的设备端 TTS。

> [!NOTE]
> Home Assistant **没有 MQTT `media_player`平台**，因此面板（目前）还无法作为 `media_player` 直接接收 `tts.speak`；此功能已列入[路线图](../roadmap.md#longer-term-stretch-goals)。本方案是当前的过渡方式。

> [!NOTE]
> `/play` accepts the bare URL as the request body (or `{"url":"…"}`), **not** a form field.

## 设置

You need a TTS engine (e.g. `tts.piper`) and a **Long-Lived Access Token** (Profile → Security) in `secrets.yaml`. You'll also inline your HA's LAN base URL in the script below.[^secrets]

`secrets.yaml`：

```yaml
ha_token: "Bearer eyJ…"                 # Long-Lived Access Token, prefixed with "Bearer "
```

`configuration.yaml`：

```yaml
rest_command:
  # Render a phrase with an HA TTS engine; returns {"url", "path"}.
  ha_paneld_tts_get_url:
    url: "http://127.0.0.1:8123/api/tts_get_url"
    method: POST
    headers:
      authorization: !secret ha_token
      content-type: application/json
    payload: '{"engine_id": "{{ engine }}", "message": "{{ message }}"}'

  # Send a ready audio URL to a panel's /play (raw URL in the body).
  ha_paneld_play:
    url: "{{ base }}play"
    method: POST
    payload: "{{ media_url }}"
```

`scripts.yaml`：

```yaml
ha_paneld_say:
  alias: "Panel: speak (TTS)"
  fields:
    message:
      description: "Phrase to speak"
      example: "Dinner is ready"
    target:
      description: "Any ha-paneld entity on the target panel"
      example: "light.kitchen_screen"
    engine:
      description: "TTS engine entity_id"
      example: "tts.piper"
  sequence:
    - service: rest_command.ha_paneld_tts_get_url
      data:
        engine: "{{ engine | default('tts.piper') }}"
        message: "{{ message }}"
      response_variable: tts
    - service: rest_command.ha_paneld_play
      data:
        # Panel address from its HA device — no IP to hardcode.
        base: "{{ device_attr(device_id(target), 'configuration_url') }}"
        # Rebuild on your LAN base so the panel can fetch it (tts_get_url returns the external URL).
        # Inline your internal_url here (templates can't read secrets.yaml):
        media_url: "http://homeassistant.local:8123{{ tts['content']['path'] }}"
```

> [!TIP]
> Templates can't read `secrets.yaml` directly. Either inline your internal base in the script (`media_url: "http://homeassistant.local:8123{{ tts['content']['path'] }}"`) or expose it via a `template` sensor. If your **external** URL is reachable from the panel, you can skip the rebuild and use `tts['content']['url']` straight from the response.

## 使用方法

通过自动化或 UI：

```yaml
- service: script.ha_paneld_say
  data:
    message: "The washing machine has finished"
    target: light.kitchen_screen     # any entity belonging to the target panel
    engine: tts.piper
```

The panel's volume is `number.<panel>_volume` (set it beforehand if needed). For multi-room/broadcast, call the script once per panel.

## 工作原理

1. HA renders the phrase and returns an audio URL via `POST /api/tts_get_url`.
2. `tts_get_url` returns your **external** URL, so the script rebuilds it on your **`internal_url` + the returned `path`** so the panel can fetch it on the LAN.
3. It POSTs that URL (raw, in the body) to the panel's `/play`. The panel's address comes from its HA device `configuration_url` (`http://<ip>:8888/`) — so you just pass the **target panel entity**.

[^secrets]: Templates can't read `secrets.yaml` directly, which is why the LAN base URL is inlined in the script rather than referenced as a secret.
