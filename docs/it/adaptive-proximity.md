> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../adaptive-proximity.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Prossimità adattiva e riattivazione con un gesto della mano

I sensori di prossimità dei pannelli a parete Android non condividono un'unica scala utilizzabile. Alcuni indicano una distanza, altri indicano solo due valori, la polarità può variare e le letture in stato di inattività possono differire tra unità o versioni del firmware. Anziché richiedere soglie specifiche per ogni modello, ha-paneld apprende il valore di riferimento del sensore con l'area libera, il riferimento di prossimità, la polarità e la modalità di segnalazione.

## Apprendimento e addestramento

Aprire **Configura → Presenza e riattivazione** per visualizzare la fase corrente. L'apprendimento avviene localmente e normalmente non richiede alcuna configurazione:

1. Tenersi lontani dal pannello mentre identifica il segnale in stato di inattività e il valore di riferimento dell'ambiente.
2. Utilizzare normalmente il pannello affinché gli avvicinamenti e gli allontanamenti intenzionali possano essere distinti dalle piccole oscillazioni del segnale in stato di inattività.
3. Se è utile completare più rapidamente la configurazione, selezionare **Registra un gesto della mano** ed eseguire tre gesti intenzionali quando richiesto.
4. Quando il sistema è pronto, **Prova un gesto della mano** verifica un singolo gesto senza riattivare lo schermo.

Touch-to-wake remains available throughout learning. **Forget learned proximity** deletes the evidence for that sensor and returns it to the learning journey; use it after moving the panel, changing firmware or replacing the sensor route.

## Entità di Home Assistant

Quando il modello è affidabile e il profilo attivo espone una sorgente di prossimità utilizzabile, Home Assistant riceve:

- `binary_sensor.<panel>_proximity` come stato appreso di occupazione vicino/lontano; e
- `sensor.<panel>_proximity_level` come valore da 0 a 100 normalizzato per l'intera flotta, dove 0 indica lontano e 100 indica vicino.

Le entità rimangono non disponibili finché i dati non sono sufficienti o il percorso del sensore non funziona correttamente. ha-paneld non pubblica un valore apparentemente affidabile proveniente da un modello non attendibile. I sensori che forniscono solo valori binari possono comunque rilevare l'occupazione e i gesti di riattivazione, mentre un livello normalizzato significativo viene pubblicato solo quando il segnale osservato lo consente.

## Riattivazione con un gesto della mano

**Riattivazione con un gesto della mano** riconosce un singolo movimento delimitato lontano→vicino→lontano. La presenza prolungata, l'addestramento, il test e le brevi oscillazioni del segnale in stato di inattività non riattivano lo schermo. In questo modo, una persona ferma vicino al pannello o un sensore rumoroso con letture ad alta densità non vengono interpretati come un flusso di richieste di riattivazione.

La funzionalità richiede una sorgente di prossimità attiva e un modello appreso pronto, ma la sorgente può essere un normale sensore Android oppure un percorso ausiliario selezionato dal profilo. La UI web e la diagnostica indicano il percorso effettivo e lo stato di disponibilità; la sola dichiarazione di un profilo non crea la disponibilità di un sensore.
