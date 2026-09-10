> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../tts.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Texto a voz (TTS) desde Home Assistant

Speak any Home Assistant TTS voice (Piper, Home Assistant Cloud, …) on a panel with two `rest_command`s and a small script: HA renders the phrase to an audio URL and the panel downloads and plays it via `POST /play`. Copy the three blocks under [Setup](#configuración), then call `script.ha_paneld_say`.

El TTS del lado del servidor ofrece voces modernas, mucho mejores que el TTS integrado en los paneles antiguos.

> [!NOTE]
> Home Assistant **no dispone de ninguna plataforma `media_player` de MQTT**, por lo que un panel (aún) no es un `media_player` al que se pueda dirigir `tts.speak` directamente; esto está incluido en la [hoja de ruta](../roadmap.md#longer-term-stretch-goals). Este procedimiento es la solución provisional.

> [!NOTE]
> `/play` accepts the bare URL as the request body (or `{"url":"…"}`), **not** a form field.

## Configuración

You need a TTS engine (e.g. `tts.piper`) and a **Long-Lived Access Token** (Profile → Security) in `secrets.yaml`. You'll also inline your HA's LAN base URL in the script below.[^secrets]

`secrets.yaml`:

```yaml
ha_token: "Bearer eyJ…"                 # Long-Lived Access Token, prefixed with "Bearer "
```

`configuration.yaml`:

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

`scripts.yaml`:

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

## Uso

Desde una automatización o la UI:

```yaml
- service: script.ha_paneld_say
  data:
    message: "The washing machine has finished"
    target: light.kitchen_screen     # any entity belonging to the target panel
    engine: tts.piper
```

The panel's volume is `number.<panel>_volume` (set it beforehand if needed). For multi-room/broadcast, call the script once per panel.

## Cómo funciona

1. HA renders the phrase and returns an audio URL via `POST /api/tts_get_url`.
2. `tts_get_url` returns your **external** URL, so the script rebuilds it on your **`internal_url` + the returned `path`** so the panel can fetch it on the LAN.
3. It POSTs that URL (raw, in the body) to the panel's `/play`. The panel's address comes from its HA device `configuration_url` (`http://<ip>:8888/`) — so you just pass the **target panel entity**.

[^secrets]: Templates can't read `secrets.yaml` directly, which is why the LAN base URL is inlined in the script rather than referenced as a secret.
