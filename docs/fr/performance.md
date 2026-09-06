> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../performance.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Réglage des performances des panneaux muraux Home Assistant

Ce guide explique pourquoi un panneau mural Android peu coûteux peut sembler lent, saccader pendant le défilement, afficher un écran vide ou cesser de s’actualiser alors que le même tableau de bord fonctionne bien sur un téléphone ou un ordinateur. Commencez par examiner la charge que Home Assistant envoie au panneau, puis mesurez les limites restantes du tableau de bord et du matériel au lieu de supposer que le panneau doit être remplacé.

> [!TIP]
> Une cause fréquente est le nombre d’états d’entités et de mises à jour Home Assistant qui parviennent au tableau de bord. Une installation de grande taille peut contraindre un panneau peu puissant à traiter des milliers d’entités qu’il n’affiche jamais. Avec le moteur de rendu intégré de ha-paneld, le premier correctif à essayer est le filtrage des entités.

## Mesurer avant toute modification

Ouvrez la page Web du panneau à l’adresse `http://<panel-ip>:8888/` ou utilisez le lien **Visiter** de la page de l’appareil dans Home Assistant. Enregistrez la même vue du tableau de bord pendant une durée similaire avant et après chaque modification afin que la comparaison soit pertinente.

- **Réactivité du tableau de bord** — latence réelle des interactions, décomposition de l’interaction la plus lente entre entrée, gestionnaire et présentation, blocage du thread principal, délai avant interactivité et rechargements inattendus du moteur de rendu.
- **Flux d’état Home Assistant** — mises à jour d’état par seconde, débit de la charge utile JSON non compressée, taille de l’hydratation initiale, délai avant que le thread principal ne rende la main et les trois principales entités contributrices sur l’heure glissante, classées à la fois selon le taux de mise à jour et le volume de la charge utile.
- **Performances** et **Processus principaux** — CPU, GPU, RAM, fréquence d’horloge, température et processus les plus sollicités.
- **Flux de la caméra** (uniquement sur un panneau dont le profil déclare une caméra) : les abonnés qui maintiennent la session ouverte, l’encodeur utilisé, la fréquence d’images demandée à côté de celle fournie, ainsi que le débit binaire fourni à côté de la limite de débit de l’encodeur. Une fréquence nettement inférieure à celle demandée est signalée comme telle, sans cause associée : le rythme de capture dans une pièce sombre, le chemin d’encodage et la concurrence pour le matériel vidéo sont autant de causes possibles, et la carte ne cherche pas à les départager. La demande elle-même n’est jamais réduite discrètement. Lorsque la session ne peut pas fournir à un flux la fréquence demandée, la carte affiche la demande à côté de la fréquence transmise à l’encodeur au lieu de déplacer la cible pour la faire correspondre. La fréquence de capture est définie par celui qui ouvre la caméra ; ainsi, un flux qui rejoint une session ouverte par un instantané est encodé selon la valeur par défaut configurée. La carte n’indique délibérément aucun chiffre de CPU, car un encodage matériel s’exécute dans le matériel du codec et le compositeur, ainsi que dans l’application, et aucun processus unique ne représente le coût de la caméra. Consultez la charge totale du panneau dans Performances et Processus principaux.
- **Page Entités** — éléments auxquels le moteur de rendu intégré est actuellement abonné, résultats de l’apprentissage automatique et règles du tableau de bord qui nécessitent encore une décision.
- **Rapport de diagnostic** (`/diag`) — rapport à copier-coller dans un rapport de bogue lorsque la cause reste incertaine.

Le moteur de rendu intégré collecte directement ces mesures du navigateur et ne nécessite ni root ni débogage WebView. L’application Companion reste un mode de compatibilité : ha-paneld peut afficher le CPU du moteur de rendu et des indicateurs indirects du rendu Android lorsque root ou l’assistant est disponible, mais il ne peut pas prétendre mesurer la latence réelle des interactions dans Companion.

La valeur du thread principal pour un événement d’état commence lorsqu’un message Home Assistant pertinent est distribué et se termine lorsque le navigateur rend la main après tous les gestionnaires et toutes les microtâches. Elle inclut le faible coût de l’observateur de ha-paneld ainsi que tout autre travail effectué dans la tâche de ce message ; elle n’est donc délibérément pas présentée comme un temps de traitement propre à Home Assistant uniquement. Le débit de la charge utile correspond aux données JSON non compressées de l’application traitées par l’interface, et non au trafic réseau compressé.

Utilisez le graphique aligné pour rechercher des corrélations. Des pics d’interaction qui coïncident avec un trafic d’états élevé et une forte occupation liée aux événements d’état orientent vers le flot de données. Un temps de gestionnaire élevé avec un flux calme oriente vers le JavaScript du tableau de bord. Un retard de présentation et de longues trames de rendu orientent vers la mise en page, les animations, la caméra ou les contenus multimédias. Des rechargements répétés du moteur de rendu orientent vers un problème de mémoire ou d’instabilité du moteur de rendu. Interprétez les mesures conjointement ; un instantané isolé du CPU ne permet pas d’identifier à lui seul la cause.

La ligne **Cause probable** applique automatiquement ces règles prudentes et indique son niveau de confiance. Elle précise volontairement qu’aucune cause dominante claire ne se dégage lorsque les éléments disponibles ne permettent pas d’en établir une.

### Comparaison A/B hors bande avec et sans filtrage

Si le tableau de bord sans filtrage surcharge son propre WebView, les valeurs de rendu affichées dans la page peuvent cesser d’évoluer. Le collecteur côté hôte interroge à la place l’API de performances du service de ha-paneld : le CPU de l’ensemble du panneau reste donc observable et, lorsque root ou l’assistant est disponible, la sonde native du thread de rendu Chromium l’est également. Il enregistre aussi l’âge du dernier lot de télémétrie du navigateur ; une valeur supérieure à 15 secondes est signalée comme bloquée, au lieu de traiter silencieusement comme actuel un ancien échantillon du navigateur.

The collector is read-only with respect to user configuration. It never enables, disables or rewrites the entity filter; the first binding request may create one private installation key in ha-paneld's internal state. Select the same dashboard view and workload for both arms, change the filter through the panel UI, wait for the dashboard reload, then run one command from a repository checkout for each state:

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect filtered --label filtered --output filtered.json
```

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect unfiltered --label unfiltered --pair-with filtered.json --output unfiltered.json
```

Comparez les deux résultats bornés et lisibles par machine :

```bash
python3 scripts/measure-dashboard-performance.py compare filtered.json unfiltered.json --output comparison.json
```

Each arm defaults to three minutes with a 30-second warm-up and 10-second polling. `--pair-with` reuses an opaque comparison id from the first result, allowing the comparison to reject a different physical panel, configured Home Assistant/dashboard target or measurement-relevant setting without recording those private values. It cannot detect a different view selected manually within the same configured dashboard, so keep the visible view and workload unchanged. The collector rejects the arm if the expected mode is not active, the filter revision or renderer generation changes, the build/configuration changes during collection, filtering falls back, or it retains fewer than three samples, fewer than three whole-panel CPU samples or fewer than three native renderer-main CPU samples. It still writes the invalid result and the exact validation reasons. Existing output files are never overwritten.

The JSON intentionally omits the panel URL and id, Home Assistant URL and dashboard path, entity ids, process names and filter hash. The panel computes the opaque fingerprints with a private random 256-bit installation key that never leaves the panel; fingerprints are unique to that comparison pair, so separate measurements cannot be correlated through them or used to guess room-style panel names. A panel that cannot persist this key refuses the measurement binding. The unfiltered entity count is the last synchronized catalog-backed count, not a live count recovered from the overloaded WebView; it is reported as unavailable when the catalog is empty. Network and browser traffic counters are normalized to their actual observed intervals, so a tolerated missed poll cannot skew the comparison. Browser-derived state/render timing and browser traffic rates are omitted from summaries when stale, while service-side CPU, memory, network and renderer-main values continue to be collected.

### Débogage WebView à distance facultatif

Les cartes de performances intégrées habituelles ne nécessitent pas DevTools. Utilisez la carte **Débogage WebView à distance** uniquement lorsqu’une inspection plus approfondie au niveau du code source est nécessaire.

For the Companion app, first enable **Companion → Settings → Troubleshooting → WebView remote debugging**, then relaunch the dashboard. This setting is easy to miss: without it, the relay cannot discover the Companion WebView. The LAN relay itself also requires root.

### Écarter l’ancien défaut du watchdog Zigbee du firmware d’origine du NSPanel Pro

Legacy stock firmware containing a recursive `export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}` assignment can make the vendor's `guard_process.sh` the performance problem itself. A [community investigation](https://github.com/maxlyth/ha-paneld/issues/34) confirmed the defect on an NSPanel Pro 120 running stock 3.8.0 and reported the condition across all 16 panels in that fleet. The script prepends its directory every five seconds; after roughly ten hours in that setup the environment string crosses Linux's per-string execution limit, external commands start failing with `E2BIG`, `sleep` stops delaying the loop and the watchdog can pin one CPU core. At that point it can also fail to restart a dead `zgateway`, leaving Zigbee unavailable. Broader exposure across legacy 1.x–3.x firmware is plausible where the same line exists, but has not been independently verified by the ha-paneld project.

Recherchez ce schéma lorsque ha-paneld signale une charge système périodique sur un NSPanel Pro d’origine par ailleurs inactif :

- `guard_process.sh` stays near 100% of one CPU core and its process size grows far above the reported healthy value of about 9 MB;
- `zgateway` is absent or no longer recovers; and
- rebooting helps, but the load returns around ten hours later in the reported stock setup.

A reboot only resets the accumulating environment temporarily. Issue #34 contains a reporter-provided root/ADB workaround, but the project has not yet independently validated that mutation and recovery sequence. Do not apply it unless the exact recursive assignment is present once in the vendor-native script. Any repair must first verify a non-empty backup and preserve ownership, mode and SELinux metadata. Abort before mutation on an unexpected match; after mutation, roll back if restart or verification fails, return `/vendor` to read-only, and verify both `zgateway` and its availability topic. A firmware update that rewrites `/vendor` removes the local patch; the defect returns only if the target firmware still contains the vulnerable assignment. Community inspection of firmware 4.0.12 and 4.6.0 did not find it.

This watchdog defect is distinct from a stable `zgateway` busy-looping against an unresponsive radio. ha-paneld's Zigbee health sensor distinguishes the guard and gateway CPU, join evidence and restart history. It warns about the exact recursive assignment but does not edit vendor scripts automatically. A configured, explicitly unjoined gateway that remains above 50% of one core for five minute-level samples after its 15-minute grace is automatically switched OFF and contained; a joined high-CPU router is warning-only.

## Correctifs, par ordre d’impact

### 1. Filtrer l’abonnement aux entités du moteur de rendu intégré

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

Advanced testers can supply and inspect an exact list through the API. The UI workflow, manual API format, runtime status and rollback commands are documented in [The built-in dashboard renderer](built-in-renderer.md#filtre-dentités-expérimental).

### 2. Alléger le tableau de bord lui-même

- Divisez un tableau de bord dense en vues ciblées et évitez de charger des cartes dont le panneau n’a jamais besoin.
- Préférez les cartes intégrées lorsqu’une carte personnalisée exécute des animations en continu, crée une arborescence de document volumineuse ou effectue fréquemment des opérations JavaScript.
- Watch interaction processing, long animation frames and renderer reloads. If one view repeatedly dominates them, simplify it or use `button.<panel>_reload` as a temporary recovery path while finding the expensive card.
- Testez séparément les cartes de caméra et de graphique. Leur décodage, leurs requêtes d’historique et leur coût de rendu peuvent rester prépondérants même lorsque le filtrage des entités fonctionne correctement.

### 3. Réduire à la source les mises à jour inutiles

Entity filtering protects the panel from unrelated entities, but it does not make a required entity cheaper. If a dashboard really displays a power meter, BLE distance sensor, rapidly changing template or noisy diagnostic entity, reduce that source's update rate where the integration supports it. This can also reduce recorder and database work for the whole Home Assistant installation.

Useful controls include ESPHome throttling or delta filters, Zigbee reporting intervals, integration `scan_interval` settings and less frequent template updates. Confirm the change does not make an automation or history view less useful before applying it globally.

### 4. Adapter le reste du tableau de bord au matériel

Les panneaux PX30 et rk3566 peuvent exécuter correctement un tableau de bord ciblé, mais leurs performances monothread restent limitées et ils ne disposent généralement que de 2 GB de RAM. Le filtrage des entités élimine le traitement inutile des états, mais il ne rend pas sans coût un flux de caméra surdimensionné, une animation complexe ou un très grand graphique d’historique. Concevez le tableau de bord en fonction de la taille d’affichage logique du panneau et testez la vue la plus exigeante au lieu de juger uniquement l’onglet d’accueil.

## Liste de contrôle

- [ ] The built-in renderer is selected where Assist voice control and native notifications are not required
- [ ] Automatic dashboard entity filtering is enabled, scanned, reviewed and explicitly applied
- [ ] Every dashboard tab, pop-up and conditional path has been exercised during learning
- [ ] Custom-card and template dependencies are pinned or otherwise accounted for
- [ ] Entity-filter checks have been resolved deliberately rather than ignored accidentally
- [ ] The same views have been compared before and after filtering using the performance cards on the Dashboard tab
- [ ] If the page itself stalls unfiltered, the two arms have been collected and validated with the out-of-band measurement script
- [ ] Les cartes lourdes, les flux de caméra et les graphiques ont été testés séparément
- [ ] Required high-frequency entities have been tuned at the source where appropriate
- [ ] If the panel uses a legacy stock NSPanel Pro Zigbee stack, its `guard_process.sh` has been checked
- [ ] A reload and filter-disable recovery path has been verified

## Ce que le filtre intégré a remplacé

Before ha-paneld could filter its own subscription, one deployment used a dedicated dashboard-only Home Assistant instance fed through the `remote_homeassistant` integration. It reduced the panel-facing feed from about 3,410 entities and 7.3 updates per second to about 310 entities and 0.77 updates per second, making the dashboards usable. It also required a second Home Assistant installation, bridged entities, separate configuration, authentication, updates, backups, monitoring and another failure path.

The built-in filter addresses that panel-load problem inside ha-paneld, so the split-instance system is no longer needed or maintained in that deployment and is not recommended for built-in-renderer users. This comparison remains here to show the amount of infrastructure the integrated solution replaces. Users who must retain the Companion app or another renderer cannot use ha-paneld's filter on that renderer; source-side tuning still applies, while any external filtering arrangement remains outside ha-paneld's supported setup.

---

## Référence

### Pourquoi une installation Home Assistant de grande taille peut ralentir un panneau

L'interface de Home Assistant maintient un abonnement WebSocket contenant l'état actuel et les mises à jour ultérieures des entités accessibles à l'utilisateur. Sans ensemble restreint d'entités, le moteur de rendu reçoit bien plus de données qu'un tableau de bord mural ciblé n'en utilise normalement. Son thread JavaScript principal doit analyser les messages, mettre à jour le modèle d'état de l'interface et déterminer si un élément visible a changé.

Les panneaux à bas coût sont particulièrement sensibles, car l'exécution de JavaScript, la mise en page et le rendu dépendent fortement d'un seul thread de rendu. Des cœurs CPU supplémentaires facilitent les autres tâches, mais n'éliminent pas cette latence, et une RAM limitée rend le ramasse-miettes de plus en plus perturbant à mesure que le tas de WebView augmente.

### Symptômes courants

| Symptôme | Cause probable |
| --- | --- |
| Réponse tardive aux appuis, navigation lente ou défilement saccadé | charge du thread principal du moteur de rendu due à un flux important d'entités, à des cartes lourdes ou aux deux |
| Une vue du tableau de bord est nettement moins performante que les autres | cartes gourmandes en ressources, décodage des caméras, données d’historique ou arborescence de document volumineuse dans cette vue |
| La vue entière devient vide tandis que l’interface externe reste affichée | pression sur le tas de WebView ou défaillance du moteur de rendu |
| Le tableau de bord se dégrade progressivement au fil des jours | croissance du tas, fragmentation de la mémoire ou carte qui accumule les tâches |
| Les entités cessent de se mettre à jour, puis les mises à jour arrivent en rafale | interruption de WebSocket, reconnexion ou moteur de rendu qui ne parvient pas à suivre |
| Des panneaux similaires se comportent différemment | contenu du tableau de bord, abonnement aux entités, version de WebView, durée de fonctionnement ou état thermique différents |
| Legacy stock NSPanel Pro becomes janky around ten hours after boot and `guard_process.sh` uses one core | check for the recursive vendor Zigbee-watchdog assignment leading to `E2BIG` |

Le ramasse-miettes met périodiquement JavaScript en pause pour récupérer la mémoire inutilisée. À l’approche de la limite du tas, ces pauses deviennent plus longues et plus fréquentes, ce qui peut priver le rendu de ressources même si le processus Android lui-même n’a pas planté.
