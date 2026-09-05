> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../../README.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../../app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="../../app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![Versione](https://img.shields.io/github/v/release/maxlyth/ha-paneld?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/maxlyth/ha-paneld/releases)
[![Licenza](https://assets.ha-paneld.com/docs/badge/license-apache-2-0-8aa187e4.svg)](../../LICENSE)

<!-- docs-i18n-language-picker:start -->
[English](../../README.md) · [Deutsch](../de/README.md) · [Français](../fr/README.md) · **Italiano** · [Español](../es/README.md) · [简体中文](../zh-Hans/README.md)
<!-- docs-i18n-language-picker:end -->

**L'app universale per le dashboard di Home Assistant sui pannelli a parete Android.**

ha-paneld rende pratiche le dashboard di Home Assistant sui pannelli che altrimenti risulterebbero troppo lenti o scomodi da usare. I pannelli meno potenti possono rallentare o impiegare alcuni secondi per rispondere quando sono connessi a un'installazione Home Assistant di grandi dimensioni. Una causa importante è che il pannello riceve ed elabora gli aggiornamenti di molte più entità di quante ne mostri la dashboard. **Il renderer integrato di ha-paneld può individuare quali entità usa la dashboard e chiedere a Home Assistant di inviare solo i relativi stati**. Nell'uso reale, questo può ridurre il carico delle entità di 10–100×, rendendo finalmente utilizzabile la dashboard.

ha-paneld offre inoltre un insieme coerente di controlli in Home Assistant per pannelli a parete di marche diverse. A seconda dell'hardware, possono includere schermo, LED, pulsanti, sensori, relè e audio. Il rilevamento MQTT aggiunge i controlli disponibili senza richiedere YAML specifico per dispositivo, mentre il programma di installazione gestisce la configurazione di Android.

Questa app è destinata ai pannelli a parete dedicati, non agli smartphone personali. Il supporto hardware è descritto tramite normali profili YAML, quindi proprietari e produttori possono aggiungere un altro pannello senza ricompilare l'app.

L'interfaccia web offre un unico posto in cui configurare un pannello, installare il software e scoprire cosa non ha funzionato. I suoi strumenti per le prestazioni misurano il tempo di risposta della dashboard, i ricaricamenti imprevisti, il carico di CPU e GPU, la frequenza di clock, la temperatura e i processi più attivi. Il programma di installazione offre lo stesso percorso di configurazione e aggiornamento per un insieme eterogeneo di pannelli, mentre il launcher integrato e la navigazione su schermo rendono pratico l'uso dei pannelli privi di tasti hardware.

<picture>
  <source media="(prefers-color-scheme: light)" srcset="https://assets.ha-paneld.com/docs/screenshot/hero-light-a17f5f14.webp">
  <img src="https://assets.ha-paneld.com/docs/screenshot/hero-dark-aeb93099.webp" alt="Dashboard di ha-paneld che mostra in tempo reale lo stato del pannello, le prestazioni e i controlli dello schermo">
</picture>

<details>
<summary><strong>Altre schermate</strong></summary>

| Dashboard | Configura |
|---|---|
| <a href="../img/ui-dashboard-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-dashboard-light.png"><img src="../img/ui-dashboard-dark.png" alt="Scheda Dashboard" width="420"></picture></a> | <a href="../img/ui-configure-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-configure-light.png"><img src="../img/ui-configure-dark.png" alt="Scheda Configura" width="420"></picture></a> |

| Entità | Installa |
|---|---|
| <a href="../img/ui-entities-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-entities-light.png"><img src="../img/ui-entities-dark.png" alt="Scheda Entità" width="420"></picture></a> | <a href="../img/ui-install-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-install-light.png"><img src="../img/ui-install-dark.png" alt="Scheda Installa" width="420"></picture></a> |

| Profilo | Registri |
|---|---|
| <a href="../img/ui-profile-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-profile-light.png"><img src="../img/ui-profile-dark.png" alt="Scheda Profilo" width="420"></picture></a> | <a href="../img/ui-logs-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-logs-light.png"><img src="../img/ui-logs-dark.png" alt="Scheda Registri" width="420"></picture></a> |

| Schermata di attesa | Esplora API REST |
|---|---|
| <img src="../img/standing-screen.png" alt="Schermata di attesa di ha-paneld con l'indirizzo di configurazione e il codice QR" width="420"> | <picture><source media="(prefers-color-scheme: light)" srcset="../img/api-explorer-light.png"><img src="../img/api-explorer-dark.png" alt="Esplora API REST" width="420"></picture> |

</details>

## Installazione

Se non sai se ha-paneld può essere eseguito sul tuo pannello, consulta [Pannelli e stato del supporto](#pannelli-e-stato-del-supporto) prima dell'installazione.

Per prima cosa, rendi ADB disponibile in rete. Su alcuni pannelli questa opzione si trova nelle Opzioni sviluppatore; altri richiedono una connessione USB una tantum per eseguire `adb tcpip 5555`. La [guida al provisioning](provisioning.md) e le [guide hardware](../hardware/) specifiche per modello illustrano i metodi disponibili. Quindi esegui quanto segue da un computer con `adb` connesso alla stessa rete:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

> [!IMPORTANT]
> **In Windows, usa Git Bash o WSL, non PowerShell.** Il programma di installazione è uno script `bash`. Git Bash è incluso in [Git for Windows](https://gitforwindows.org/). Installa `adb` con `winget install Google.PlatformTools`, riapri la shell e quindi esegui il comando. macOS e Linux possono eseguirlo così com'è.

Non è necessario clonare il repository né specificare alcuna opzione. Il programma di installazione verifica che `adb` e `curl` siano disponibili, richiede l'indirizzo del pannello e spiega ogni modifica prima di applicarla. Scarica l'ultima versione stabile firmata, la installa e verifica che ha-paneld si sia avviato correttamente.

Se un passaggio obbligatorio non riesce, il programma di installazione indica il problema e termina senza dichiarare che l'installazione è riuscita. Correggi il problema ed esegui nuovamente lo stesso comando.

> [!IMPORTANT]
> **Controlla Home Assistant e la WebView di sistema del pannello prima di caricare la dashboard per la prima volta.** Il renderer integrato richiede Home Assistant 2026.4.2 o versioni successive e una WebView moderna. Anche un pannello nuovo può contenere una WebView troppo vecchia per visualizzare una dashboard attuale. Consulta [Requisiti del renderer integrato](built-in-renderer.md#requisiti-e-compatibilità) e [Aggiornamento della WebView di sistema](../hardware/README.md#updating-the-system-webview).

Per seguire la versione pubblicata più recente, incluse le release candidate, aggiungi `--prerelease`. Una versione stabile più recente ha comunque la precedenza:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
```

Lo stesso programma di installazione supporta il provisioning non presidiato di un singolo pannello. Consulta [Provisioning e aggiornamenti del parco dispositivi](provisioning.md) per installazioni tramite script, bootstrap USB, pannelli senza ADB di rete e aggiornamenti dell'intero parco dispositivi.

ha-paneld non viene distribuito tramite Google Play, quindi l'installazione richiede sempre il sideloading. Questo vale anche per i pannelli più recenti che altrimenti hanno accesso al Play Store.

### Altri metodi di installazione

- **F-Droid sul pannello:** aggiungi il [repository F-Droid di ha-paneld](../fdroid.md) per installare e aggiornare le versioni stabili senza un computer. F-Droid ti avvisa quando è disponibile un aggiornamento e ti consente di installarlo sul pannello; le release candidate non sono incluse. Il firmware Sonoff NSPanel Pro 4.0.0 e versioni successive include F-Droid. Questo installa l'app, ma le funzionalità che richiedono l'accesso root necessitano comunque dei normali passaggi di provisioning.
- **Sideloading manuale o bootstrap USB:** usa l'APK dell'[ultima versione](https://github.com/maxlyth/ha-paneld/releases) e segui [Provisioning e aggiornamenti del parco dispositivi](provisioning.md) per le autorizzazioni e la configurazione rimanenti.

## Scegli come eseguire la dashboard

Usa il renderer integrato quando desideri filtrare le entità della dashboard. Supporta inoltre l'accesso da un altro browser, la selezione di una specifica scheda della dashboard e un avvio e un ripristino più rapidi. Dopo il riavvio dell'app, può riaprire l'ultima dashboard predefinita dell'account verificata mentre aggiorna in background l'elenco delle dashboard di Home Assistant.

È supportata anche l'app ufficiale [Home Assistant Companion](https://github.com/home-assistant/android). Usala quando il pannello richiede più di un server Home Assistant, il controllo vocale Assist o le notifiche native. Su un pannello senza Google Play e con un metodo di installazione supportato, usa la scheda Installa di ha-paneld. Il selettore applica il limite di compatibilità per quel pannello, anziché presupporre che possa eseguire la versione più recente di Companion.

Entrambe le opzioni rimangono supportate. Il filtro delle entità della dashboard funziona solo con il renderer integrato di ha-paneld.

<a id="panels-and-support-status"></a>

## Pannelli e stato del supporto

Non è necessario installare ha-paneld come app di sistema. I controlli Android di base, come luminosità, navigazione e TTS, funzionano sui pannelli compatibili. LED, relè, spegnimento effettivo dello schermo e alcuni sensori richiedono il supporto per quel modello nel relativo [profilo del pannello](../profiles/README.md). Gli eventi dei pulsanti hardware richiedono l'acquisizione tramite Accessibilità Android o un metodo verificato del profilo.

| Pannello | Stato | Android / ABI | Note |
|---|---|---|---|
| Sonoff NSPanel Pro / Pro 120 | Supportato | Android 8.1, arm64-v8a | PX30 / rk3326-S; il firmware stock fornisce ADB root e il normale provisioning installa l'helper root autenticato di ha-paneld |
| Tuya TPA10 | Supportato | Android 11, armeabi-v7a | rk3566 con userspace a 32 bit |
| Electron WF1589T | Supportato | Android 14, arm64-v8a | firmware userdebug rk3576; `adb root`, barra di navigazione Android nativa e controllo del LED RGB |
| ZHICAI SMT1019 | Testato dalla community, alcune funzionalità sono sperimentali | Android 14, arm64-v8a | rk3576; il firmware di serie non dispone di root accessibile dalle app. L'helper autenticato può fornire ulteriore accesso all'hardware, se installato. L'accuratezza delle misurazioni di temperatura e umidità e il supporto del sensore di prossimità richiedono ancora ulteriori test sull'hardware. [Issue #8](https://github.com/maxlyth/ha-paneld/issues/8) |
| ZX-SMT156 / RK3566_T | Preliminare | Android 13, arm64-v8a | Il LED RGB e i sensori di luminosità e prossimità funzionano senza root. Il supporto dei sensori di temperatura e umidità è facoltativo; i relè e l'accesso root sono ancora in fase di caratterizzazione. [Issue #24](https://github.com/maxlyth/ha-paneld/issues/24) |
| Smatek S9E | Sperimentale | Android 11, arm64-v8a | Profilo per i relè integrati, i LED dei pulsanti e il sensore di prossimità. È ancora necessaria una conferma dal vivo sull'hardware S9E. |
| Shelly Wall Display (originale) | Software di serie incompatibile | Android 7.0, armeabi-v7a | La versione di Android è precedente alla versione minima richiesta da ha-paneld. |
| Shelly Wall Display X2 | Solo per ricerca | Android 8.1, armeabi-v7a | Nessun metodo confermato per installare ha-paneld. |
| Shelly Wall Display X1i / X2i / XL | Solo ricerca | Android 11, arm64-v8a | I metadati del profilo devono ancora essere suddivisi per modello. Non esiste un percorso di installazione confermato per ha-paneld. |

Consulta la [documentazione hardware](../hardware/) per la configurazione specifica del modello, le limitazioni note e i dettagli hardware ottenuti tramite reverse engineering.

## Funzionalità di controllo hardware

Ogni pannello espone solo i controlli supportati dal proprio profilo e dall'hardware rilevato. I relativi nomi e comportamenti rimangono coerenti tra i modelli.

| Funzionalità | Controllo tramite Home Assistant o API |
|---|---|
| Luminosità dello schermo | `light.<panel>_screen` luminosità |
| Accensione/spegnimento dello schermo | `light.<panel>_screen` accensione/spegnimento; spegnimento effettivo dello schermo se supportato dal profilo, altrimenti riduzione sicura della luminosità |
| LED RGB | `light.<panel>_led` sui pannelli con hardware LED supportato |
| Pulsanti hardware | `event.<panel>_button` quando è disponibile l'acquisizione tramite Accessibilità Android o un metodo verificato del profilo |
| Luce ambientale e prossimità | `sensor.<panel>_illuminance`, `binary_sensor.<panel>_proximity` e un valore normalizzato `sensor.<panel>_proximity_level` da 0 (lontano) a 100 (vicino) |
| Luminosità adattiva | Apprendimento opzionale di sette giorni dal sensore di luminosità del pannello o da un'entità di illuminamento di Home Assistant |
| Aprire un URL | `text.<panel>_navigate` |
| Controlli della dashboard e riavvio | Pulsanti di Home Assistant, oltre alle azioni Dashboard, Ricarica e alle azioni di navigazione disponibili nel riquadro Comandi dell'interfaccia remota |
| Audio TTS e degli annunci | `POST /play` e `number.<panel>_volume`; consultare la [guida TTS](../tts.md) |
| Screenshot della dashboard e tocco remoto | I pannelli con un metodo di acquisizione degli screenshot supportato possono mostrare e aggiornare lo schermo dalla scheda Dashboard; la modalità Permissiva consente anche di inviare un clic al pannello |
| Informazioni e configurazione del pannello | Aprire `http://<panel>:8888/`, disponibile anche tramite il collegamento **Visita** nella pagina del dispositivo di Home Assistant |

Home Assistant rileva questi controlli tramite MQTT senza YAML. Le principali famiglie di entità, l'API HTTP e i dettagli sull'associazione sono disponibili in [docs/api.md](../api.md). È inoltre possibile esplorare e provare l'API HTTP su un pannello all'indirizzo `http://<panel>:8888/api`.

## Sicurezza e accesso root

### Modalità protetta

La modalità permissiva è quella predefinita ed è destinata a una rete domestica attendibile. Usa la [modalità protetta](../security-mode.md) quando la rete è condivisa con dispositivi meno attendibili. La modalità protetta richiede l'accesso fisico al pannello. Qualcuno deve approvare sullo schermo del pannello le azioni remote ad alto impatto; non è possibile approvarle da remoto. Gli screenshot rimangono visualizzabili, ma i tocchi remoti sono disabilitati. L'impostazione deve essere abilitata separatamente su ogni pannello e non viene copiata tramite backup, ripristino o provisioning del parco dispositivi.

### Funzionalità che richiedono root

Alcuni componenti hardware del pannello sono nascosti alle normali app Android e richiedono quindi l'accesso root. La disponibilità di root dipende dal firmware del pannello, non da ha-paneld. Alcuni pannelli espongono `su`; sugli altri, il programma di installazione può aggiungere il piccolo helper root di ha-paneld. L'helper non fornisce una shell generica né accesso illimitato ai file.

L'interfaccia web contrassegna con un lucchetto i controlli non disponibili e spiega che cosa manca al pannello. Anche il programma di installazione e la diagnostica indicano il livello di accesso disponibile.

**Root non necessario:** associazione con Home Assistant, luminosità e attenuazione dello schermo, annunci audio, entrambe le opzioni per la dashboard, interfaccia web, API REST, backup e ripristino della configurazione. Indietro, App recenti, riattivazione con un gesto della mano e barra di navigazione software dipendono dalla funzionalità Android o del sensore corrispondente, ma non richiedono intrinsecamente root.

**Potrebbero essere necessari root o l'helper autenticato:** spegnimento fisico della retroilluminazione, sospensione di Android quando selezionata dal profilo, controllo del LED RGB su alcuni pannelli, controllo dell'app del fornitore, riavvio e governor della CPU. Se il profilo attivo non dispone di un metodo sicuro per spegnere completamente lo schermo, ha-paneld ne riduce invece la luminosità.

**L'uso diretto di `su` all'interno di ha-paneld è ancora necessario:** blocco di Android sulla dashboard, log di sistema completi, controllo dei relè quando richiesto dal profilo e percorso legacy per l'importazione della sessione Companion. Un backup completo può includere un accesso Companion esistente, che passa sempre attraverso l'helper autenticato: il protocollo limitato ai descrittori è l'unico percorso, anche sui pannelli con root diretto.

Per i pannelli realmente senza root esiste una [soluzione di ripiego avanzata](provisioning.md#shizuku-come-soluzione-di-ripiego-per-i-pannelli-senza-root) limitata, che tuttavia non fa parte del normale percorso per l'hardware supportato e non offre le funzionalità hardware disponibili solo con il root.

## Guide e riferimenti

### Utilizzo di ha-paneld

- [Provisioning e aggiornamenti del parco dispositivi](provisioning.md): installazione automatica, configurazione di ADB tramite USB e rete, backup e aggiornamenti dell'intero parco dispositivi.
- [Renderer integrato](built-in-renderer.md): requisiti, login remoto, selezione della dashboard, ripristino e limitazioni intenzionali.
- [Prestazioni](../performance.md): scopri perché una dashboard è lenta e misura l'effetto del filtraggio delle entità.
- [Luminosità adattiva](../adaptive-brightness.md): seleziona una sorgente luminosa, comprendi il processo di apprendimento e reimposta la cronologia dopo aver spostato un pannello.
- [Prossimità adattiva e riattivazione con un gesto della mano](../adaptive-proximity.md): configura il rilevamento di prossimità e insegna il gesto di riattivazione.
- [Modalità di sicurezza](../security-mode.md): comprendi la modalità permissiva e la modalità protetta, incluse le azioni che richiedono la presenza di una persona davanti al pannello.
- [TTS](../tts.md): genera l'audio vocale con un motore TTS di Home Assistant e invialo a un pannello.

### Sviluppo ed estensione di ha-paneld

- [API HTTP, MQTT e Home Assistant](../api.md): endpoint HTTP, principali famiglie di entità MQTT, associazione e rilevamento. La specifica leggibile dalle macchine è disponibile su un pannello all'indirizzo `/api/v1/openapi.json`.
- [Profili dei pannelli](../profiles/): crea, testa e condividi il supporto per un altro pannello senza ricompilare l'app.
- [Riferimenti hardware](../hardware/): configurazione specifica per modello, sensori, controlli, firmware e note sul reverse engineering.
- [Compilazione dal codice sorgente](../building.md) e [sviluppo locale](../local-builds.md): compila con Docker, il container di sviluppo o una toolchain Android locale.
- [Roadmap](../roadmap.md): attività pianificate. Le attività completate sono registrate nel [changelog](../../CHANGELOG.md).

La pagina `GET /diag` del pannello genera un report su hardware, firmware e funzionalità da allegare alle segnalazioni di bug. Controllalo e oscura i dati sensibili prima di pubblicarlo.

## Altre app per modalità kiosk

### Fully Kiosk

Non consiglio di eseguire [Fully Kiosk Browser](https://www.fully-kiosk.com/) e ha-paneld insieme. Entrambi tenterebbero di gestire lo schermo, il comportamento kiosk e i controlli remoti, creando due punti in cui configurare lo stesso pannello.

<details>
<summary>Perché non consiglio di eseguirli entrambi</summary>

- Fully Kiosk è un software commerciale closed source. Le sue funzionalità di amministrazione remota richiedono una [licenza a pagamento per ogni dispositivo](https://license.fully-kiosk.com/license/single).
- Il filtro delle entità fa parte del renderer integrato di ha-paneld, quindi un browser separato non può utilizzarlo.
- Fully Kiosk viene configurato separatamente su ciascun dispositivo, il che diventa scomodo quando pannelli di marche diverse devono comportarsi in modo coerente.

Usa una sola app per dashboard sul pannello: il renderer integrato di ha-paneld, Companion oppure un browser kiosk separato, se offre qualcosa che gli altri due non offrono.

</details>

### FreeKiosk

[FreeKiosk](https://github.com/RushB-fr/freekiosk) non è correlato a ha-paneld, nonostante il nome simile. È gratuito e open source, ma utilizza React Native e quindi esegue un altro motore JavaScript insieme alla dashboard di Home Assistant. Questo carico aggiuntivo può essere significativo sui pannelli con appena 1–2 GB di RAM.

## Chat della community

Non volevo configurare un server Discord o uno spazio di lavoro Slack per un progetto gestito da una sola persona, quindi sto sperimentando con Matrix ed Element. Unisciti a [#ha-paneld:matrix.org](https://matrix.to/#/#ha-paneld:matrix.org) nel tuo client Matrix abituale oppure visualizzalo senza un account in [Element Web](https://app.element.io/#/room/#ha-paneld:matrix.org).

Non pubblicare configurazioni o link a file nelle issue o nelle discussioni di GitHub, a meno che tu non accetti che rimangano pubblici per sempre. Anche la stanza Matrix è pubblica e leggibile da chiunque, ma Matrix supporta anche messaggi diretti privati per i dettagli di assistenza che non devono entrare a far parte di un registro pubblico permanente. Oscura credenziali, URL privati e dati personali prima di pubblicare configurazioni, log o link a file ovunque.

## Vuoi che il tuo pannello sia supportato?

ha-paneld non ha un pulsante per le donazioni. È gratuito e il "pagamento" che lo fa davvero progredire consiste nel supportare più pannelli. Per farlo serve hardware da studiare.

Inizia con la [guida ai profili di runtime](../profiles/README.md). Il profilo Generic può produrre una bozza passiva che puoi convalidare, testare e condividere senza compilare l'app. Prima che un profilo possa essere incluso in ha-paneld, ho comunque bisogno di riscontri dal dispositivo reale, in particolare per pulsanti, LED, relè e sensori.

Quindi, se vuoi contribuire:

- **Crea e condividi un profilo.** Apri `http://<panel-ip>:8888/profiles`, scarica la bozza del dispositivo Generic e segui le guide per [eseguire i test](../profiles/testing.md) e [condividere](../profiles/sharing.md). Un profilo della community può essere utile prima che sia pronto per essere distribuito con ha-paneld.
- **Apri una issue con la diagnostica del pannello.** Visita `http://<panel-ip>:8888/diag`, controlla e oscura i dati sensibili nel report, quindi incollalo in una nuova issue. È sufficiente per iniziare. Collaborerò con te per eseguire una breve serie di test su eventuali pulsanti, LED, relè o sensori che richiedono la presenza di qualcuno davanti al pannello.
- **Inviami il pannello.** Mi trovo nel Regno Unito e sono disponibile a svolgere direttamente il reverse engineering. È il modo più rapido per ottenere un supporto completo per l'hardware. Ti verrà restituito (ne ho già fin troppi); apri prima una issue, così possiamo concordare i dettagli.

Il risultato è sempre aperto: il tuo pannello diventa un profilo utilizzabile da chiunque. Questa è la donazione.

## Sviluppo

Se vuoi lavorare su ha-paneld, inizia da [CONTRIBUTING.md](../../CONTRIBUTING.md). La documentazione per sviluppatori tratta la [compilazione dai sorgenti](../building.md), le [build locali e in container di sviluppo](../local-builds.md), l'[API HTTP e MQTT](../api.md), lo [sviluppo dei profili dei pannelli](../profiles/README.md), l'[ambiente di test del browser](../../test/README.md) e il [processo di rilascio](../RELEASING.md).

Ho fornito deliberatamente informazioni sufficienti per usare il container di sviluppo incluso e creare una versione di test locale. Non inviare pull request o issue generate automaticamente senza modificarle: leggi e comprendi ogni parte del testo e del codice proposti, quindi riscrivili con parole tue. Questo progetto è gestito da una sola persona e non ho tempo di esaminare contenuti generati automaticamente e non filtrati. Sii conciso e scrivi per le persone; se hai dubbi su qualcosa, chiedi prima.

<details>
<summary><strong>Stack tecnologico</strong></summary>

- **Applicazione:** [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX](https://github.com/androidx/androidx) e [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines).
- **HTTP e WebSocket di Home Assistant:** [Ktor](https://github.com/ktorio/ktor) con i moduli CIO per server e client e il modulo WebSocket.
- **MQTT:** [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client), con il relativo client MQTT 5 e il trasporto NIO in Java puro.
- **mDNS:** [JmDNS](https://github.com/jmdns/jmdns), che annuncia `_ha-paneld._tcp` affinché le istanze di ha-paneld possano trovarsi a vicenda per il selettore multipannello. ha-paneld segnala quando tale annuncio si interrompe e non può essere ripristinato.
- **Profili di runtime:** [SnakeYAML Engine](https://github.com/snakeyaml/snakeyaml-engine) per YAML 1.2, con [CodeMirror](https://codemirror.net/) e il relativo [pacchetto del linguaggio YAML](https://github.com/codemirror/lang-yaml) nell'editor dei profili.
- **QR e logging:** [ZXing](https://github.com/zxing/zxing) per i codici QR di configurazione e [SLF4J](https://github.com/qos-ch/slf4j) per il logging di Ktor e HiveMQ tramite Logcat.

La selezione e gli aggiornamenti delle dipendenze seguono la [politica del progetto relativa alle dipendenze e alla catena di fornitura](../../SECURITY.md#dependency-and-supply-chain-policy).

</details>

## Traduzioni

Le traduzioni vengono generate e sottoposte a verifica incrociata mediante diversi servizi e modelli, tra cui EuroLLM, DeepL e OpenAI. Non sono state revisionate sistematicamente da parlanti di ciascuna lingua, pertanto il testo inglese rimane autorevole. Se una formulazione non è chiara o è errata, [apri una issue per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

## Ringraziamenti

Grazie a **Seaky** per [NSPanel Pro Tools](https://github.com/seaky/nspanel_pro_tools_apk), uno dei progetti che mi hanno ispirato ad avviare ha-paneld. ha-paneld non è una reimplementazione open source di NSPanel Pro Tools. Si è evoluto in una piattaforma per pannelli a parete molto più ampia, con un proprio renderer per dashboard, filtri delle entità, profili hardware di runtime, diagnostica e provisioning per pannelli di più marche. I due progetti ora offrono funzionalità molto diverse e non devono essere considerati intercambiabili, nemmeno su un pannello Sonoff.

## Licenza

Apache-2.0. Consulta [LICENSE](../../LICENSE).
