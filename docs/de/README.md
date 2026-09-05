> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../../README.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../../app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="../../app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/maxlyth/ha-paneld?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/maxlyth/ha-paneld/releases)
[![Lizenz](https://assets.ha-paneld.com/docs/badge/license-apache-2-0-8aa187e4.svg)](../../LICENSE)

<!-- docs-i18n-language-picker:start -->
[English](../../README.md) · **Deutsch** · [Français](../fr/README.md) · [Italiano](../it/README.md) · [Español](../es/README.md) · [简体中文](../zh-Hans/README.md)
<!-- docs-i18n-language-picker:end -->

**Die universelle Home Assistant-Dashboard-App für Android-Wandpanels.**

ha-paneld macht Home Assistant-Dashboards auf Panels praktisch nutzbar, die sich sonst zu langsam oder umständlich bedienen lassen. Leistungsschwache Panels können träge werden oder erst nach mehreren Sekunden reagieren, wenn sie mit einer großen Home Assistant-Installation verbunden sind. Ein wichtiger Grund dafür ist, dass das Panel Aktualisierungen für weitaus mehr Entitäten empfängt und verarbeitet, als auf seinem Dashboard angezeigt werden. **Der integrierte Renderer von ha-paneld kann erkennen, welche Entitäten das Dashboard verwendet, und Home Assistant anweisen, nur deren Zustände zu senden**. In der Praxis kann dies die Entitätslast um 10–100× reduzieren und das Dashboard endlich nutzbar machen.

ha-paneld stellt außerdem für Wandpanels verschiedener Hersteller einen einheitlichen Satz von Bedienelementen in Home Assistant bereit. Abhängig von der Hardware können dazu Bildschirm, LEDs, Tasten, Sensoren, Relais und Audio gehören. MQTT Discovery fügt die verfügbaren Bedienelemente ohne gerätespezifisches YAML hinzu, und der Installer übernimmt die Android-Einrichtung.

Diese App ist für dedizierte Wandpanels vorgesehen, nicht für persönliche Smartphones. Die Hardwareunterstützung wird durch gewöhnliche YAML-Profile beschrieben, sodass Eigentümer und Hersteller weitere Panels hinzufügen können, ohne die App neu zu erstellen.

Über die Weboberfläche kannst du an zentraler Stelle ein Panel konfigurieren, Software installieren und herausfinden, was schiefgegangen ist. Die Leistungswerkzeuge messen die Reaktionszeit des Dashboards, unerwartete Neuladungen, CPU- und GPU-Auslastung, Taktfrequenz, Temperatur und die am stärksten ausgelasteten Prozesse. Der Installer bietet für eine heterogene Sammlung von Panels denselben Einrichtungs- und Aktualisierungsweg, während der integrierte Launcher und die Bildschirmnavigation dafür sorgen, dass Panels ohne Hardwaretasten praktisch nutzbar sind.

<picture>
  <source media="(prefers-color-scheme: light)" srcset="https://assets.ha-paneld.com/docs/screenshot/hero-light-a17f5f14.webp">
  <img src="https://assets.ha-paneld.com/docs/screenshot/hero-dark-aeb93099.webp" alt="ha-paneld-Dashboard mit Live-Panelstatus, Leistung und Bildschirmsteuerung">
</picture>

<details>
<summary><strong>Weitere Screenshots</strong></summary>

| Dashboard | Konfiguration |
|---|---|
| <a href="../img/ui-dashboard-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-dashboard-light.png"><img src="../img/ui-dashboard-dark.png" alt="Registerkarte „Dashboard“" width="420"></picture></a> | <a href="../img/ui-configure-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-configure-light.png"><img src="../img/ui-configure-dark.png" alt="Registerkarte „Konfiguration“" width="420"></picture></a> |

| Entitäten | Installation |
|---|---|
| <a href="../img/ui-entities-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-entities-light.png"><img src="../img/ui-entities-dark.png" alt="Registerkarte „Entitäten“" width="420"></picture></a> | <a href="../img/ui-install-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-install-light.png"><img src="../img/ui-install-dark.png" alt="Registerkarte „Installation“" width="420"></picture></a> |

| Profil | Protokolle |
|---|---|
| <a href="../img/ui-profile-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-profile-light.png"><img src="../img/ui-profile-dark.png" alt="Registerkarte „Profil“" width="420"></picture></a> | <a href="../img/ui-logs-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-logs-light.png"><img src="../img/ui-logs-dark.png" alt="Registerkarte „Protokolle“" width="420"></picture></a> |

| Bereitschaftsbildschirm | REST-API-Explorer |
|---|---|
| <img src="../img/standing-screen.png" alt="ha-paneld-Bereitschaftsbildschirm mit Konfigurationsadresse und QR-Code" width="420"> | <picture><source media="(prefers-color-scheme: light)" srcset="../img/api-explorer-light.png"><img src="../img/api-explorer-dark.png" alt="REST-API-Explorer" width="420"></picture> |

</details>

## Installation

Wenn du nicht sicher bist, ob ha-paneld auf deinem Panel ausgeführt werden kann, lies vor der Installation den Abschnitt [Panels und Unterstützungsstatus](#panels-und-unterstützungsstatus).

Mache ADB zunächst über das Netzwerk verfügbar. Bei einigen Panels ist dies eine Einstellung in den Entwickleroptionen; andere benötigen eine einmalige USB-Verbindung, um `adb tcpip 5555` auszuführen. Der [Bereitstellungsleitfaden](provisioning.md) und die modellspezifischen [Hardwareleitfäden](../hardware/) erläutern die verfügbaren Methoden. Führe anschließend diesen Befehl auf einem Computer mit `adb` im selben Netzwerk aus:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

> [!IMPORTANT]
> **Verwende unter Windows Git Bash oder WSL, nicht PowerShell.** Das Installationsprogramm ist ein `bash`-Skript. Git Bash ist in [Git for Windows](https://gitforwindows.org/) enthalten. Installiere `adb` mit `winget install Google.PlatformTools`, öffne die Shell erneut und führe dann den Befehl aus. Unter macOS und Linux kann er wie angegeben ausgeführt werden.

Du musst das Repository weder klonen noch Optionen angeben. Das Installationsprogramm prüft, ob `adb` und `curl` verfügbar sind, fragt nach der Adresse des Panels und erläutert jede Änderung, bevor es sie vornimmt. Es lädt die neueste signierte stabile Version herunter, installiert sie und prüft, ob ha-paneld ordnungsgemäß gestartet wurde.

Wenn ein erforderlicher Schritt fehlschlägt, nennt das Installationsprogramm das Problem und wird beendet, ohne zu behaupten, dass die Installation erfolgreich war. Behebe das Problem und führe denselben Befehl erneut aus.

> [!IMPORTANT]
> **Überprüfe Home Assistant und das System WebView des Panels, bevor das Dashboard zum ersten Mal geladen wird.** Der integrierte Renderer erfordert Home Assistant 2026.4.2 oder neuer und ein modernes WebView. Selbst ein neues Panel kann ein WebView enthalten, das zu alt ist, um ein aktuelles Dashboard anzuzeigen. Siehe [Anforderungen des integrierten Renderers](../built-in-renderer.md#requirements-and-compatibility) und [System WebView aktualisieren](../hardware/README.md#updating-the-system-webview).

Füge `--prerelease` hinzu, um die neueste veröffentlichte Version einschließlich Release Candidates zu verwenden. Eine neuere stabile Version hat weiterhin Vorrang:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
```

Dasselbe Installationsprogramm unterstützt die unbeaufsichtigte Bereitstellung eines einzelnen Panels. Unter [Bereitstellung und Flottenaktualisierungen](provisioning.md) findest du skriptgesteuerte Installationen, USB-Bootstrap, Panels ohne ADB-Zugriff über das Netzwerk und Aktualisierungen der gesamten Flotte.

ha-paneld wird nicht über Google Play vertrieben, daher ist für die Installation immer Sideloading erforderlich. Dies gilt auch für neuere Panels, die ansonsten Zugriff auf den Play Store haben.

### Andere Installationsmethoden

- **F-Droid auf dem Panel:** Füge das [F-Droid-Repository von ha-paneld](../fdroid.md) hinzu, um stabile Versionen ohne Computer zu installieren und zu aktualisieren. F-Droid benachrichtigt dich, wenn eine Aktualisierung verfügbar ist, und ermöglicht dir, sie auf dem Panel zu installieren; Release Candidates sind nicht enthalten. Sonoff NSPanel Pro-Firmware 4.0.0 und neuer enthält F-Droid. Dadurch wird die App installiert, aber Funktionen, die Root-Rechte erfordern, benötigen weiterhin die normalen Bereitstellungsschritte.
- **Manuelles Sideloading oder USB-Bootstrap:** Verwende die APK aus der [neuesten Version](https://github.com/maxlyth/ha-paneld/releases) und folge für die verbleibenden Berechtigungen und die Einrichtung der Anleitung [Bereitstellung und Flottenaktualisierungen](provisioning.md).

## Auswählen, wie das Dashboard ausgeführt wird

Verwende den integrierten Renderer, wenn du Dashboard-Entitäten filtern möchtest. Er unterstützt außerdem die Anmeldung über einen anderen Browser, die Auswahl eines bestimmten Dashboard-Tabs sowie einen schnelleren Start und eine schnellere Wiederherstellung. Nach einem Neustart der App kann er das zuletzt verifizierte, für das Konto als Standard festgelegte Dashboard erneut öffnen, während er die Dashboard-Liste von Home Assistant im Hintergrund aktualisiert.

Die offizielle [Home Assistant Companion-App](https://github.com/home-assistant/android) wird ebenfalls unterstützt. Verwende sie, wenn das Panel mehr als einen Home-Assistant-Server, die Sprachsteuerung mit Assist oder native Benachrichtigungen benötigt. Verwende auf einem Panel ohne Google Play und mit einer unterstützten Installationsmethode die Registerkarte „Installation“ von ha-paneld. Die Auswahl wendet die Kompatibilitätsgrenze für dieses Panel an, statt davon auszugehen, dass die neueste Companion-Version darauf läuft.

Beide Optionen werden weiterhin unterstützt. Das Filtern von Entitäten im Dashboard funktioniert nur mit dem integrierten Renderer von ha-paneld.

<a id="panels-and-support-status"></a>

## Panels und Unterstützungsstatus

ha-paneld muss nicht als System-App installiert sein. Grundlegende Android-Steuerungen wie Helligkeit, Navigation und TTS funktionieren auf kompatiblen Panels. LEDs, Relais, echtes Ausschalten des Bildschirms und einige Sensoren benötigen Unterstützung für das jeweilige Modell in dessen [Panelprofil](../profiles/README.md). Ereignisse von Hardwaretasten erfordern die Erfassung über die Android-Bedienungshilfen oder eine verifizierte Profilmethode.

| Panel | Status | Android / ABI | Hinweise |
|---|---|---|---|
| Sonoff NSPanel Pro / Pro 120 | Unterstützt | Android 8.1, arm64-v8a | PX30 / rk3326-S; die Stock-Firmware stellt Root-ADB bereit und bei der normalen Bereitstellung wird der authentifizierte Root-Helper von ha-paneld installiert |
| Tuya TPA10 | Unterstützt | Android 11, armeabi-v7a | rk3566 mit 32-Bit-Userspace |
| Electron WF1589T | Unterstützt | Android 14, arm64-v8a | rk3576-Userdebug-Firmware; `adb root`, native Android-Navigationsleiste und RGB-LED-Steuerung |
| ZHICAI SMT1019 | Von der Community getestet, einige Funktionen sind experimentell | Android 14, arm64-v8a | rk3576; die Standard-Firmware bietet keinen für Apps zugänglichen Root-Zugriff. Der authentifizierte Hilfsdienst kann, sofern installiert, zusätzlichen Hardwarezugriff ermöglichen. Die Genauigkeit der Temperatur- und Feuchtigkeitsmessung und die Unterstützung des Näherungssensors müssen noch weiter an der Hardware getestet werden. [Issue #8](https://github.com/maxlyth/ha-paneld/issues/8) |
| ZX-SMT156 / RK3566_T | Vorläufig | Android 13, arm64-v8a | RGB-LED sowie Licht- und Näherungssensor funktionieren ohne Root-Zugriff. Die Unterstützung für Temperatur- und Feuchtigkeitssensoren ist optional; Relais und Root-Zugriff werden noch genauer untersucht. [Issue #24](https://github.com/maxlyth/ha-paneld/issues/24) |
| Smatek S9E | Experimentell | Android 11, arm64-v8a | Profil für integrierte Relais, Tasten-LEDs und Näherungssensor. Eine Bestätigung im laufenden Betrieb auf S9E-Hardware steht noch aus. |
| Shelly Wall Display (Original) | Inkompatible Werkssoftware | Android 7.0, armeabi-v7a | Die Android-Version ist älter als die von ha-paneld mindestens vorausgesetzte Version. |
| Shelly Wall Display X2 | Nur zu Forschungszwecken | Android 8.1, armeabi-v7a | Kein bestätigter Installationsweg für ha-paneld. |
| Shelly Wall Display X1i / X2i / XL | Nur zu Forschungszwecken | Android 11, arm64-v8a | Die Profilmetadaten müssen noch nach Modell aufgeteilt werden. Es gibt keinen bestätigten Installationspfad für ha-paneld. |

Modellspezifische Einrichtung, bekannte Einschränkungen und durch Reverse Engineering ermittelte Hardwaredetails sind in der [Hardwaredokumentation](../hardware/) beschrieben.

## Funktionen zur Hardwaresteuerung

Jedes Panel stellt nur die Steuerungsmöglichkeiten bereit, die von seinem Profil und der erkannten Hardware unterstützt werden. Ihre Namen und ihr Verhalten sind über alle Modelle hinweg einheitlich.

| Funktion | Steuerung über Home Assistant oder API |
|---|---|
| Bildschirmhelligkeit | `light.<panel>_screen` Helligkeit |
| Bildschirm ein/aus | `light.<panel>_screen` ein/aus; echtes Ausschalten des Bildschirms, sofern das Profil dies unterstützt, andernfalls sicheres Dimmen der Helligkeit |
| RGB-LED | `light.<panel>_led` auf Panels mit unterstützter LED-Hardware |
| Hardwaretasten | `event.<panel>_button` wenn die Erfassung über die Android-Bedienungshilfen oder eine verifizierte Profilmethode verfügbar ist |
| Umgebungslicht und Näherung | `sensor.<panel>_illuminance`, `binary_sensor.<panel>_proximity` und ein normalisierter `sensor.<panel>_proximity_level` von 0 (fern) bis 100 (nah) |
| Adaptive Helligkeit | Optionales siebentägiges Lernen anhand des Lichtsensors des Panels oder einer Beleuchtungsstärke-Entität von Home Assistant |
| Eine URL öffnen | `text.<panel>_navigate` |
| Dashboard-Steuerung und Neustart | Home-Assistant-Schaltflächen sowie die Aktionen „Dashboard“ und „Neu laden“ und die Navigationsaktionen im entfernten Bereich „Steuerung“ |
| TTS- und Durchsageaudio | `POST /play` und `number.<panel>_volume`; siehe die [TTS-Anleitung](../tts.md) |
| Dashboard-Screenshot und entferntes Tippen | Panels mit einer unterstützten Screenshot-Methode können den Bildschirm auf der Registerkarte „Dashboard“ anzeigen und aktualisieren; im Relaxed-Modus kann außerdem ein Tippen an das Panel zurückgesendet werden |
| Panel-Informationen und -Konfiguration | Öffne `http://<panel>:8888/`, auf der Home Assistant-Geräteseite auch als **Besuchen** verlinkt |

Home Assistant erkennt diese Bedienelemente über MQTT ohne YAML. Die wichtigsten Entitätsfamilien sowie Details zur HTTP-API und Kopplung findest du in [docs/api.md](../api.md). Du kannst die HTTP-API eines Panels unter `http://<panel>:8888/api` auch durchsuchen und ausprobieren.

## Sicherheit und Root-Zugriff

### Hardened-Modus

Der Relaxed-Modus ist die Standardeinstellung und für ein vertrauenswürdiges Heimnetzwerk vorgesehen. Verwende den [Hardened-Modus](../security-mode.md), wenn weniger vertrauenswürdige Geräte dasselbe Netzwerk nutzen. Der Hardened-Modus erfordert physischen Zugriff auf das Panel. Entfernte Aktionen mit weitreichenden Auswirkungen muss jemand direkt am Bildschirm des Panels genehmigen; eine Genehmigung aus der Ferne ist nicht möglich. Screenshots bleiben sichtbar, aber entferntes Tippen ist deaktiviert. Die Einstellung muss auf jedem Panel separat aktiviert werden und wird weder durch Sicherung und Wiederherstellung noch durch die Flottenbereitstellung kopiert.

### Funktionen, die Root-Zugriff benötigen

Manche Panel-Hardware ist für gewöhnliche Android-Apps nicht zugänglich und benötigt daher Root-Zugriff. Ob Root verfügbar ist, hängt von der Firmware des Panels ab, nicht von ha-paneld. Einige Panels stellen `su` bereit; auf anderen kann das Installationsprogramm den kleinen Root-Helfer von ha-paneld hinzufügen. Der Helfer bietet weder eine allgemeine Shell noch uneingeschränkten Dateizugriff.

Die Weboberfläche kennzeichnet nicht verfügbare Bedienelemente mit einem Schloss und erklärt, was dem Panel fehlt. Das Installationsprogramm und die Diagnose melden ebenfalls, welche Zugriffsebene verfügbar ist.

**Kein Root-Zugriff erforderlich:** Kopplung mit Home Assistant, Bildschirmhelligkeit und Dimmen, Audiodurchsagen, beide Dashboard-Optionen, die Weboberfläche, die REST-API sowie Sicherung und Wiederherstellung der Konfiguration. Zurück, Zuletzt verwendete Apps, Aufwecken durch Winken und die Software-Navigationsleiste hängen von der jeweiligen Android- oder Sensorfunktion ab, benötigen aber nicht grundsätzlich Root-Zugriff.

**Root oder der authentifizierte Helfer kann erforderlich sein:** physisches Ausschalten der Hintergrundbeleuchtung, Android-Ruhezustand, wenn das Profil ihn auswählt, RGB-LED-Steuerung auf einigen Panels, Steuerung der Hersteller-App, Neustart und CPU-Governor. Wenn das aktive Profil keine sichere Möglichkeit bietet, den Bildschirm vollständig auszuschalten, dimmt ha-paneld ihn stattdessen.

**Direktes `su` innerhalb von ha-paneld ist weiterhin erforderlich:** Android auf das Dashboard beschränken, vollständige Systemprotokolle, Relaissteuerung, wenn das Profil sie erfordert, und der alte Importpfad für Companion-Sitzungen. Eine vollständige Sicherung kann eine bestehende Companion-Anmeldung enthalten, die immer über den authentifizierten Helfer läuft: Das auf Deskriptoren beschränkte Protokoll ist auch auf Panels mit direktem Root-Zugriff der einzige Pfad.

Für tatsächlich nicht gerootete Panels gibt es eine eingeschränkte [erweiterte Ausweichlösung](provisioning.md#shizuku-ausweichlösung-für-panels-ohne-root-zugriff). Sie gehört jedoch nicht zum regulären Pfad für unterstützte Hardware und stellt keine Hardwarefunktionen bereit, die Root-Zugriff erfordern.

## Anleitungen und Referenz

### ha-paneld verwenden

- [Bereitstellung und Flottenaktualisierungen](provisioning.md): unbeaufsichtigte Installation, Einrichtung von ADB über USB und Netzwerk, Sicherungen und Aktualisierungen der gesamten Flotte.
- [Integrierter Renderer](../built-in-renderer.md): Anforderungen, Remote-Anmeldung, Dashboard-Auswahl, Wiederherstellung und bewusste Einschränkungen.
- [Leistung](../performance.md): herausfinden, warum ein Dashboard langsam ist, und die Auswirkung der Entitätsfilterung messen.
- [Adaptive Helligkeit](../adaptive-brightness.md): eine Lichtquelle auswählen, den Lernvorgang verstehen und nach dem Versetzen eines Panels den Verlauf zurücksetzen.
- [Adaptive Näherungserkennung und Aktivierung durch Winken](../adaptive-proximity.md): die Näherungserkennung konfigurieren und die Aktivierungsgeste anlernen.
- [Sicherheitsmodi](../security-mode.md): den Relaxed-Modus und den Hardened-Modus verstehen, einschließlich der Aktionen, für die jemand am Panel anwesend sein muss.
- [TTS](../tts.md): Sprache mit einer Home Assistant TTS-Engine erzeugen und an ein Panel senden.

### ha-paneld entwickeln und erweitern

- [HTTP-, MQTT- und Home-Assistant-API](../api.md): die HTTP-Endpunkte, wichtigsten MQTT-Entitätsfamilien, Kopplung und Erkennung. Die maschinenlesbare Spezifikation ist auf einem Panel unter `/api/v1/openapi.json` verfügbar.
- [Panelprofile](../profiles/): Unterstützung für ein weiteres Panel erstellen, testen und teilen, ohne die App neu zu bauen.
- [Hardware-Referenzen](../hardware/): modellspezifische Einrichtung, Sensoren, Bedienelemente, Firmware und Hinweise zum Reverse Engineering.
- [Aus dem Quellcode bauen](../building.md) und [lokale Entwicklung](../local-builds.md): mit Docker, dem Entwicklungscontainer oder einer lokalen Android-Toolchain bauen.
- [Roadmap](../roadmap.md): geplante Arbeiten. Abgeschlossene Arbeiten sind im [Änderungsprotokoll](../../CHANGELOG.md) dokumentiert.

Die Seite `GET /diag` des Panels erstellt für Fehlerberichte einen Bericht über Hardware, Firmware und Funktionen. Prüfe und schwärze ihn, bevor du ihn öffentlich teilst.

## Andere Kiosk-Apps

### Fully Kiosk

Ich empfehle nicht, [Fully Kiosk Browser](https://www.fully-kiosk.com/) und ha-paneld gleichzeitig auszuführen. Beide würden versuchen, den Bildschirm, das Kioskverhalten und die Fernsteuerung zu verwalten. Dadurch gäbe es zwei Stellen, an denen dasselbe Panel konfiguriert wird.

<details>
<summary>Warum ich nicht empfehle, beide parallel zu betreiben</summary>

- Fully Kiosk ist proprietäre kommerzielle Software. Die Funktionen zur Fernverwaltung erfordern für jedes Gerät eine [kostenpflichtige Lizenz](https://license.fully-kiosk.com/license/single).
- Die Entitätsfilterung ist Teil des integrierten Renderers von ha-paneld und kann daher nicht von einem separaten Browser verwendet werden.
- Fully Kiosk wird auf jedem Gerät separat konfiguriert. Das wird umständlich, wenn mehrere Panels unterschiedlicher Hersteller einheitlich funktionieren sollen.

Verwende auf dem Panel eine einzige Dashboard-App: den integrierten Renderer von ha-paneld, Companion oder einen separaten Kiosk-Browser, falls dieser etwas bietet, das die beiden anderen nicht bieten.

</details>

### FreeKiosk

[FreeKiosk](https://github.com/RushB-fr/freekiosk) steht trotz des ähnlichen Namens in keiner Verbindung zu ha-paneld. Es ist kostenlos und quelloffen, verwendet jedoch React Native und führt daher neben dem Home Assistant-Dashboard eine weitere JavaScript-Engine aus. Diese zusätzliche Last kann auf Panels mit nur 1–2 GB RAM erheblich sein.

## Community-Chat

Ich wollte für ein Ein-Personen-Projekt weder einen Discord-Server noch einen Slack-Workspace einrichten, daher experimentiere ich mit Matrix und Element. Tritt [#ha-paneld:matrix.org](https://matrix.to/#/#ha-paneld:matrix.org) in deinem üblichen Matrix-Client bei oder sieh dir den Raum ohne Konto in [Element Web](https://app.element.io/#/room/#ha-paneld:matrix.org) an.

Veröffentliche keine Konfigurationen oder Dateilinks in GitHub-Issues oder -Diskussionen, wenn du nicht damit einverstanden bist, dass sie für immer öffentlich bleiben. Der Matrix-Raum ist ebenfalls öffentlich und weltweit lesbar, aber Matrix unterstützt auch private Direktnachrichten für Supportdetails, die nicht Teil einer dauerhaften öffentlichen Aufzeichnung werden sollen. Entferne Zugangsdaten, private URLs und persönliche Angaben, bevor du Konfigurationen, Protokolle oder Dateilinks irgendwo veröffentlichst.

## Soll dein Panel unterstützt werden?

ha-paneld hat keine Spendenschaltfläche. Es ist kostenlos, und die „Bezahlung“, die das Projekt tatsächlich voranbringt, ist die Unterstützung weiterer Panels. Dafür wird Hardware zur Untersuchung benötigt.

Beginne mit dem [Leitfaden zu Laufzeitprofilen](../profiles/README.md). Das Generic-Profil kann einen passiven Entwurf erstellen, den du validieren, testen und teilen kannst, ohne die App zu bauen. Bevor ein Profil in ha-paneld gebündelt werden kann, benötige ich weiterhin Nachweise vom realen Gerät, insbesondere zu seinen Tasten, LEDs, Relais und Sensoren.

Wenn du also helfen möchtest:

- **Erstelle und teile ein Profil.** Öffne `http://<panel-ip>:8888/profiles`, lade den Entwurf für das Generic-Gerät herunter und folge den Anleitungen zum [Testen](../profiles/testing.md) und [Teilen](../profiles/sharing.md). Ein Community-Profil kann bereits nützlich sein, bevor es zusammen mit ha-paneld ausgeliefert werden kann.
- **Erstelle ein Issue mit den Diagnosedaten des Panels.** Rufe `http://<panel-ip>:8888/diag` auf, prüfe und bereinige den Bericht und füge ihn anschließend in ein neues Issue ein. Das reicht für den Anfang. Ich werde gemeinsam mit dir eine kurze Reihe von Tests für alle Tasten, LEDs, Relais oder Sensoren durchführen, für die jemand direkt am Panel anwesend sein muss.
- **Schicke mir das Panel.** Ich bin in Großbritannien ansässig und übernehme das Reverse Engineering gerne direkt. Dies ist der schnellste Weg zu vollständig unterstützter Hardware. Du bekommst es zurück (ich habe bereits viel zu viele); erstelle zuerst ein Issue, damit wir die Einzelheiten abstimmen können.

Das Ergebnis ist immer offen: Dein Panel wird zu einem Profil, das alle verwenden können. Das ist die Spende.

## Entwicklung

Wenn du an ha-paneld selbst mitarbeiten möchtest, beginne mit [CONTRIBUTING.md](../../CONTRIBUTING.md). Die Entwicklerdokumentation behandelt das [Bauen aus dem Quellcode](../building.md), [lokale Builds und Builds in Entwicklungscontainern](../local-builds.md), die [HTTP- und MQTT-API](../api.md), die [Entwicklung von Panelprofilen](../profiles/README.md), die [Browser-Testumgebung](../../test/README.md) und den [Veröffentlichungsprozess](../RELEASING.md).

Ich habe bewusst genügend Informationen bereitgestellt, um den mitgelieferten Entwicklungscontainer zu verwenden und eine lokale Testversion zu erstellen. Reiche computergenerierte Pull Requests oder Issues nicht unverändert ein: Lies und verstehe jeden Teil des vorgeschlagenen Textes und Codes und formuliere ihn anschließend in deinen eigenen Worten neu. Dies ist ein Ein-Personen-Projekt, und ich habe keine Zeit, ungefilterte computergenerierte Ausgaben zu prüfen. Fasse dich kurz und schreibe für Menschen; wenn du dir bei etwas unsicher bist, frage zuerst nach.

<details>
<summary><strong>Technologie-Stack</strong></summary>

- **Anwendung:** [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX](https://github.com/androidx/androidx) und [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines).
- **HTTP und Home-Assistant-WebSocket:** [Ktor](https://github.com/ktorio/ktor) mit CIO-Server-, Client- und WebSocket-Modulen.
- **MQTT:** [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client) mit dessen MQTT-5-Client und reinem Java-NIO-Transport.
- **mDNS:** [JmDNS](https://github.com/jmdns/jmdns) kündigt `_ha-paneld._tcp` an, damit ha-paneld-Instanzen einander für den Umschalter zwischen mehreren Panels finden können. ha-paneld meldet, wenn diese Ankündigung ausfällt und nicht wiederhergestellt werden kann.
- **Laufzeitprofile:** [SnakeYAML Engine](https://github.com/snakeyaml/snakeyaml-engine) für YAML 1.2 sowie [CodeMirror](https://codemirror.net/) und dessen [YAML-Sprachpaket](https://github.com/codemirror/lang-yaml) im Profileditor.
- **QR und Protokollierung:** [ZXing](https://github.com/zxing/zxing) für Einrichtungs-QR-Codes und [SLF4J](https://github.com/qos-ch/slf4j) für die Ktor- und HiveMQ-Protokollierung über Logcat.

Die Auswahl und Aktualisierung von Abhängigkeiten folgen der [Abhängigkeits- und Lieferkettenrichtlinie](../../SECURITY.md#dependency-and-supply-chain-policy) des Projekts.

</details>

## Übersetzungen

Die Übersetzungen werden mithilfe mehrerer Dienste und Modelle erstellt und gegengeprüft, darunter EuroLLM, DeepL und OpenAI. Sie wurden nicht systematisch von Sprechern der jeweiligen Sprache geprüft, daher bleibt der englische Text maßgeblich. Wenn eine Formulierung unklar oder falsch ist, [öffne ein Issue zur Übersetzungskorrektur](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

## Danksagung

Vielen Dank an **Seaky** für [NSPanel Pro Tools](https://github.com/seaky/nspanel_pro_tools_apk), eines der Projekte, die mich dazu inspiriert haben, ha-paneld zu starten. ha-paneld ist keine Open-Source-Neuimplementierung von NSPanel Pro Tools. Es hat sich zu einer wesentlich umfassenderen Plattform für Wandpanels entwickelt, mit einem eigenen Dashboard-Renderer, Entitätsfilterung, Laufzeit-Hardwareprofilen, Diagnose und Bereitstellung für Panels mehrerer Hersteller. Die beiden Projekte verfügen inzwischen über sehr unterschiedliche Funktionsumfänge und sollten nicht als austauschbar betrachtet werden, selbst auf einem Sonoff-Panel.

## Lizenz

Apache-2.0. Siehe [LICENSE](../../LICENSE).
