> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../performance.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Leistungsoptimierung für Home Assistant-Wandpanels

Dieser Leitfaden erklärt, warum ein preisgünstiges Android-Wandpanel träge reagieren, beim Scrollen ruckeln, einen leeren Bildschirm anzeigen oder die Aktualisierung einstellen kann, obwohl dasselbe Dashboard auf einem Smartphone oder Desktop gut funktioniert. Beginnen Sie mit der Arbeit, die Home Assistant an das Panel übermittelt, und messen Sie anschließend die verbleibenden Grenzen des Dashboards und der Hardware, statt davon auszugehen, dass das Panel ersetzt werden muss.

> [!TIP]
> Eine häufige Ursache ist die Anzahl der Entitätszustände und Aktualisierungen von Home Assistant, die das Dashboard erreichen. Eine große Installation kann dazu führen, dass ein leistungsschwaches Panel Tausende von Entitäten verarbeitet, die es nie anzeigt. Beim integrierten Renderer von ha-paneld sollten Sie zuerst die Entitätsfilterung ausprobieren.

## Vor Änderungen messen

Öffnen Sie die Webseite des Panels unter `http://<panel-ip>:8888/` oder verwenden Sie auf der Geräteseite von Home Assistant den Link **Besuchen**. Zeichnen Sie vor und nach jeder Änderung dieselbe Dashboard-Ansicht über einen ähnlich langen Zeitraum auf, damit der Vergleich aussagekräftig ist.

- **Reaktionsfähigkeit des Dashboards** — tatsächliche Interaktionslatenz, die Aufschlüsselung der langsamsten Interaktion nach Eingabe, Handler und Darstellung, Blockierung des Hauptthreads, Zeit bis zur Interaktionsbereitschaft sowie unerwartetes Neuladen des Renderers.
- **Home Assistant-Statusdatenstrom** — Zustandsaktualisierungen pro Sekunde, Rate der unkomprimierten JSON-Nutzdaten, Größe der anfänglichen Hydrierung, Zeit bis zur Freigabe des Hauptthreads und die drei Entitäten mit dem größten Beitrag innerhalb der jeweils letzten Stunde, geordnet sowohl nach Aktualisierungsrate als auch nach Nutzdatenvolumen.
- **Leistung** und **Top-Prozesse** — CPU, GPU, RAM, Taktfrequenz, Temperatur und die am stärksten ausgelasteten Prozesse.
- **Kamerastream** (nur auf einem Panel, dessen Profil eine Kamera deklariert): die Abonnenten, die die Sitzung offen halten, der verwendete Encoder, die angeforderte neben der bereitgestellten Bildrate und die bereitgestellte Bitrate neben der Bitratenobergrenze des Encoders. Eine deutlich unter der angeforderten Rate liegende Rate wird als solche gemeldet, ohne eine Ursache anzugeben: Die Taktung der Aufnahme in einem dunklen Raum, der Kodierungspfad und die Konkurrenz um die Videohardware kommen alle infrage, und die Karte trifft keine Vermutung darüber. Die Anforderung selbst wird nie stillschweigend reduziert. Wenn die Sitzung einem Stream nicht die angeforderte Rate bereitstellen kann, zeigt die Karte die Anforderung neben der Rate an, die dem Encoder zugewiesen wurde, statt den Zielwert entsprechend zu verschieben. Die Aufnahmerate wird von demjenigen festgelegt, der die Kamera öffnet. Daher wird ein Stream, der einer durch eine Momentaufnahme geöffneten Sitzung beitritt, mit dem konfigurierten Standardwert kodiert. Die Karte nennt bewusst keinen CPU-Wert, da eine Hardwarekodierung sowohl in der Codec-Hardware und im Compositor als auch in der App ausgeführt wird und kein einzelner Prozess die Gesamtkosten der Kamera darstellt. Lesen Sie die Gesamtlast des Panels unter Leistung und Top-Prozesse ab.
- **Entitätenseite** — was der integrierte Renderer derzeit abonniert, was das automatische Lernen gefunden hat und für welche Dashboard-Regeln noch eine Entscheidung erforderlich ist.
- **Diagnosebericht** (`/diag`) — ein kopierbarer Bericht für einen Fehlerbericht, wenn die Ursache weiterhin unklar ist.

Der integrierte Renderer erfasst diese Browsermesswerte direkt und benötigt weder root noch WebView-Debugging. Die Companion-App bleibt ein Kompatibilitätsmodus: ha-paneld kann die CPU-Auslastung des Renderers und Android-Rendering-Proxymesswerte anzeigen, wenn root oder das Hilfsprogramm verfügbar ist, kann jedoch keine tatsächliche Companion-Interaktionslatenz ausweisen.

Der Hauptthread-Wert für Zustandsereignisse beginnt, wenn eine relevante Home Assistant-Nachricht übermittelt wird, und endet, wenn der Browser nach allen Handlern und Mikrotasks die Ausführung freigibt. Er umfasst die geringe Beobachterlast von ha-paneld und alle weiteren Arbeiten in diesem Nachrichtentask. Daher wird er bewusst nicht als reine Verarbeitungszeit von Home Assistant bezeichnet. Die Nutzdatenrate bezieht sich auf das vom Frontend verarbeitete unkomprimierte Anwendungs-JSON, nicht auf komprimierten Netzwerkverkehr.

Suchen Sie im zeitlich ausgerichteten Diagramm nach Korrelationen. Interaktionsspitzen, die mit hohem Zustandsdatenverkehr und einer hohen Belegung durch Zustandsereignisse zusammenfallen, deuten auf die Datenflut hin. Eine lange Handler-Zeit bei einem ruhigen Datenstrom deutet auf das JavaScript des Dashboards hin. Verzögerungen bei der Darstellung und lange Rendering-Frames deuten auf Layout-, Animations-, Kamera- oder Medienvorgänge hin. Wiederholte Neustarts des Renderers deuten auf Speicherprobleme oder eine Instabilität des Renderers hin. Betrachten Sie die Messwerte gemeinsam; eine einzelne CPU-Momentaufnahme kann die Ursache nicht allein bestimmen.

Die Zeile **Wahrscheinliche Ursache** wendet diese konservativen Regeln automatisch an und gibt ihre Konfidenz an. Sie meldet bewusst, dass es keine eindeutig vorherrschende Ursache gibt, wenn die Belege keine solche Schlussfolgerung stützen.

### Out-of-band-A/B-Vergleich: gefiltert gegenüber ungefiltert

Wenn das ungefilterte Dashboard seine eigene WebView überlastet, werden die seiteninternen Rendering-Werte möglicherweise nicht mehr aktualisiert. Stattdessen fragt der hostseitige Collector die dienstinterne Leistungs-API von ha-paneld ab. Dadurch bleibt die CPU-Auslastung des gesamten Panels beobachtbar und, sofern root oder das Hilfsprogramm verfügbar ist, auch die native Messung des Chromium-Renderer-Threads. Er erfasst außerdem das Alter des letzten Browser-Telemetrieblocks. Ein Wert über 15 Sekunden wird als angehalten gemeldet, statt eine alte Browser-Messprobe stillschweigend als aktuell zu behandeln.

The collector is read-only with respect to user configuration. It never enables, disables or rewrites the entity filter; the first binding request may create one private installation key in ha-paneld's internal state. Select the same dashboard view and workload for both arms, change the filter through the panel UI, wait for the dashboard reload, then run one command from a repository checkout for each state:

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect filtered --label filtered --output filtered.json
```

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect unfiltered --label unfiltered --pair-with filtered.json --output unfiltered.json
```

Vergleichen Sie die beiden begrenzten, maschinenlesbaren Ergebnisse:

```bash
python3 scripts/measure-dashboard-performance.py compare filtered.json unfiltered.json --output comparison.json
```

Each arm defaults to three minutes with a 30-second warm-up and 10-second polling. `--pair-with` reuses an opaque comparison id from the first result, allowing the comparison to reject a different physical panel, configured Home Assistant/dashboard target or measurement-relevant setting without recording those private values. It cannot detect a different view selected manually within the same configured dashboard, so keep the visible view and workload unchanged. The collector rejects the arm if the expected mode is not active, the filter revision or renderer generation changes, the build/configuration changes during collection, filtering falls back, or it retains fewer than three samples, fewer than three whole-panel CPU samples or fewer than three native renderer-main CPU samples. It still writes the invalid result and the exact validation reasons. Existing output files are never overwritten.

The JSON intentionally omits the panel URL and id, Home Assistant URL and dashboard path, entity ids, process names and filter hash. The panel computes the opaque fingerprints with a private random 256-bit installation key that never leaves the panel; fingerprints are unique to that comparison pair, so separate measurements cannot be correlated through them or used to guess room-style panel names. A panel that cannot persist this key refuses the measurement binding. The unfiltered entity count is the last synchronized catalog-backed count, not a live count recovered from the overloaded WebView; it is reported as unavailable when the catalog is empty. Network and browser traffic counters are normalized to their actual observed intervals, so a tolerated missed poll cannot skew the comparison. Browser-derived state/render timing and browser traffic rates are omitted from summaries when stale, while service-side CPU, memory, network and renderer-main values continue to be collected.

### Optionales Remote-WebView-Debugging

Die normalen integrierten Leistungskarten benötigen keine DevTools. Verwenden Sie die Karte **Remote-WebView-Debugging** nur, wenn eine tiefergehende Untersuchung auf Quellcodeebene erforderlich ist.

For the Companion app, first enable **Companion → Settings → Troubleshooting → WebView remote debugging**, then relaunch the dashboard. This setting is easy to miss: without it, the relay cannot discover the Companion WebView. The LAN relay itself also requires root.

### Alten Zigbee-Watchdog-Defekt des serienmäßigen NSPanel Pro ausschließen

Legacy stock firmware containing a recursive `export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}` assignment can make the vendor's `guard_process.sh` the performance problem itself. A [community investigation](https://github.com/maxlyth/ha-paneld/issues/34) confirmed the defect on an NSPanel Pro 120 running stock 3.8.0 and reported the condition across all 16 panels in that fleet. The script prepends its directory every five seconds; after roughly ten hours in that setup the environment string crosses Linux's per-string execution limit, external commands start failing with `E2BIG`, `sleep` stops delaying the loop and the watchdog can pin one CPU core. At that point it can also fail to restart a dead `zgateway`, leaving Zigbee unavailable. Broader exposure across legacy 1.x–3.x firmware is plausible where the same line exists, but has not been independently verified by the ha-paneld project.

Achten Sie auf dieses Muster, wenn ha-paneld bei einem ansonsten inaktiven serienmäßigen NSPanel Pro eine periodische Systemlast meldet:

- `guard_process.sh` stays near 100% of one CPU core and its process size grows far above the reported healthy value of about 9 MB;
- `zgateway` is absent or no longer recovers; and
- rebooting helps, but the load returns around ten hours later in the reported stock setup.

A reboot only resets the accumulating environment temporarily. Issue #34 contains a reporter-provided root/ADB workaround, but the project has not yet independently validated that mutation and recovery sequence. Do not apply it unless the exact recursive assignment is present once in the vendor-native script. Any repair must first verify a non-empty backup and preserve ownership, mode and SELinux metadata. Abort before mutation on an unexpected match; after mutation, roll back if restart or verification fails, return `/vendor` to read-only, and verify both `zgateway` and its availability topic. A firmware update that rewrites `/vendor` removes the local patch; the defect returns only if the target firmware still contains the vulnerable assignment. Community inspection of firmware 4.0.12 and 4.6.0 did not find it.

This watchdog defect is distinct from a stable `zgateway` busy-looping against an unresponsive radio. ha-paneld's Zigbee health sensor distinguishes the guard and gateway CPU, join evidence and restart history. It warns about the exact recursive assignment but does not edit vendor scripts automatically. A configured, explicitly unjoined gateway that remains above 50% of one core for five minute-level samples after its 15-minute grace is automatically switched OFF and contained; a joined high-CPU router is warning-only.

## Korrekturen nach Auswirkung geordnet

### 1. Entitätsabonnement des integrierten Renderers filtern

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

Advanced testers can supply and inspect an exact list through the API. The UI workflow, manual API format, runtime status and rollback commands are documented in [The built-in dashboard renderer](built-in-renderer.md#experimenteller-entitätsfilter).

### 2. Das Dashboard selbst entlasten

- Teilen Sie ein umfangreiches Dashboard in gezielte Ansichten auf und vermeiden Sie das Laden von Karten, die das Panel nie benötigt.
- Bevorzugen Sie integrierte Karten, wenn eine benutzerdefinierte Karte kontinuierlich animiert, einen großen Dokumentbaum erzeugt oder häufig JavaScript ausführt.
- Watch interaction processing, long animation frames and renderer reloads. If one view repeatedly dominates them, simplify it or use `button.<panel>_reload` as a temporary recovery path while finding the expensive card.
- Testen Sie Kamera- und Diagrammkarten getrennt. Deren Dekodierung, Verlaufsabfragen und Rendering-Aufwand können selbst bei ordnungsgemäß funktionierender Entitätsfilterung dominieren.

### 3. Unnötige Aktualisierungen an der Quelle reduzieren

Entity filtering protects the panel from unrelated entities, but it does not make a required entity cheaper. If a dashboard really displays a power meter, BLE distance sensor, rapidly changing template or noisy diagnostic entity, reduce that source's update rate where the integration supports it. This can also reduce recorder and database work for the whole Home Assistant installation.

Useful controls include ESPHome throttling or delta filters, Zigbee reporting intervals, integration `scan_interval` settings and less frequent template updates. Confirm the change does not make an automation or history view less useful before applying it globally.

### 4. Das verbleibende Dashboard an die Hardware anpassen

PX30- und rk3566-Panels können ein fokussiertes Dashboard gut ausführen, verfügen aber weiterhin nur über eine begrenzte Single-Thread-Leistung und üblicherweise lediglich 2 GB RAM. Die Entitätsfilterung vermeidet unnötige Arbeit mit Zuständen; sie kann einen überdimensionierten Kamerastream, eine komplexe Animation oder ein sehr großes Verlaufsdiagramm jedoch nicht aufwandsfrei machen. Legen Sie das Design auf die logische Anzeigegröße des Panels aus und testen Sie die anspruchsvollste Ansicht, statt nur die Startregisterkarte zu beurteilen.

## Checkliste

- [ ] The built-in renderer is selected where Assist voice control and native notifications are not required
- [ ] Automatic dashboard entity filtering is enabled, scanned, reviewed and explicitly applied
- [ ] Every dashboard tab, pop-up and conditional path has been exercised during learning
- [ ] Custom-card and template dependencies are pinned or otherwise accounted for
- [ ] Entity-filter checks have been resolved deliberately rather than ignored accidentally
- [ ] The same views have been compared before and after filtering using the performance cards on the Dashboard tab
- [ ] If the page itself stalls unfiltered, the two arms have been collected and validated with the out-of-band measurement script
- [ ] Ressourcenintensive Karten, Kamerastreams und Diagramme wurden separat getestet
- [ ] Required high-frequency entities have been tuned at the source where appropriate
- [ ] If the panel uses a legacy stock NSPanel Pro Zigbee stack, its `guard_process.sh` has been checked
- [ ] A reload and filter-disable recovery path has been verified

## Was der integrierte Filter ersetzt hat

Before ha-paneld could filter its own subscription, one deployment used a dedicated dashboard-only Home Assistant instance fed through the `remote_homeassistant` integration. It reduced the panel-facing feed from about 3,410 entities and 7.3 updates per second to about 310 entities and 0.77 updates per second, making the dashboards usable. It also required a second Home Assistant installation, bridged entities, separate configuration, authentication, updates, backups, monitoring and another failure path.

The built-in filter addresses that panel-load problem inside ha-paneld, so the split-instance system is no longer needed or maintained in that deployment and is not recommended for built-in-renderer users. This comparison remains here to show the amount of infrastructure the integrated solution replaces. Users who must retain the Companion app or another renderer cannot use ha-paneld's filter on that renderer; source-side tuning still applies, while any external filtering arrangement remains outside ha-paneld's supported setup.

---

## Referenz

### Warum eine große Home Assistant-Installation ein Panel verlangsamen kann

Das Home Assistant-Frontend verwaltet eine WebSocket-Subscription, die den aktuellen Zustand und nachfolgende Aktualisierungen für die dem Benutzer verfügbaren Entitäten enthält. Ohne eine eingeschränkte Entitätsmenge empfängt der Renderer deutlich mehr Daten, als ein zielgerichtetes Wand-Dashboard normalerweise verwendet. Sein JavaScript-Hauptthread muss die Nachrichten parsen, das Zustandsmodell des Frontends aktualisieren und entscheiden, ob sich etwas Sichtbares geändert hat.

Kostengünstige Panels reagieren besonders empfindlich, da JavaScript-Ausführung, Layout und Darstellung stark von einem einzelnen Renderer-Thread abhängen. Zusätzliche CPU-Kerne helfen bei anderen Aufgaben, beseitigen diese Latenz jedoch nicht. Begrenzter RAM führt zudem dazu, dass die Speicherbereinigung mit wachsendem WebView-Heap zunehmend stört.

### Häufige Symptome

| Symptom | Wahrscheinliche Ursache |
| --- | --- |
| Verzögerte Tippreaktionen, träge Navigation oder ruckelndes Scrollen | Arbeit im Hauptthread des Renderers durch einen großen Entitätsdatenstrom, ressourcenintensive Karten oder beides |
| Eine Dashboard-Ansicht ist deutlich schlechter als die anderen | aufwendige Karten, Kameradekodierung, Verlaufsdaten oder ein großer Dokumentbaum in dieser Ansicht |
| Die gesamte Ansicht wird leer, während die äußere Benutzeroberfläche bestehen bleibt | WebView-Heap-Druck oder Renderer-Ausfall |
| Das Dashboard verschlechtert sich über mehrere Tage hinweg allmählich | Heap-Wachstum, Speicherfragmentierung oder eine Karte, bei der sich Arbeit ansammelt |
| Entitäten werden nicht mehr aktualisiert und treffen später gebündelt ein | WebSocket-Unterbrechung, Neuverbindung oder ein Renderer, der nicht Schritt halten kann |
| Ähnliche Panels verhalten sich unterschiedlich | unterschiedliche Dashboard-Inhalte, Entitätsabonnement, WebView-Version, Betriebsdauer oder thermischer Zustand |
| Legacy stock NSPanel Pro becomes janky around ten hours after boot and `guard_process.sh` uses one core | check for the recursive vendor Zigbee-watchdog assignment leading to `E2BIG` |

Die Garbage Collection hält JavaScript regelmäßig an, um nicht mehr verwendeten Speicher freizugeben. Nahe der Heap-Obergrenze werden diese Pausen länger und häufiger, was dem Rendering die nötigen Ressourcen entziehen kann, selbst wenn der Android-Prozess selbst nicht abgestürzt ist.
