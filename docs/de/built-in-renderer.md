> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../built-in-renderer.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Der integrierte Dashboard-Renderer

> [!NOTE]
> **Experimentell (0.9).** Der integrierte Renderer ist der eingebaute Weg zum Filtern von Dashboard-Entitäten. Die HA Companion app wird weiterhin unterstützt, wenn ein Panel mehr als einen Home Assistant-Server, Assist-Sprachsteuerung oder native Benachrichtigungen benötigt.

ha-paneld kann das Home Assistant-Dashboard in seiner eigenen WebView anzeigen, statt es an eine separate Dashboard-App zu übergeben. Dadurch kann das Panel nach einem App-Neustart schneller zu seinem Dashboard zurückkehren. Es kann das letzte Dashboard erneut öffnen, das Home Assistant für denselben Server, dasselbe Konto und dieselbe Einstellung für das Start-Dashboard verifiziert hat, während es die aktuelle Dashboard-Liste im Hintergrund prüft. Wenn Home Assistant meldet, dass das Dashboard entfernt oder die Kontostandardeinstellung geändert wurde, wechselt das Panel zur aktuellen Auswahl.

Sobald das Dashboard ausgeführt wird, kann ha-paneld eine hängende Verbindung erkennen, angesammelten WebView-Speicher freigeben und Renderer-Abstürze eindämmen. Die integrierte Verbindung ermöglicht außerdem das Filtern von Dashboard-Entitäten. Das Panel bleibt ein Gerät für eine einzelne App, mit einer APK zum Installieren, Aktualisieren und Bereitstellen.

## Start und Wiederherstellung

The renderer uses Home Assistant's documented `?external_auth=1` interface, which is the same interface used by the HA Companion app. ha-paneld can therefore tell when the dashboard has connected instead of treating the page as a black box.

- Öffnet nach einem App-Neustart das letzte verifizierte Dashboard erneut, während die Dashboard-Liste von Home Assistant im Hintergrund aktualisiert wird. Zuerst wird weiterhin eine kurze Kompatibilitätsprüfung ausgeführt. Die gespeicherte Route ist an den Home Assistant-Server, das Konto und das konfigurierte Dashboard gebunden. Ein ausdrücklich konfiguriertes Dashboard oder eine ausdrücklich konfigurierte Dashboard-Registerkarte bleibt maßgeblich.
- Friert die Seite ein, während der Bildschirm ausgeschaltet ist, und setzt sie beim Aufwecken fort. Dadurch werden über Nacht ungefähr 70% der Renderer-CPU eingespart.
- Lädt ein Dashboard neu, das geöffnet, aber nie verbunden wurde. Nach wiederholten Fehlern erfolgen die Wiederholungsversuche in größeren Abständen, und das Panel zeigt statt einer Browser-Fehlerseite einen eindeutigen Bildschirm **Verbindung zu Home Assistant wird wiederhergestellt…** an.
- Automatically retries recoverable checks with increasing delays. A permanently rejected login stops the retry loop and shows Browser sign-in instructions. An unsupported Home Assistant version or incompatible WebView names the required update and waits for it.
- Gibt angesammelten Speicher durch unsichtbares Neuladen frei, während der Bildschirm ausgeschaltet ist.
- Contains and rate-limits renderer crashes. A page that continues to crash falls back to the admin launcher instead of restarting all night.
- When Home Assistant announces that it is stopping or goes offline through MQTT availability, the panel shows a native notice and clears it only after Home Assistant proves it is back.

You can pull down from the very top edge of the screen to refresh, or pull twice for a full reload. The renderer also supports an optional idle return to the Home dashboard, camera-stream autoplay and private-CA HTTPS using user-installed certificate authorities. **Hide Android system bars** provides an edge-to-edge dashboard; swipe from a screen edge to reveal the bars again. On panels using ha-paneld's software navigation bar, **Dashboard** brings the configured renderer to the foreground without reloading it. **Reload** remains a separate recovery action.

Der Renderer skaliert das Dashboard auf dieselbe Weise wie die Home Assistant Companion app, sodass das Layout beim Wechsel von Companion erhalten bleibt. **Zoomstufe (%)** passt das Ergebnis an, wobei 100% der Companion-Standardeinstellung entspricht. Der Renderer fügt der Seitenleiste von Home Assistant einen Eintrag **App-Einstellungen** hinzu, der die Konfigurationsseite des Panels öffnet. Beim ersten Start blendet er die angedockte Seitenleiste aus und hält die Verbindung im Leerlauf aufrecht. Sie können die Seitenleiste weiterhin öffnen oder diese Standardeinstellungen später ändern. Die separate Option **Navigation von Home Assistant ausblenden (nativ)** weist das Frontend an, seine Navigation zu entfernen, während der native Kioskmodus aktiv ist.

## Anforderungen und Kompatibilität

Ab ha-paneld 0.9.6 benötigt der integrierte Renderer beides:

- **Home Assistant 2026.4.2 oder neuer**; und
- eine Android System WebView, die den sicheren WebMessage-Listener unterstützt, den die Native-Host-Schnittstelle von Home Assistant verwendet.

Die meisten Benutzer benötigen nur eine aktuelle Android System WebView. ha-paneld prüft die erforderliche WebView-Funktion und verifiziert die Kompatibilität mit Home Assistant, bevor das Dashboard geladen wird.

If the panel shows **Home Assistant upgrade required**, upgrade Home Assistant and select **Retry**. Nothing on the panel substitutes for that.

Wenn **Die Webansicht dieses Panels ist zu alt** angezeigt wird, informiert Sie der Bildschirm darüber, welche Abhilfemaßnahmen bei diesem Panel möglich sind, da dies vom Modell und von der Einrichtung des Panels abhängt:

- **The panel can repair itself.** When a known-good Android System WebView is pinned in the panel profile and ha-paneld is permitted to install it, the screen offers **Update the web viewer**. Select it and the panel downloads and installs that version, then ha-paneld restarts once to use it. If the screen comes back afterwards, the pinned version did not resolve the fault and the manual routes below still apply.
- **The panel cannot, and the screen says why.** Once ha-paneld has confirmed that automatic repair is unavailable, it names one of three reasons, and the update has to be done by hand, after which you select **Retry**: a known-good version is pinned but ha-paneld is not permitted to install it; no known-good version is pinned for this panel; or the panel takes its Android System WebView from a store, which will replace it more safely than ha-paneld would. Reinstalling the same version repairs a damaged one.

How Android System WebView is updated by hand depends on the panel: some take it from Google Play, others only from a vendor firmware update or a manually installed build.

The built-in renderer does not fall back to the older, less isolated bridge. Another renderer may help when Home Assistant itself cannot be upgraded. The Companion app uses the same system WebView, so it cannot bypass an obsolete WebView on the panel.

## Aktivieren

On a new or reset panel, open `http://<panel>:8888/setup` from a laptop or phone, or select **Set up** on the panel itself. The guided journey chooses the renderer, signs in to Home Assistant, selects the account default, a dashboard or a specific dashboard tab, and asks about the entity filter before the first dashboard load. Authorization happens in the administrator's browser, so credentials do not need to be typed on the panel.

Öffnen Sie bei einem vorhandenen Panel unter `:8888` die Seite **Konfiguration** des Panels. Geben Sie unter **Home Assistant-Verbindung** die Home Assistant-URL ein und wählen Sie **Browser-Anmeldung**. Wählen Sie anschließend **Integrierter Renderer** als Dashboard-App aus.

Existing rooted installations that already imported a signed-in Companion session remain supported as a compatibility path. New installations should use Browser sign-in.

Ersetzen Sie für die unbeaufsichtigte Einrichtung von einem Administratorrechner aus die beispielhafte Paneladresse und die Home Assistant-Angaben in diesem ohne Checkout ausführbaren Befehl (siehe [Bereitstellung](provisioning.md)):

```bash
# First create an owner-only password file as shown in the linked provisioning guide.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt
```

The password never reaches the panel because the login happens on your machine. The panel holds a revocable refresh token. A long-lived access token works too: `--ha-token-file ha-token.txt` instead of `--ha-user/--ha-pass-file`. See [Provisioning and fleet updates](provisioning.md) for securely creating credential files and the trusted-LAN transport boundary. Literal `--ha-pass` and `--ha-token` values remain compatibility options, but expose the value in the original shell command and process list.

For automated provisioning, a token or username/password flow remains available as an advanced fallback. Interactive installations should use Browser sign-in.

## Dashboard-Darstellung im Vergleich zur Android-Sperre

Die erweiterte Konfiguration bietet drei voneinander unabhängige Steuerelemente. Jedes wirkt sich auf eine andere Ebene aus:

| Konfigurationsoption | Was dadurch geändert wird | Was dadurch **nicht** geändert wird |
|---|---|---|
| **Navigation von Home Assistant ausblenden (nativ)** (standardmäßig aktiviert) | After Home Assistant connects, asks its native frontend to hide its navigation. Built-in renderer only. | Does not lock Android, hide Android system bars, or inject or modify dashboard CSS. If Home Assistant rejects or does not support the command, the dashboard is left unchanged. |
| **Android-Systemleisten ausblenden** (standardmäßig aktiviert) | Hides Android's status and navigation bars for an edge-to-edge dashboard. Swipe from an edge to reveal them. Built-in renderer only. | Does not prevent someone leaving the app and does not hide Home Assistant's own menus/navigation. |
| **Android auf Dashboard sperren (experimentell)** (standardmäßig deaktiviert) | With root, hides Android system bars and returns to the selected dashboard within about three seconds when another app or Recents opens. This is a casual-use deterrent, not an adversarial security boundary. | Does not change the Home Assistant dashboard appearance. It has no effect without root. Reboot provides a 60-second unlocked recovery window, then the saved lock is reasserted. |

For a cleaner dashboard, start with **Hide Home Assistant navigation (native)** and/or **Hide Android system bars**. Enable **Lock Android to dashboard** only when discouraging casual escape from the app is required and you have tested the documented release routes: Configure, the Home Assistant switch, adb, seven rapid taps in the top-left corner, or the unlocked window after reboot.

## Experimenteller Entitätsfilter

> [!WARNING]
> This is an opt-in tester feature. Automatic learning cannot prove every custom-card or dynamic-template dependency, and an incomplete entity set can leave cards missing or stale. Review it on a non-critical panel first and keep the filter-disable rollback available.

The filter applies only to ha-paneld's built-in renderer. It changes the frontend's Home Assistant subscription, so Home Assistant filters the states before serializing and sending them to the panel. The Companion app and other dashboard applications are unaffected.

### Automatischer Ablauf

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Entity filtering**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and use its controls, pop-ups and conditional content so ha-paneld can observe runtime dependencies.
4. Review the current, suggested and excluded lists. Pin entities used indirectly by custom cards or templates, and resolve any entity-filter checks shown above the tables.
5. Select **Apply policy set** when the candidate is ready. ha-paneld shows the old and new entity counts before asking for confirmation, then reloads the dashboard with the filtered subscription.

The Entities page explains why each entity was found, records manual pin and exclusion choices, and keeps recognized broad or dynamic rules visible until the user fixes them or explicitly chooses how to proceed. Unrecognized behavior can still exist, so test every dashboard tab after activation. If anything is missing, turn off **Entity filtering** in Configure and reload before revising the candidate.

### Wenn das Panel das Dashboard zurückhält

With automatic filtering on, the built-in renderer never opens Home Assistant unfiltered. Until a scan has produced a set it can vouch for, the panel shows a native hold screen instead of the dashboard, and the hold has three distinct causes. While the first scan is running or has failed, the panel retries it on a widening schedule, because the usual reason is that Home Assistant is not up yet. If Home Assistant rejects the panel's credential, the hold names the sign-in. If the scan finished and found a rule it cannot bound, such as a strategy-generated dashboard or an unbounded selector, the hold asks for a decision: ignore the flagged rules and continue, turn the filter off, or review them on the Entities page. That decision can be made at the panel or from any device on the network at `http://<panel>:8888/entities`, and the hold screen shows that address.

A hold that is waiting on a decision is settled, so the panel does not rescan the catalogue while it waits. It asks Home Assistant whether the dashboard changed, five minutes after the hold settles and then at most hourly, and rescans only when the dashboard's configuration or the account default has actually changed, when the decision is made, or when the panel's Home Assistant settings change. `GET /api/v1/dashboard/entities/sync` reports the cause in `hold_reason` (`synchronizing`, `synchronization`, `authentication` or `decision`) and sets `resync_suspended` while a decision is the only thing outstanding.

An update can force the panel to re-check a dashboard it was already filtering. When that re-check flags a rule on a dashboard the panel had already been running a filter on, the panel records the rule as ignored, restores the entity set it was running, and opens the dashboard rather than hold it; the rule stays visible on the Entities page and can be re-enabled there. This applies only to the re-check an update forces, only when a previously accepted filter exists, and only when the restored set is not empty. Rules the panel can never ignore, such as a dashboard too large to diagnose, still hold the renderer.

### Vorlagen und manuelle Anheftungen

ha-paneld does not run dashboard templates, so it cannot know which entities a template returns, and it does not guess. What it does with the two kinds of entity a template touches is deliberately different.

Entities a template only **reads**, such as a state tested as a condition, need nothing. Home Assistant renders the template itself and sends the panel the result, over a separate subscription the entity filter does not touch. Those entities are supposed to be absent from the lists on the Entities page, and adding them would only make the subscription larger for no benefit.

Entities a template **returns** are different. They become cards on the dashboard, which read their state through the filtered subscription, so they do have to be in it. ha-paneld cannot discover them without running the template, and choosing to continue past an entity-discovery check does not add them either; that choice only lets automatic updates carry on without them.

To add one, type any part of its name or ID into the search box at the top of the Entities page. The search covers the complete Home Assistant catalogue rather than only the entities already found, and reports how many matches each table holds. Set every entity you need to **Pinned**. A manual pin is kept until you remove it, including across dashboard changes and rescans.

### Gelernte Daten zurücksetzen

Use **Reset learned data** on the Entities page when obsolete dashboard evidence or earlier manual decisions make the candidate misleading. After explicit confirmation it clears learned dashboard membership and evidence, manual pin/exclude overrides, and ignored safety decisions. It preserves the known-good active filter, keeps the Home Assistant catalog used for candidate names, and starts a replacement scan when learning is enabled. This makes reset a rebuild operation rather than an immediate expansion back to the full Home Assistant state stream.

The stronger API reset below can also remove the stored active filter by sending `clear_filter:true`. Use it only when the filter itself must be discarded.

### Manuelle exakte Liste

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

Lade die vollständige Liste auf das Panel hoch:

```bash
PANEL_IP=192.0.2.10
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data @entity-filter.json \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

Der integrierte Renderer wird nach einer Aktualisierung neu geladen. Prüfe den Status, sobald das Dashboard die Verbindung wiederhergestellt hat:

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

## Designanpassung

**Dashboard-Theme** (Konfiguration → Integrierter Renderer) legt fest, wer zwischen Hell und Dunkel wählt:

- **Follow Home Assistant** (Standardeinstellung) überlässt die Wahl Home Assistant. Das Panel gibt nur einen Ausgangswert vor: Unter Android 13+ folgt dieser der Systemeinstellung in Echtzeit, unter Android 10-12 folgt er der Systemeinstellung beim Laden des Dashboards und unter Android 9 und älter wird er über den Schalter „Dunkelmodus“ (Konfiguration → Anzeige) festgelegt. Ein in Home Assistant ausgewähltes Theme hat Vorrang vor diesem Ausgangswert.
- **Dark** und **Light** lassen das Panel wählen. Dies ist für ein Kiosk-Dashboard mit ausgeblendeter Seitenleiste vorgesehen, bei dem die Home Assistant-Profilseite über das Panel überhaupt nicht erreichbar ist.

Das Erzwingen eines Themes ändert nur den hellen beziehungsweise dunklen Teil der Auswahl. Ein benanntes Theme und seine Farben bleiben exakt unverändert. Beim Zurückwechseln zu Follow Home Assistant wird der helle beziehungsweise dunkle Teil auf seinen vorherigen Wert zurückgesetzt oder auf Auto, falls es keinen vorherigen Wert gab. Das Panel ändert niemals das in Ihrem Home Assistant-*Konto* gespeicherte Theme. Daher kann ein auf Dark eingestelltes Panel Ihr Telefon nicht abdunkeln.

In einem Fall kann das Panel die Auswahl nicht überschreiben: Wenn dieser Home Assistant-Benutzer ausdrücklich Hell oder Dunkel statt Auto ausgewählt hat, hat diese Auswahl weiterhin Vorrang. Sie zu überschreiben würde bedeuten, eine Einstellung zu ändern, die mit jedem anderen Gerät geteilt wird, auf dem sich dieser Benutzer anmeldet. Stellen Sie das Theme des Benutzers auf Auto oder verwenden Sie für das Panel einen separaten Home Assistant-Benutzer, damit die Auswahl des Panels angewendet wird. In diesem Fall weist das Panel ausdrücklich darauf hin: Die Karte **Laufzeitdiagnose** auf den `:8888`-Seiten meldet, dass das Home Assistant-Theme das Dashboard-Theme überschreibt. `GET /api/v1/status` meldet dies unter `renderer` als `theme_overridden: true`; daneben stehen `theme_policy` und `theme_effective`, und die Abhilfe ist in `action` angegeben.

Die `:8888`-Weboberfläche ist von all dem unabhängig und folgt immer dem Browser, in dem Sie sie anzeigen.

## Zurückwechseln

Open Configure, select an installed Home Assistant Companion app under **Dashboard app** and save the change. The switch takes effect immediately. Do not select **Auto** for this purpose because Auto uses the built-in renderer when it is ready.

## Einschränkungen

- **No support for more than one Home Assistant server, Assist voice control or native notifications.** Keep the HA Companion on the panel where those matter.
- **Keine zusätzlichen Vollbild-Medienfunktionen** wie eine Dateiauswahl oder eine Casting-ähnliche Wiedergabe. Diese Funktionen bleiben dauerhaft außerhalb des Funktionsumfangs. Verwenden Sie den Companion, wenn sie benötigt werden.
- A **current system WebView** is still required to render the Home Assistant frontend. ha-paneld can install a known-good WebView on supported rooted panels; an obsolete WebView produces a health warning in the `:8888` interface.
- Browser sign-in and advanced non-interactive provisioning work without root. Legacy Companion-session import requires root and remains only for existing installations.
