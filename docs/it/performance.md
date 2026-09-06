> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../performance.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Ottimizzazione delle prestazioni per i pannelli a parete Home Assistant

Questa guida spiega perché un pannello a parete Android economico può risultare lento, procedere a scatti durante lo scorrimento, oscurarsi o smettere di aggiornarsi anche se la stessa dashboard funziona bene su un telefono o un computer. Inizia dal carico di lavoro che Home Assistant invia al pannello, quindi misura i limiti residui della dashboard e dell'hardware anziché presumere che il pannello debba essere sostituito.

> [!TIP]
> Una causa comune è il numero di stati e aggiornamenti delle entità di Home Assistant che raggiungono la dashboard. Un'installazione di grandi dimensioni può costringere un pannello poco potente a elaborare migliaia di entità che non visualizza mai. Con il renderer integrato di ha-paneld, la prima soluzione da provare è il filtro delle entità.

## Misura prima di modificare qualsiasi cosa

Apri la pagina web del pannello all'indirizzo `http://<panel-ip>:8888/` oppure usa il link **Visita** nella pagina del dispositivo di Home Assistant. Registra la stessa vista della dashboard per un periodo simile prima e dopo ogni modifica, affinché il confronto sia significativo.

- **Reattività della dashboard** — latenza effettiva delle interazioni, suddivisione tra input, gestore e presentazione dell'interazione più lenta, blocco del thread principale, tempo necessario per diventare interattivo e ricaricamenti imprevisti del renderer.
- **Flusso degli stati di Home Assistant** — aggiornamenti di stato al secondo, velocità dei payload JSON non compressi, dimensione dell'idratazione iniziale, tempo prima che il thread principale ceda l'esecuzione e le tre entità con il contributo maggiore nell'ora mobile, classificate sia per frequenza degli aggiornamenti sia per volume dei payload.
- **Prestazioni** e **Processi principali** — CPU, GPU, RAM, velocità di clock, temperatura e processi con il carico maggiore.
- **Flusso fotocamera** (solo su un pannello il cui profilo dichiara una fotocamera): i client che mantengono aperta la sessione, l'encoder in uso, la frequenza dei fotogrammi richiesta accanto a quella fornita e il bitrate fornito accanto al limite di bitrate dell'encoder. Una frequenza nettamente inferiore a quella richiesta viene indicata come tale, senza attribuirle una causa: la temporizzazione dell'acquisizione in un ambiente poco illuminato, il percorso di codifica e la contesa per l'hardware video sono tutte possibili cause e la scheda non tenta di stabilire quale sia. La richiesta non viene mai ridotta silenziosamente. Quando la sessione non può fornire a un flusso la frequenza richiesta, la scheda mostra la richiesta accanto alla frequenza assegnata all'encoder, anziché modificare l'obiettivo per adeguarlo. La frequenza di acquisizione viene impostata da chi apre la fotocamera, pertanto un flusso che si unisce a una sessione aperta da un'istantanea viene codificato con il valore predefinito configurato. Deliberatamente non viene indicato alcun valore della CPU, perché la codifica hardware viene eseguita nell'hardware del codec e nel compositore, oltre che nell'app, e nessun singolo processo rappresenta il costo della fotocamera. Consulta il carico complessivo del pannello in Prestazioni e Processi principali.
- **Pagina Entità** — ciò che il renderer integrato sottoscrive attualmente, ciò che l'apprendimento automatico ha rilevato e quali regole della dashboard richiedono ancora una decisione.
- **Dump diagnostico** (`/diag`) — un rapporto da copiare e incollare in una segnalazione di bug quando la causa non è ancora chiara.

Il renderer integrato raccoglie direttamente queste misurazioni del browser e non richiede root né il debug di WebView. L'app Companion rimane una modalità di compatibilità: ha-paneld può mostrare la CPU del renderer e indicatori indiretti del rendering Android quando sono disponibili root o l'helper, ma non può dichiarare la latenza effettiva delle interazioni di Companion.

Il valore del thread principale per gli eventi di stato inizia quando viene inviato un messaggio pertinente di Home Assistant e termina quando il browser cede l'esecuzione dopo tutti i gestori e i microtask. Include il modesto costo dell'osservatore di ha-paneld e qualsiasi altro lavoro eseguito nell'attività di quel messaggio; per questo, deliberatamente, non viene etichettato come tempo di elaborazione esclusivo di Home Assistant. La velocità dei payload si riferisce ai dati JSON non compressi dell'applicazione gestiti dal frontend, non al traffico di rete compresso.

Usa il grafico allineato per cercare correlazioni. I picchi di interazione che coincidono con un traffico di stati elevato e un'elevata occupazione dovuta agli eventi di stato indicano il flusso massivo di eventi. Un tempo di gestione lento con un flusso poco attivo indica il JavaScript della dashboard. Un ritardo di presentazione e fotogrammi di rendering lunghi indicano attività di layout, animazione, telecamera o contenuti multimediali. Ricaricamenti ripetuti del renderer indicano problemi di memoria o instabilità del renderer. Considera le misurazioni nel loro insieme: una singola istantanea della CPU non può identificare la causa da sola.

La riga **Causa probabile** applica automaticamente queste regole conservative e indica il relativo livello di attendibilità. Segnala intenzionalmente che non esiste una causa dominante chiara quando le evidenze non ne supportano una.

### Confronto A/B fuori banda tra modalità filtrata e non filtrata

Se la dashboard non filtrata sovraccarica il proprio WebView, i valori di rendering nella pagina potrebbero smettere di cambiare. Il raccoglitore sul lato host interroga invece periodicamente l'API delle prestazioni gestita dal servizio di ha-paneld, così la CPU dell'intero pannello rimane osservabile e, dove sono disponibili root o l'helper, lo stesso vale per il controllo nativo del thread del renderer di Chromium. Registra inoltre l'età dell'ultimo batch di telemetria del browser; un valore superiore a 15 secondi viene segnalato come bloccato, anziché considerare silenziosamente attuale un vecchio campione del browser.

The collector is read-only with respect to user configuration. It never enables, disables or rewrites the entity filter; the first binding request may create one private installation key in ha-paneld's internal state. Select the same dashboard view and workload for both arms, change the filter through the panel UI, wait for the dashboard reload, then run one command from a repository checkout for each state:

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect filtered --label filtered --output filtered.json
```

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect unfiltered --label unfiltered --pair-with filtered.json --output unfiltered.json
```

Confronta i due risultati delimitati e leggibili dalla macchina:

```bash
python3 scripts/measure-dashboard-performance.py compare filtered.json unfiltered.json --output comparison.json
```

Each arm defaults to three minutes with a 30-second warm-up and 10-second polling. `--pair-with` reuses an opaque comparison id from the first result, allowing the comparison to reject a different physical panel, configured Home Assistant/dashboard target or measurement-relevant setting without recording those private values. It cannot detect a different view selected manually within the same configured dashboard, so keep the visible view and workload unchanged. The collector rejects the arm if the expected mode is not active, the filter revision or renderer generation changes, the build/configuration changes during collection, filtering falls back, or it retains fewer than three samples, fewer than three whole-panel CPU samples or fewer than three native renderer-main CPU samples. It still writes the invalid result and the exact validation reasons. Existing output files are never overwritten.

The JSON intentionally omits the panel URL and id, Home Assistant URL and dashboard path, entity ids, process names and filter hash. The panel computes the opaque fingerprints with a private random 256-bit installation key that never leaves the panel; fingerprints are unique to that comparison pair, so separate measurements cannot be correlated through them or used to guess room-style panel names. A panel that cannot persist this key refuses the measurement binding. The unfiltered entity count is the last synchronized catalog-backed count, not a live count recovered from the overloaded WebView; it is reported as unavailable when the catalog is empty. Network and browser traffic counters are normalized to their actual observed intervals, so a tolerated missed poll cannot skew the comparison. Browser-derived state/render timing and browser traffic rates are omitted from summaries when stale, while service-side CPU, memory, network and renderer-main values continue to be collected.

### Debug remoto WebView facoltativo

Le normali schede delle prestazioni integrate non richiedono DevTools. Usa la scheda **Debug remoto WebView** solo quando è necessaria un'analisi più approfondita a livello di codice sorgente.

For the Companion app, first enable **Companion → Settings → Troubleshooting → WebView remote debugging**, then relaunch the dashboard. This setting is easy to miss: without it, the relay cannot discover the Companion WebView. The LAN relay itself also requires root.

### Escludere il difetto legacy del watchdog Zigbee del firmware originale di NSPanel Pro

Legacy stock firmware containing a recursive `export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}` assignment can make the vendor's `guard_process.sh` the performance problem itself. A [community investigation](https://github.com/maxlyth/ha-paneld/issues/34) confirmed the defect on an NSPanel Pro 120 running stock 3.8.0 and reported the condition across all 16 panels in that fleet. The script prepends its directory every five seconds; after roughly ten hours in that setup the environment string crosses Linux's per-string execution limit, external commands start failing with `E2BIG`, `sleep` stops delaying the loop and the watchdog can pin one CPU core. At that point it can also fail to restart a dead `zgateway`, leaving Zigbee unavailable. Broader exposure across legacy 1.x–3.x firmware is plausible where the same line exists, but has not been independently verified by the ha-paneld project.

Cerca questo schema quando ha-paneld segnala un carico di sistema periodico su un NSPanel Pro con firmware originale altrimenti inattivo:

- `guard_process.sh` stays near 100% of one CPU core and its process size grows far above the reported healthy value of about 9 MB;
- `zgateway` is absent or no longer recovers; and
- rebooting helps, but the load returns around ten hours later in the reported stock setup.

A reboot only resets the accumulating environment temporarily. Issue #34 contains a reporter-provided root/ADB workaround, but the project has not yet independently validated that mutation and recovery sequence. Do not apply it unless the exact recursive assignment is present once in the vendor-native script. Any repair must first verify a non-empty backup and preserve ownership, mode and SELinux metadata. Abort before mutation on an unexpected match; after mutation, roll back if restart or verification fails, return `/vendor` to read-only, and verify both `zgateway` and its availability topic. A firmware update that rewrites `/vendor` removes the local patch; the defect returns only if the target firmware still contains the vulnerable assignment. Community inspection of firmware 4.0.12 and 4.6.0 did not find it.

This watchdog defect is distinct from a stable `zgateway` busy-looping against an unresponsive radio. ha-paneld's Zigbee health sensor distinguishes the guard and gateway CPU, join evidence and restart history. It warns about the exact recursive assignment but does not edit vendor scripts automatically. A configured, explicitly unjoined gateway that remains above 50% of one core for five minute-level samples after its 15-minute grace is automatically switched OFF and contained; a joined high-CPU router is warning-only.

## Correzioni, in ordine di impatto

### 1. Filtrare la sottoscrizione alle entità del renderer integrato

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

Advanced testers can supply and inspect an exact list through the API. The UI workflow, manual API format, runtime status and rollback commands are documented in [The built-in dashboard renderer](built-in-renderer.md#filtro-entità-sperimentale).

### 2. Alleggerire la dashboard stessa

- Suddividi una dashboard densa in viste mirate ed evita di caricare schede che non servono mai al pannello.
- Preferisci le schede integrate quando una scheda personalizzata esegue animazioni continue, crea un albero del documento di grandi dimensioni o esegue spesso operazioni JavaScript.
- Watch interaction processing, long animation frames and renderer reloads. If one view repeatedly dominates them, simplify it or use `button.<panel>_reload` as a temporary recovery path while finding the expensive card.
- Verifica separatamente le schede delle videocamere e dei grafici. I relativi costi di decodifica, interrogazione della cronologia e rendering possono essere predominanti anche quando il filtro entità funziona correttamente.

### 3. Ridurre gli aggiornamenti non necessari alla fonte

Entity filtering protects the panel from unrelated entities, but it does not make a required entity cheaper. If a dashboard really displays a power meter, BLE distance sensor, rapidly changing template or noisy diagnostic entity, reduce that source's update rate where the integration supports it. This can also reduce recorder and database work for the whole Home Assistant installation.

Useful controls include ESPHome throttling or delta filters, Zigbee reporting intervals, integration `scan_interval` settings and less frequent template updates. Confirm the change does not make an automation or history view less useful before applying it globally.

### 4. Adattare la dashboard rimanente all'hardware

I pannelli PX30 e rk3566 possono eseguire bene una dashboard mirata, ma hanno comunque prestazioni limitate in thread singolo e in genere solo 2 GB di RAM. Il filtro entità elimina l'elaborazione non necessaria degli stati, ma non azzera il carico di un flusso video sovradimensionato, di un'animazione complessa o di un grafico della cronologia molto grande. Progetta in base alle dimensioni logiche dello schermo del pannello e verifica la vista più pesante, anziché valutare soltanto la scheda iniziale.

## Lista di controllo

- [ ] The built-in renderer is selected where Assist voice control and native notifications are not required
- [ ] Automatic dashboard entity filtering is enabled, scanned, reviewed and explicitly applied
- [ ] Every dashboard tab, pop-up and conditional path has been exercised during learning
- [ ] Custom-card and template dependencies are pinned or otherwise accounted for
- [ ] Entity-filter checks have been resolved deliberately rather than ignored accidentally
- [ ] The same views have been compared before and after filtering using the performance cards on the Dashboard tab
- [ ] If the page itself stalls unfiltered, the two arms have been collected and validated with the out-of-band measurement script
- [ ] Le schede pesanti, i flussi delle telecamere e i grafici sono stati testati separatamente
- [ ] Required high-frequency entities have been tuned at the source where appropriate
- [ ] If the panel uses a legacy stock NSPanel Pro Zigbee stack, its `guard_process.sh` has been checked
- [ ] A reload and filter-disable recovery path has been verified

## Ciò che il filtro integrato ha sostituito

Before ha-paneld could filter its own subscription, one deployment used a dedicated dashboard-only Home Assistant instance fed through the `remote_homeassistant` integration. It reduced the panel-facing feed from about 3,410 entities and 7.3 updates per second to about 310 entities and 0.77 updates per second, making the dashboards usable. It also required a second Home Assistant installation, bridged entities, separate configuration, authentication, updates, backups, monitoring and another failure path.

The built-in filter addresses that panel-load problem inside ha-paneld, so the split-instance system is no longer needed or maintained in that deployment and is not recommended for built-in-renderer users. This comparison remains here to show the amount of infrastructure the integrated solution replaces. Users who must retain the Companion app or another renderer cannot use ha-paneld's filter on that renderer; source-side tuning still applies, while any external filtering arrangement remains outside ha-paneld's supported setup.

---

## Riferimenti

### Perché un'installazione Home Assistant di grandi dimensioni può rallentare un pannello

Il frontend di Home Assistant mantiene una sottoscrizione WebSocket contenente lo stato corrente e gli aggiornamenti successivi delle entità disponibili per l'utente. Senza un insieme limitato di entità, il renderer riceve molti più dati di quanti ne usi normalmente una dashboard da parete dedicata. Il relativo thread principale JavaScript deve analizzare i messaggi, aggiornare il modello di stato del frontend e determinare se sia cambiato qualcosa di visibile.

I pannelli a basso costo sono particolarmente sensibili perché l'esecuzione di JavaScript, il layout e il disegno dipendono fortemente da un singolo thread del renderer. I core CPU aggiuntivi agevolano le altre operazioni, ma non eliminano tale latenza; inoltre, la RAM limitata rende la garbage collection sempre più invasiva con la crescita dell'heap della WebView.

### Sintomi comuni

| Sintomo | Causa probabile |
| --- | --- |
| Tocchi recepiti in ritardo, navigazione lenta o scorrimento a scatti | carico sul thread principale del renderer dovuto a un flusso elevato di entità, a schede pesanti o a entrambi |
| Una vista della dashboard funziona molto peggio delle altre | schede onerose, decodifica delle telecamere, dati della cronologia o un albero del documento di grandi dimensioni in quella vista |
| L'intera vista diventa vuota mentre l'interfaccia esterna rimane visibile | pressione sull'heap di WebView o errore del renderer |
| La dashboard si degrada gradualmente nel corso dei giorni | crescita dell'heap, frammentazione della memoria o una scheda che accumula operazioni |
| Gli aggiornamenti delle entità si interrompono e in seguito arrivano a raffica | interruzione di WebSocket, riconnessione o renderer che non riesce a tenere il passo |
| Pannelli simili si comportano in modo diverso | contenuti diversi della dashboard, sottoscrizione delle entità, versione di WebView, tempo di attività o stato termico |
| Legacy stock NSPanel Pro becomes janky around ten hours after boot and `guard_process.sh` uses one core | check for the recursive vendor Zigbee-watchdog assignment leading to `E2BIG` |

La garbage collection sospende periodicamente JavaScript per recuperare la memoria inutilizzata. In prossimità del limite dell'heap, queste pause diventano più lunghe e frequenti e possono impedire al rendering di ricevere risorse sufficienti anche se il processo Android non si è arrestato in modo anomalo.
