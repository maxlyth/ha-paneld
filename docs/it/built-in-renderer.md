> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../built-in-renderer.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Il renderer integrato della dashboard

> [!NOTE]
> **Sperimentale (0.9).** Il renderer integrato è la soluzione integrata per filtrare le entità della dashboard. L'app HA Companion rimane supportata quando un pannello deve utilizzare più di un server Home Assistant, il controllo vocale di Assist o le notifiche native.

ha-paneld può visualizzare la dashboard di Home Assistant nella propria WebView anziché affidarla a un'app separata per le dashboard. Ciò consente al pannello di tornare alla propria dashboard con meno ritardo dopo il riavvio dell'app. Può riaprire l'ultima dashboard verificata da Home Assistant per lo stesso server, account e la stessa impostazione Plancia principale mentre controlla in background l'elenco corrente delle dashboard. Se Home Assistant segnala che la dashboard è stata rimossa o che l'impostazione predefinita dell'account è cambiata, il pannello passa alla scelta corrente.

Una volta avviata la dashboard, ha-paneld può rilevare una connessione bloccata, liberare la memoria accumulata dalla WebView e contenere gli arresti anomali del renderer. La connessione integrata consente anche di filtrare le entità della dashboard. Il pannello rimane un dispositivo a singola app, con un solo APK da installare, aggiornare e configurare.

## Avvio e ripristino

The renderer uses Home Assistant's documented `?external_auth=1` interface, which is the same interface used by the HA Companion app. ha-paneld can therefore tell when the dashboard has connected instead of treating the page as a black box.

- Riapre l'ultima dashboard verificata dopo il riavvio dell'app mentre aggiorna in background l'elenco delle dashboard di Home Assistant. Viene comunque eseguito prima un breve controllo di compatibilità. Il percorso memorizzato è associato al server Home Assistant, all'account e alla dashboard configurata; una dashboard o una scheda della dashboard configurata esplicitamente rimane autorevole.
- Sospende la pagina mentre lo schermo è spento e la riprende alla riattivazione, risparmiando circa il 70% della CPU del renderer durante la notte.
- Ricarica una dashboard che si è aperta ma non si è mai connessa. I nuovi tentativi rallentano dopo errori ripetuti e il pannello mostra una chiara schermata **Riconnessione a Home Assistant…** anziché una pagina di errore del browser.
- Automatically retries recoverable checks with increasing delays. A permanently rejected login stops the retry loop and shows Browser sign-in instructions. An unsupported Home Assistant version or incompatible WebView names the required update and waits for it.
- Libera la memoria accumulata mediante ricaricamenti invisibili mentre lo schermo è spento.
- Contains and rate-limits renderer crashes. A page that continues to crash falls back to the admin launcher instead of restarting all night.
- When Home Assistant announces that it is stopping or goes offline through MQTT availability, the panel shows a native notice and clears it only after Home Assistant proves it is back.

You can pull down from the very top edge of the screen to refresh, or pull twice for a full reload. The renderer also supports an optional idle return to the Home dashboard, camera-stream autoplay and private-CA HTTPS using user-installed certificate authorities. **Hide Android system bars** provides an edge-to-edge dashboard; swipe from a screen edge to reveal the bars again. On panels using ha-paneld's software navigation bar, **Dashboard** brings the configured renderer to the foreground without reloading it. **Reload** remains a separate recovery action.

Il renderer dimensiona la dashboard allo stesso modo dell'app Home Assistant Companion, quindi il passaggio da Companion mantiene il layout. **Zoom (%)** regola il risultato, con 100% corrispondente all'impostazione predefinita di Companion. Il renderer aggiunge una voce **Impostazioni dell'app** alla barra laterale di Home Assistant, che apre la pagina di configurazione del pannello. Al primo avvio nasconde la barra laterale ancorata e mantiene attiva la connessione durante l'inattività. Puoi comunque aprire la barra laterale o modificare queste impostazioni predefinite in seguito. L'opzione separata **Nascondi navigazione Home Assistant (nativa)** chiede al frontend di rimuovere la propria navigazione mentre è attiva la modalità kiosk nativa.

## Requisiti e compatibilità

A partire da ha-paneld 0.9.6, il renderer integrato richiede entrambi i seguenti requisiti:

- **Home Assistant 2026.4.2 o versione successiva**; e
- un'Android System WebView che supporti il listener WebMessage sicuro utilizzato dall'interfaccia host nativa di Home Assistant.

Alla maggior parte degli utenti serve solo una versione aggiornata di Android System WebView. ha-paneld controlla la funzionalità WebView richiesta e verifica la compatibilità con Home Assistant prima di caricare la dashboard.

If the panel shows **Home Assistant upgrade required**, upgrade Home Assistant and select **Retry**. Nothing on the panel substitutes for that.

Se mostra **Il visualizzatore web del pannello è troppo vecchio**, la schermata indica cosa può fare questo specifico pannello, poiché dipende dal modello e da come è configurato:

- **The panel can repair itself.** When a known-good Android System WebView is pinned in the panel profile and ha-paneld is permitted to install it, the screen offers **Update the web viewer**. Select it and the panel downloads and installs that version, then ha-paneld restarts once to use it. If the screen comes back afterwards, the pinned version did not resolve the fault and the manual routes below still apply.
- **The panel cannot, and the screen says why.** Once ha-paneld has confirmed that automatic repair is unavailable, it names one of three reasons, and the update has to be done by hand, after which you select **Retry**: a known-good version is pinned but ha-paneld is not permitted to install it; no known-good version is pinned for this panel; or the panel takes its Android System WebView from a store, which will replace it more safely than ha-paneld would. Reinstalling the same version repairs a damaged one.

How Android System WebView is updated by hand depends on the panel: some take it from Google Play, others only from a vendor firmware update or a manually installed build.

The built-in renderer does not fall back to the older, less isolated bridge. Another renderer may help when Home Assistant itself cannot be upgraded. The Companion app uses the same system WebView, so it cannot bypass an obsolete WebView on the panel.

## Attivazione

On a new or reset panel, open `http://<panel>:8888/setup` from a laptop or phone, or select **Set up** on the panel itself. The guided journey chooses the renderer, signs in to Home Assistant, selects the account default, a dashboard or a specific dashboard tab, and asks about the entity filter before the first dashboard load. Authorization happens in the administrator's browser, so credentials do not need to be typed on the panel.

Su un pannello esistente, apri la pagina `:8888` **Configura** del pannello. In **Connessione a Home Assistant**, inserisci l'URL di Home Assistant e scegli **Accesso dal browser**, quindi seleziona **Renderer integrato** come App della dashboard.

Existing rooted installations that already imported a signed-in Companion session remain supported as a compatibility path. New installations should use Browser sign-in.

Per la configurazione automatica da un computer amministrativo, sostituisci l'indirizzo di esempio del pannello e i dati di Home Assistant in questo comando che non richiede il checkout (vedi [Provisioning](provisioning.md)):

```bash
# First create an owner-only password file as shown in the linked provisioning guide.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt
```

The password never reaches the panel because the login happens on your machine. The panel holds a revocable refresh token. A long-lived access token works too: `--ha-token-file ha-token.txt` instead of `--ha-user/--ha-pass-file`. See [Provisioning and fleet updates](provisioning.md) for securely creating credential files and the trusted-LAN transport boundary. Literal `--ha-pass` and `--ha-token` values remain compatibility options, but expose the value in the original shell command and process list.

For automated provisioning, a token or username/password flow remains available as an advanced fallback. Interactive installations should use Browser sign-in.

## Aspetto della Dashboard e blocco di Android a confronto

La sezione avanzata di Configura offre tre controlli indipendenti. Ciascuno agisce su un livello diverso:

| Opzione di Configura | Cosa modifica | Cosa **non** modifica |
|---|---|---|
| **Nascondi navigazione Home Assistant (nativa)** (attiva per impostazione predefinita) | After Home Assistant connects, asks its native frontend to hide its navigation. Built-in renderer only. | Does not lock Android, hide Android system bars, or inject or modify dashboard CSS. If Home Assistant rejects or does not support the command, the dashboard is left unchanged. |
| **Nascondi barre di sistema Android** (attivo per impostazione predefinita) | Hides Android's status and navigation bars for an edge-to-edge dashboard. Swipe from an edge to reveal them. Built-in renderer only. | Does not prevent someone leaving the app and does not hide Home Assistant's own menus/navigation. |
| **Blocca Android sulla plancia (sperimentale)** (disattivo per impostazione predefinita) | With root, hides Android system bars and returns to the selected dashboard within about three seconds when another app or Recents opens. This is a casual-use deterrent, not an adversarial security boundary. | Does not change the Home Assistant dashboard appearance. It has no effect without root. Reboot provides a 60-second unlocked recovery window, then the saved lock is reasserted. |

For a cleaner dashboard, start with **Hide Home Assistant navigation (native)** and/or **Hide Android system bars**. Enable **Lock Android to dashboard** only when discouraging casual escape from the app is required and you have tested the documented release routes: Configure, the Home Assistant switch, adb, seven rapid taps in the top-left corner, or the unlocked window after reboot.

## Filtro entità sperimentale

> [!WARNING]
> This is an opt-in tester feature. Automatic learning cannot prove every custom-card or dynamic-template dependency, and an incomplete entity set can leave cards missing or stale. Review it on a non-critical panel first and keep the filter-disable rollback available.

The filter applies only to ha-paneld's built-in renderer. It changes the frontend's Home Assistant subscription, so Home Assistant filters the states before serializing and sending them to the panel. The Companion app and other dashboard applications are unaffected.

### Procedura automatica

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Entity filtering**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and use its controls, pop-ups and conditional content so ha-paneld can observe runtime dependencies.
4. Review the current, suggested and excluded lists. Pin entities used indirectly by custom cards or templates, and resolve any entity-filter checks shown above the tables.
5. Select **Apply policy set** when the candidate is ready. ha-paneld shows the old and new entity counts before asking for confirmation, then reloads the dashboard with the filtered subscription.

The Entities page explains why each entity was found, records manual pin and exclusion choices, and keeps recognized broad or dynamic rules visible until the user fixes them or explicitly chooses how to proceed. Unrecognized behavior can still exist, so test every dashboard tab after activation. If anything is missing, turn off **Entity filtering** in Configure and reload before revising the candidate.

### Quando il pannello mantiene in attesa la dashboard

With automatic filtering on, the built-in renderer never opens Home Assistant unfiltered. Until a scan has produced a set it can vouch for, the panel shows a native hold screen instead of the dashboard, and the hold has three distinct causes. While the first scan is running or has failed, the panel retries it on a widening schedule, because the usual reason is that Home Assistant is not up yet. If Home Assistant rejects the panel's credential, the hold names the sign-in. If the scan finished and found a rule it cannot bound, such as a strategy-generated dashboard or an unbounded selector, the hold asks for a decision: ignore the flagged rules and continue, turn the filter off, or review them on the Entities page. That decision can be made at the panel or from any device on the network at `http://<panel>:8888/entities`, and the hold screen shows that address.

A hold that is waiting on a decision is settled, so the panel does not rescan the catalogue while it waits. It asks Home Assistant whether the dashboard changed, five minutes after the hold settles and then at most hourly, and rescans only when the dashboard's configuration or the account default has actually changed, when the decision is made, or when the panel's Home Assistant settings change. `GET /api/v1/dashboard/entities/sync` reports the cause in `hold_reason` (`synchronizing`, `synchronization`, `authentication` or `decision`) and sets `resync_suspended` while a decision is the only thing outstanding.

An update can force the panel to re-check a dashboard it was already filtering. When that re-check flags a rule on a dashboard the panel had already been running a filter on, the panel records the rule as ignored, restores the entity set it was running, and opens the dashboard rather than hold it; the rule stays visible on the Entities page and can be re-enabled there. This applies only to the re-check an update forces, only when a previously accepted filter exists, and only when the restored set is not empty. Rules the panel can never ignore, such as a dashboard too large to diagnose, still hold the renderer.

### Template e fissaggi manuali

ha-paneld does not run dashboard templates, so it cannot know which entities a template returns, and it does not guess. What it does with the two kinds of entity a template touches is deliberately different.

Entities a template only **reads**, such as a state tested as a condition, need nothing. Home Assistant renders the template itself and sends the panel the result, over a separate subscription the entity filter does not touch. Those entities are supposed to be absent from the lists on the Entities page, and adding them would only make the subscription larger for no benefit.

Entities a template **returns** are different. They become cards on the dashboard, which read their state through the filtered subscription, so they do have to be in it. ha-paneld cannot discover them without running the template, and choosing to continue past an entity-discovery check does not add them either; that choice only lets automatic updates carry on without them.

To add one, type any part of its name or ID into the search box at the top of the Entities page. The search covers the complete Home Assistant catalogue rather than only the entities already found, and reports how many matches each table holds. Set every entity you need to **Pinned**. A manual pin is kept until you remove it, including across dashboard changes and rescans.

### Reimposta dati appresi

Use **Reset learned data** on the Entities page when obsolete dashboard evidence or earlier manual decisions make the candidate misleading. After explicit confirmation it clears learned dashboard membership and evidence, manual pin/exclude overrides, and ignored safety decisions. It preserves the known-good active filter, keeps the Home Assistant catalog used for candidate names, and starts a replacement scan when learning is enabled. This makes reset a rebuild operation rather than an immediate expansion back to the full Home Assistant state stream.

The stronger API reset below can also remove the stored active filter by sending `clear_filter:true`. Use it only when the filter itself must be discarded.

### Elenco esatto manuale

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

Carica l'elenco completo nel pannello:

```bash
PANEL_IP=192.0.2.10
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data @entity-filter.json \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

Il renderer integrato si ricarica dopo un aggiornamento. Quando la dashboard si è riconnessa, controlla lo stato:

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

## Temi

**Tema della plancia** (Configura → Renderer integrato) determina chi sceglie la modalità chiara o scura:

- **Follow Home Assistant** (impostazione predefinita) lascia la scelta a Home Assistant. Il pannello fornisce solo un punto di partenza: su Android 13+ segue in tempo reale l'impostazione di sistema, su Android 10-12 segue l'impostazione di sistema quando viene caricata la dashboard, mentre su Android 9 e versioni precedenti viene determinato dall'interruttore "Modalità scura" (Configura → Schermo). Un tema selezionato in Home Assistant ha la precedenza su questo punto di partenza.
- **Dark** e **Light** fanno scegliere il pannello. Questa opzione è pensata per una dashboard in modalità kiosk con la barra laterale nascosta, nella quale la pagina del profilo di Home Assistant non è in alcun modo accessibile dal pannello.

Forzare un tema modifica solo la componente chiara/scura della scelta. Un tema specifico e i relativi colori rimangono esattamente invariati; tornando a Follow Home Assistant, la componente chiara/scura torna al valore precedente oppure ad Auto se non ne era impostato alcuno. Il pannello non modifica mai il tema memorizzato per il tuo *account* Home Assistant, quindi un pannello impostato su Dark non può rendere scuro il tuo telefono.

C'è un caso in cui non può avere la precedenza: se questo utente Home Assistant ha scelto esplicitamente Chiaro o Scuro (anziché Auto), tale scelta continua a prevalere, perché ignorarla significherebbe modificare un'impostazione condivisa con ogni altro dispositivo su cui l'utente ha effettuato l'accesso. Imposta il tema dell'utente su Auto oppure usa un utente Home Assistant distinto per il pannello affinché venga applicata la scelta del pannello. Quando ciò accade, il pannello lo segnala esplicitamente: la scheda **Diagnostica runtime** nelle pagine `:8888` indica che il tema di Home Assistant ha la precedenza su Tema della plancia, mentre `GET /api/v1/status` lo segnala nella sezione `renderer` come `theme_overridden: true`, con `theme_policy` e `theme_effective` accanto e la correzione indicata in `action`.

L'interfaccia web `:8888` è separata da tutto questo e segue sempre il browser in cui viene visualizzata.

## Ripristino

Open Configure, select an installed Home Assistant Companion app under **Dashboard app** and save the change. The switch takes effect immediately. Do not select **Auto** for this purpose because Auto uses the built-in renderer when it is ready.

## Limitazioni

- **No support for more than one Home Assistant server, Assist voice control or native notifications.** Keep the HA Companion on the panel where those matter.
- **Nessuna funzionalità multimediale aggiuntiva a schermo intero**, come un selettore di file o la riproduzione in stile casting. Queste funzionalità sono definitivamente escluse; usa Companion se sono necessarie.
- A **current system WebView** is still required to render the Home Assistant frontend. ha-paneld can install a known-good WebView on supported rooted panels; an obsolete WebView produces a health warning in the `:8888` interface.
- Browser sign-in and advanced non-interactive provisioning work without root. Legacy Companion-session import requires root and remains only for existing installations.
