> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../adaptive-proximity.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Adaptive Näherungserkennung und Aktivierung durch Winken

Näherungssensoren in Android-Wandpanels verwenden keine einheitliche, brauchbare Skala. Einige melden eine Entfernung, andere nur zwei Werte, die Polarität kann unterschiedlich sein und Messwerte im Ruhezustand können zwischen Geräten oder Firmwareversionen abweichen. ha-paneld erlernt den unbeeinflussten Ausgangswert, den Referenzwert für Nähe, die Polarität und das Meldeverhalten des angeschlossenen Sensors, anstatt modellspezifische Schwellenwerte zu erfordern.

## Lernen und Anlernen

Öffnen Sie **Konfigurieren → Anwesenheit und Aktivierung**, um die aktuelle Phase anzuzeigen. Der Lernvorgang läuft lokal ab und erfordert normalerweise keine Einrichtung:

1. Halten Sie Abstand zum Panel, während es das Ruhesignal und den Ausgangswert für den Raum ermittelt.
2. Verwenden Sie das Panel wie gewohnt, damit gezielte Annäherungs- und Rückzugsbewegungen von Schwankungen im Ruhezustand unterschieden werden können.
3. Wenn eine schnellere Einrichtung gewünscht ist, wählen Sie **Winkbewegung anlernen** und führen Sie nach Aufforderung drei gezielte Winkbewegungen aus.
4. Sobald das Modell bereit ist, prüft **Winkbewegung testen** eine einzelne Geste, ohne das Display zu aktivieren.

Touch-to-wake remains available throughout learning. **Forget learned proximity** deletes the evidence for that sensor and returns it to the learning journey; use it after moving the panel, changing firmware or replacing the sensor route.

## Home Assistant-Entitäten

Wenn das Modell vertrauenswürdig ist und das aktive Profil eine nutzbare Näherungsquelle bereitstellt, erhält Home Assistant:

- `binary_sensor.<panel>_proximity` als erlernte Nah-/Fernbelegung und
- `sensor.<panel>_proximity_level` als geräteübergreifend normalisierten Wert von 0 bis 100, wobei 0 „fern“ und 100 „nah“ bedeutet.

Die Entitäten bleiben nicht verfügbar, solange nicht genügend Messdaten vorliegen oder der Sensorpfad fehlerhaft ist. ha-paneld veröffentlicht keinen vermeintlich verlässlichen Wert aus einem nicht vertrauenswürdigen Modell. Rein binäre Sensoren können weiterhin Belegung und Aktivierungsgesten bereitstellen; ein aussagekräftiger normalisierter Wert wird jedoch nur veröffentlicht, wenn das beobachtete Signal dies unterstützt.

## Aktivierung durch Winken

**Aktivierung durch Winken** erkennt eine einzelne, zeitlich begrenzte Fern→Nah→Fern-Bewegung. Längere Anwesenheit, Anlernen, Testen und kurze Schwankungen im Ruhezustand aktivieren das Display nicht. Dadurch werden weder eine Person, die in der Nähe des Panels steht, noch ein stark rauschender Sensor mit vielen Messwerten als Folge von Aktivierungsanforderungen interpretiert.

Die Funktion benötigt eine aktive Näherungsquelle und ein einsatzbereites angelerntes Modell. Die Quelle selbst kann jedoch ein gewöhnlicher Android-Sensor oder ein vom Profil ausgewählter Hilfspfad sein. Die Web-UI und die Diagnosefunktionen melden den tatsächlich verwendeten Pfad und dessen Bereitschaft; allein durch eine Profildeklaration wird kein Sensor verfügbar.
