> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../../hardware/README.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Références matérielles des panneaux

Reverse-engineered hardware fact sheets for the wall panels ha-paneld targets — SoC, LED control, sensors, buttons, NFC, Zigbee/IR, relays, adb/root access. These devices ship with almost no public documentation, so these notes record what is physically on each board and how to drive it, gathered from live units (rooted / userdebug `adb root`). If your panel is not listed, start with the no-build [runtime profile authoring workflow](../../profiles/README.md). If your panel has a camera the Camera card does not offer, see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one) and keep unverified hardware facts explicit.

| Panneau | SoC | Commande des LED | Capteurs notables | NFC | Zigbee/IR | Référence |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs (démon root) | ToF VI5300, température+humidité CHT8305, luminosité CG5256 ; **[caméra](tpa10.md#caméra)** (GC05A2) et ADC de capture ES7202 (microphone utilisable non vérifié) | non | non | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (accès direct par l’application) | IMU 6 axes (KXTJ9 + BMA2xx) ; **[caméra](wf1589t.md#caméra)** (GC05A2) et microphone ES7202 | oui (NXP, mais le NFC d’Android est désactivé) | non | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326 / PX30 | aucune (aucun nœud RGB) | luminosité + proximité STK3A5x (accès direct par l’application) | non | **Zigbee** (Silabs EFR32, UART) ; pas d’IR | [nspanel-pro.md](nspanel-pro.md) |
| Smatek S9E † | rk3566 | LED GPIO par bouton (root) | proximité radar, luminosité, température+humidité ; **2 relais secteur** (`st_relay`) ; RS485 + Ethernet | non | **Zigbee** | [s9e.md](../../hardware/s9e.md) |
| ZHICAI SMT1019 ‡ | rk3576 | utilitaire root disponible sur la version `userdebug` du fournisseur ; indisponible sur le firmware d’origine | température + humidité GXHT30 (précision non vérifiée) ; proximité VI530x expérimentale | non | non | [smt1019.md](../../hardware/smt1019.md) |
| ZX-SMT156 / RK3566_T ‡ | rk3566 | `/dev/ledjni` (accès direct par l’application) | proximité binaire, luminosité ambiante ; température+humidité GXHT30 (utilitaire ou solution de secours fixe au niveau du shell) | inconnu | relais du fournisseur signalés, chemin de commande inconnu | [zx-smt156.md](../../hardware/zx-smt156.md) |
| Shelly Wall Display § | MT6580 / SC7731E / RK3326-S / RK3566 (selon le modèle) | aucune solution établie | luminosité ambiante sur les modèles original/X2/X1i/X2i/XL ; température/humidité sur original + X2 ; proximité sur X2/X1i/X2i ; mouvement sur XL ; les relais varient selon le modèle/socle | non établi pour tous les modèles | non établi pour tous les modèles | [shelly-wall-display.md](../../hardware/shelly-wall-display.md) |

† Les caractéristiques du S9E proviennent de la fiche Smatek ; les chemins de commande viennent de [#98](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) et de la discussion de la communauté HA, et n’ont **pas** été validés ici sur un appareil réel — la prise en charge des relais/boutons est implémentée mais non testée.

‡ Les informations sur les SMT1019 et ZX-SMT156 proviennent des diagnostics de contributeurs et des éléments OEM ou commerciaux associés ([#8](https://github.com/maxlyth/ha-paneld/issues/8), [#24](https://github.com/maxlyth/ha-paneld/issues/24)) ; aucun des deux panneaux n’est disponible pour des tests locaux. La persistance de l’utilitaire du SMT1019 et les axes bruts des capteurs climatiques sont étayés par des éléments fournis par un contributeur, mais la précision de ces capteurs, la détection de proximité de bout en bout et le profil complet nécessitent encore des tests matériels. La prise en charge des capteurs climatiques du ZX est facultative ; les méthodes d’accès root par USB ou fournies par le fabricant, ainsi que les méthodes de déverrouillage persistant, restent non testées.

§ Les informations sur le Shelly Wall Display proviennent de l’analyse des firmwares OTA (dont l’analyse de l’arborescence des périphériques de l’image de partition moderne), du journal des modifications officiel et de sources communautaires/KB ; elles n’ont **pas** été validées ici sur un appareil réel. L’**ancienne** OTA déclare une compilation cible `userdebug` ; `adb root` pourrait donc y être accessible s’il existe un point d’entrée adb. L’OTA **moderne** ne déclare aucun type de compilation — consultez [shelly-wall-display.md](../../hardware/shelly-wall-display.md) pour les éléments propres à chaque branche et la déclaration de Shelly sur le matériel actuel. Les profils YAML `shelly-wall-display` et `shelly-wall-display-v2` inclus sont implémentés mais spéculatifs.

> [!TIP]
> Before modifying firmware on a rooted **TPA10 or WF1589T**, read [Firmware backup & restore](../../firmware-backup-restore.md). Those Rockchip panels use `adb reboot loader` and `rkdeveloptool` rather than the usual Android button combination. The guide does not apply to the MediaTek Shelly family, an unrooted panel or uncharacterised hardware, and it does not yet claim a write-ready Maskrom recovery path.

## Méthode

- **Puces réellement présentes** : périphériques I²C associés via `/sys/bus/i2c/devices/*/name` — et *non* via `…/drivers/`, car les BSP Rockchip compilent des centaines de pilotes facultatifs et la liste `drivers/` surestime fortement le matériel présent.
- **Radios** : `pm list features` (`nfc`, `consumerir`, `bluetooth`, `ethernet`, …) + nœuds `/dev`.
- **Capteurs exposés par Android** : `dumpsys sensorservice`.
- **Interfaces de commande** : `/sys/class/leds`, `/dev` et les attributs propres à chaque nœud LED (certains panneaux se documentent eux-mêmes, par exemple les `avsux_info` / `avsux_firmware` du TPA10).

Les corrections et ajouts concernant d’autres panneaux sont les bienvenus.

The `Native` navbar mode is profile-gated, not specific to Electron panels. The bundled WF1589T profile currently declares it because that firmware's Android navbar has been verified. Other profiles can enable the same mode after their system bar has been confirmed.

## Obtention de l’accès adb + root

Chaque panneau donne accès à adb/root différemment ; les pages propres à chaque panneau détaillent la procédure complète adaptée à son firmware :

- **Sonoff NSPanel Pro** — `userdebug`/test-keys, **no adb password**; the only hurdle is reaching developer mode (varies by eWeLink firmware). `adb root` + remount + a SuperSU `su`. → [nspanel-pro.md](nspanel-pro.md#obtention-de-laccès-adb-et-root).
- **Tuya TPA10** — adb is **password-protected**; the reliable route is the USB diagnostics-app backdoor (`su` already present). → [tpa10.md](tpa10.md#obtention-des-accès-adb-et-root).
- **Electron WF1589T** — `userdebug` with Google Play; `adb root` works directly (LED is app-direct, so root is rarely needed). → [wf1589t.md](wf1589t.md).

## Comparaison des performances et déploiement pratique

Les trois catégories de panneaux forment une hiérarchie claire : **NSPanel Pro (PX30)** en entrée de gamme, **TPA10 (rk3566)** en milieu de gamme et **WF1589T (rk3576)** en haut de gamme. La géométrie de l’écran est la première contrainte de conception ; sur les panneaux de 2 Go, la RAM est la contrainte principale. Les chiffres proviennent du point de terminaison `/perf` de ha-paneld et des caractéristiques des appareils.

<details>
<summary>Hiérarchie des caractéristiques (CPU / RAM / GPU / écran)</summary>

| | NSPanel Pro (PX30) | TPA10 (rk3566) | WF1589T (rk3576) |
|---|---|---|---|
| CPU | 4× Cortex-A35 à 1,5 GHz | 4× Cortex-A55 à 1,8 GHz | 4× A72 à 2,1 GHz + 4× A53 à 1,9 GHz |
| RAM | 2 Go | 2 Go | 4 Go |
| GPU | Mali-G31 | Mali-G52 (2EE) | Mali-G52 (MC3) |
| Écran | 480×480 **carré**, ~4 po | 1920×1200 16:10, ~10,1 po/~226 ppp | 1920×1200 16:10, ~10,1 po/~226 ppp |
| Fréquence de rafraîchissement | 60 Hz | 56 Hz | 60 Hz |
| Mise en page (dp) | densité logique de base de 160 dpi → 480×480 dp | densité logique de base de 240 dpi ; ha-paneld recommande 212 | densité logique de base de 160 dpi → 1920×1200 dp — interface minuscule, [augmentez la densité](wf1589t.md#densité-daffichage--augmentez-la) |
| Caméra | aucune | GC05A2, `Facing: Back` ; encodage H.264 via `OMX.rk.video_encoder.avc` | GC05A2, `Facing: Front` ; encodage H.264 via `c2.rk.avc.encoder` |
| Catégorie | entrée de gamme | milieu de gamme | haut de gamme |

</details>

<details>
<summary>Instantané `/perf` en direct (illustratif, pas un benchmark contrôlé)</summary>

Chaque panneau soumis à sa propre charge réelle :

| | PX30 (presque inactif) | WF1589T (tableau de bord actif) |
|---|---|---|
| CPU | 9 % | 29 % |
| Fréquence | 408 MHz (sur 1512) | gros cœurs à 1608 MHz (sur 2112) |
| RAM utilisée | 508 / 1960 Mo | 2265 / 3897 Mo |
| Température | 49 °C | 63 °C |
| Réactivité | fluide, fil d’exécution principal : 3,6 % | fluide, fil d’exécution principal : 25,9 % |

(Le TPA10 se situe entre les deux en matière de CPU.)

</details>

**Ce que cela implique pour le déploiement réel d’un tableau de bord :**

- **La géométrie de l’écran est la première contrainte de conception.** L’écran **carré** de 480×480 (480 dp) du NSPanel Pro ne peut accueillir qu’une seule colonne étroite ; l’écran 10,1 po 1920×1200 du TPA10 offre réellement assez d’espace pour des tableaux de bord à plusieurs colonnes ; le WF1589T est livré avec une faible densité logique de base, si bien que l’interface reste minuscule jusqu’à ce que cette densité soit augmentée. Concevez le tableau de bord en fonction de la **surface en dp et du rapport hauteur/largeur** du panneau, pas de son nombre brut de pixels. La densité logique de base d’Android est un paramètre de mise en page, pas une densité physique en ppp.
- **Panneaux de 2 Go (PX30, TPA10) : la RAM est la contrainte principale.** Le WebView du tableau de bord, Android et les applications en arrière-plan se partagent environ 2 Go ; les tableaux de bord lourds, comportant de nombreuses cartes, de grandes images, de longs graphiques d’historique ou des cartes personnalisées coûteuses, provoquent des rechargements du WebView et des saccades. Les 4 Go du WF1589T réduisent largement cette pression.
- **Le NSPanel Pro possède le CPU le plus lent de cette comparaison** (A35) ; les transitions et animations sont donc visiblement plus lentes que sur les appareils A55/A72. Utilisez-y les tableaux de bord les plus légers.
- **Pour le moteur de rendu intégré, filtrez l’abonnement aux entités Home Assistant avant de simplifier un tableau de bord ou de remplacer le panneau.** L’apprentissage automatique des entités peut empêcher les états sans rapport d’atteindre le WebView tout en préservant la connexion Home Assistant normale du panneau. Consultez [Optimisation des performances](../performance.md).
- **ha-paneld mesure les goulots d’étranglement restants** : le temps de réponse du tableau de bord, les rechargements inattendus, la fréquence du CPU et le bridage thermique, la pression mémoire, les processus les plus actifs et les métriques de rendu WebView permettent de distinguer les limites matérielles d’un tableau de bord gourmand en ressources ou d’un volume de données excessif.

## Mise à jour du WebView système

**À lire avant toute autre chose** — c’est la cause la plus courante d’échec au premier lancement sur ces panneaux.

ha-paneld's built-in renderer and the HA Companion app both rely on Android's **system WebView**, and most of these panels ship with one far too old to run a current Home Assistant frontend. Out of the box this can produce a **blank or broken dashboard, missing cards, or "browser not supported"**. Panels **without** Google Play (NSPanel Pro, TPA10) cannot update it automatically through the Play Store, so install a current WebView using the appropriate method below. The **WF1589T and the SMT1019 have Google Play**, so update *Android System WebView* from the Play Store or use the Play WebView development channel.

The clean way is a direct adb sideload of the standard Android System WebView (package **`com.android.webview`**), matched to the panel's Android version and ABI — **no F-Droid, no third-party app store** (the workarounds the NSPanel-Pro community threads resort to). Per-panel known-working builds and the full sideload/verify steps are below.

> [!TIP]
> The package name must be `com.android.webview` for the system to select it automatically. Mind the distinction: the **SystemWebView** builds from Cromite and LineageOS use `com.android.webview` and *do* register as the provider — but the regular **Cromite / Bromite *browser*** app uses a different package and does **not**. Use the SystemWebView build, not the browser APK.

### Versions d’origine et remplacements fonctionnels connus par panneau

"Stock" = what the vendor firmware ships from factory, verified from firmware OTA inspection or a live device. "Replacement" = what is confirmed working after sideload. Redistributable builds are mirrored as ha-paneld Release assets; sideload with `adb install -r <file>`.

| Panneau | ABI | D’origine (firmware du fournisseur) | Remplacement (`com.android.webview`) | Téléchargement |
|---|---|---|---|---|
| NSPanel Pro 86P (PX30) | arm64-v8a | Chromium **107.0.5304.105** verified on firmware 3.5.1; check other firmware/models before updating | **LineageOS** 138.0.7204.63 — last build for Android **8.1** | [fichier de la version publiée](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk) · [APKMirror](https://www.apkmirror.com/apk/lineageos/android-system-webview-2/android-system-webview-138-0-7204-63-2-release/android-system-webview-138-0-7204-63-8-android-apk-download/download) |
| TPA10 (rk3566) | armeabi-v7a | **Chrome 83** (`com.android.webview`) — trop ancien pour l’interface HA actuelle | **LineageOS** SystemWebView 150.0.7871.63 — vanilla Chromium, allows camera autoplay (Cromite 147 blocks it, kept as fallback). **Signature-locked — needs the root swap in [tpa10.md](tpa10.md#webview--à-mettre-à-jour-en-premier), not a plain sideload.** | [ressource arm](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk) |
| WF1589T (rk3576) | arm64-v8a | WebView Google Play (mise à jour automatique) | update via Play Store — no sideload needed | — |
| S9E (rk3566) | **arm64-v8a** | **Firmware-dependent — check before replacing.** Chromium **83.0.4103.120** on the 2024-07 build (too old for a current HA frontend), but **131.0.6778.200** on the 2025-12 build | Only needed on the older firmware: **LineageOS** 150.0.7871.63 (**arm64**) — *provisional, unverified hardware*; may be signature-locked like the TPA10. On 2025-12 firmware the stock WebView is already current | [ressource arm64](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk) |
| SMT1019 (rk3576) | arm64-v8a | `com.google.android.webview` **124.0.6367.179** — Android 14 **avec** Google Play | **Update *Android System WebView* from the Play Store** — no sideload needed. Do not sideload a `com.android.webview` build here: the panel's own provider list is supplied by a product overlay, so a sideloaded provider may not be selected | — |
| ZX-SMT156 / RK3566_T | arm64-v8a | Google WebView **149.0.7827.164** (firmware signalé par le contributeur) | Google WebView is current; no replacement needed | — |
| Shelly Wall Display original (MT6580) | armeabi-v7a | **inconnu** (ROM Android 7 de base) | `com.google.android.webview` **119.0.6045.194** via [official Shelly ZIP](https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip) — see [shelly-wall-display.md](../../hardware/shelly-wall-display.md#webview) | — |
| Shelly Wall Display X2 (SC7731E) | armeabi-v7a | **inconnu** (ROM Android 8.1 de base) | non établi — consulter `adb shell dumpsys webviewupdate` | — |
| Shelly Wall Display X1i/X2i/XL (arm64) | arm64-v8a | **inconnu** (ROM Android 11 de base ; absent de l’OTA Shelly) | non établi — consulter `adb shell dumpsys webviewupdate` | — |

Toutes les compilations mises en miroir figurent dans la [**publication Miroir WebView pour panneaux** dédiée](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror) — une liste communautaire évolutive des versions dont le bon fonctionnement est connu. Une version fonctionne sur un autre panneau ou une autre version ? Les contributions sont les bienvenues.

> [!NOTE]
> - **Pick the newest WebView your panel's Android version supports.** The NSPanel Pro's Android 8.1 caps at 138 (the last Chromium for Android 8/9); newer builds won't install. Android 10+ (the TPA10's 11) runs current **LineageOS** WebView (150).
> - **Les liens de téléchargement d’APKMirror *directs* sont des URL présignées de courte durée qui expirent dans l’heure** — utilisez la page ou les ressources de release ha-paneld ci-dessus (pérennes). Le miroir existe précisément parce que ces panneaux n’ont pas Play et sont livrés avec des firmwares anciens de plusieurs années ; trouver une compilation fonctionnelle peut autrement prendre plusieurs jours.

<details>
<summary>Étapes d’installation hors Play Store et de vérification</summary>

1. Download a current **Android System WebView** APK — package **`com.android.webview`**. **LineageOS** System WebView is the recommended build across Android versions: 138 is the last for Android 8.1, and 150 covers Android 10+ (both in the mirror). It's vanilla Chromium, so it doesn't carry Cromite's autoplay block that stops HA camera streams. It uses the `com.android.webview` package, so it's picked as the provider automatically (no allowlist editing, no extra app), and it's open / freely redistributable. Match your panel's ABI; per-panel downloads are above.

> [!IMPORTANT]
> The simple sideload below works on panels whose ROM waives the WebView signature check (e.g. the NSPanel Pro's userdebug build). **Signature-locked panels (the TPA10, and likely other vendor user builds) reject a plain sideload** — `signatures do not match`. Those need the one-time root swap (replace the system WebView file + clear its `packages.xml` entry); see [tpa10.md → WebView](tpa10.md#webview--à-mettre-à-jour-en-premier) for the exact procedure.
2. Sideload it (no root):

   ```sh
   adb install -r android-system-webview.apk
   ```

   It installs to `/data/app` and supersedes the stale stock WebView.
3. Verify the active provider + version:

   ```sh
   adb shell dumpsys webviewupdate | grep "Current WebView package"
   ```

If the panel lists more than one provider, select it explicitly:

```sh
adb shell cmd webviewupdate set-webview-implementation com.android.webview
```

or via Developer options → *WebView implementation*.

</details>

---

Fiches par panneau : [NSPanel Pro](nspanel-pro.md) · [TPA10](tpa10.md) · [WF1589T](wf1589t.md) · [S9E](../../hardware/s9e.md) · [SMT1019](../../hardware/smt1019.md) · [ZX-SMT156](../../hardware/zx-smt156.md) · [Shelly Wall Display](../../hardware/shelly-wall-display.md).
