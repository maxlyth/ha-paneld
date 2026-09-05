> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../built-in-renderer.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Le moteur de rendu de tableau de bord intégré

> [!NOTE]
> **Expérimental (0.9).** Le moteur de rendu intégré constitue la solution intégrée pour filtrer les entités du tableau de bord. L’application HA Companion reste prise en charge lorsqu’un panneau nécessite plusieurs serveurs Home Assistant, la commande vocale Assist ou les notifications natives.

ha-paneld peut afficher le tableau de bord Home Assistant dans sa propre WebView au lieu de le confier à une application de tableau de bord distincte. Le panneau peut ainsi revenir plus rapidement à son tableau de bord après le redémarrage de l’application. Il peut rouvrir le dernier tableau de bord que Home Assistant a vérifié pour le même serveur, le même compte et le même paramètre Tableau de bord d’accueil, tout en vérifiant en arrière-plan la liste actuelle des tableaux de bord. Si Home Assistant indique que le tableau de bord a été supprimé ou que la valeur par défaut du compte a changé, le panneau passe au choix actuel.

Une fois le tableau de bord en fonctionnement, ha-paneld peut détecter une connexion bloquée, libérer la mémoire WebView accumulée et contenir les plantages du moteur de rendu. La connexion intégrée permet également de filtrer les entités du tableau de bord. Le panneau reste un appareil à application unique, avec un seul APK à installer, mettre à jour et provisionner.

## Démarrage et récupération

The renderer uses Home Assistant's documented `?external_auth=1` interface, which is the same interface used by the HA Companion app. ha-paneld can therefore tell when the dashboard has connected instead of treating the page as a black box.

- Rouvre le dernier tableau de bord vérifié après un redémarrage de l’application, tout en actualisant en arrière-plan la liste des tableaux de bord de Home Assistant. Une courte vérification de compatibilité est tout de même effectuée au préalable. La route mémorisée est liée au serveur Home Assistant, au compte et au tableau de bord configuré, et un tableau de bord ou un onglet de tableau de bord explicitement configuré fait toujours foi.
- Fige la page lorsque l’écran est éteint et la reprend au réveil, ce qui économise environ 70 % de la charge processeur du moteur de rendu pendant la nuit.
- Recharge un tableau de bord qui s’est ouvert mais ne s’est jamais connecté. Les nouvelles tentatives s’espacent après des échecs répétés, et le panneau affiche un écran explicite **Reconnexion à Home Assistant…** au lieu d’une page d’erreur du navigateur.
- Automatically retries recoverable checks with increasing delays. A permanently rejected login stops the retry loop and shows Browser sign-in instructions. An unsupported Home Assistant version or incompatible WebView names the required update and waits for it.
- Libère la mémoire accumulée au moyen de rechargements invisibles lorsque l’écran est éteint.
- Contains and rate-limits renderer crashes. A page that continues to crash falls back to the admin launcher instead of restarting all night.
- When Home Assistant announces that it is stopping or goes offline through MQTT availability, the panel shows a native notice and clears it only after Home Assistant proves it is back.

You can pull down from the very top edge of the screen to refresh, or pull twice for a full reload. The renderer also supports an optional idle return to the Home dashboard, camera-stream autoplay and private-CA HTTPS using user-installed certificate authorities. **Hide Android system bars** provides an edge-to-edge dashboard; swipe from a screen edge to reveal the bars again. On panels using ha-paneld's software navigation bar, **Dashboard** brings the configured renderer to the foreground without reloading it. **Reload** remains a separate recovery action.

Le moteur de rendu dimensionne le tableau de bord de la même manière que l’application Home Assistant Companion ; le passage depuis Companion conserve donc la mise en page. **Zoom (%)** ajuste le résultat, 100 % correspondant à la valeur par défaut de Companion. Le moteur de rendu ajoute une entrée **Paramètres de l'application** dans la barre latérale de Home Assistant, qui ouvre la page de configuration du panneau. Lors de la première exécution, il masque la barre latérale ancrée et maintient la connexion active pendant les périodes d’inactivité. Vous pouvez toujours ouvrir la barre latérale ou modifier ces valeurs par défaut ultérieurement. L’option distincte **Masquer la navigation Home Assistant (native)** demande à l’interface de supprimer sa navigation lorsque le mode kiosque natif est actif.

## Configuration requise et compatibilité

À partir de ha-paneld 0.9.6, le moteur de rendu intégré nécessite les deux éléments suivants :

- **Home Assistant 2026.4.2 ou version ultérieure** ; et
- une Android System WebView prenant en charge l’écouteur WebMessage sécurisé utilisé par l’interface d’hôte natif de Home Assistant.

La plupart des utilisateurs ont seulement besoin d’une Android System WebView à jour. ha-paneld vérifie la fonctionnalité WebView requise et la compatibilité de Home Assistant avant de charger le tableau de bord.

If the panel shows **Home Assistant upgrade required**, upgrade Home Assistant and select **Retry**. Nothing on the panel substitutes for that.

S’il affiche **Le navigateur de ce panneau est trop ancien**, l’écran indique ce que ce panneau particulier peut faire pour y remédier, car cela dépend du modèle et de la configuration du panneau :

- **The panel can repair itself.** When a known-good Android System WebView is pinned in the panel profile and ha-paneld is permitted to install it, the screen offers **Update the web viewer**. Select it and the panel downloads and installs that version, then ha-paneld restarts once to use it. If the screen comes back afterwards, the pinned version did not resolve the fault and the manual routes below still apply.
- **The panel cannot, and the screen says why.** Once ha-paneld has confirmed that automatic repair is unavailable, it names one of three reasons, and the update has to be done by hand, after which you select **Retry**: a known-good version is pinned but ha-paneld is not permitted to install it; no known-good version is pinned for this panel; or the panel takes its Android System WebView from a store, which will replace it more safely than ha-paneld would. Reinstalling the same version repairs a damaged one.

How Android System WebView is updated by hand depends on the panel: some take it from Google Play, others only from a vendor firmware update or a manually installed build.

The built-in renderer does not fall back to the older, less isolated bridge. Another renderer may help when Home Assistant itself cannot be upgraded. The Companion app uses the same system WebView, so it cannot bypass an obsolete WebView on the panel.

## Activation

On a new or reset panel, open `http://<panel>:8888/setup` from a laptop or phone, or select **Set up** on the panel itself. The guided journey chooses the renderer, signs in to Home Assistant, selects the account default, a dashboard or a specific dashboard tab, and asks about the entity filter before the first dashboard load. Authorization happens in the administrator's browser, so credentials do not need to be typed on the panel.

Sur un panneau existant, ouvrez la page `:8888` **Configurer** du panneau. Sous **Connexion à Home Assistant**, saisissez l’URL de Home Assistant et choisissez **Connexion par navigateur**, puis sélectionnez **Moteur de rendu intégré** comme Application du tableau de bord.

Existing rooted installations that already imported a signed-in Companion session remain supported as a compatibility path. New installations should use Browser sign-in.

Pour une configuration sans surveillance depuis une machine d’administration, remplacez l’adresse d’exemple du panneau et les informations de Home Assistant dans cette commande ne nécessitant pas de dépôt local (voir [Provisionnement](provisioning.md)) :

```bash
# First create an owner-only password file as shown in the linked provisioning guide.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt
```

The password never reaches the panel because the login happens on your machine. The panel holds a revocable refresh token. A long-lived access token works too: `--ha-token-file ha-token.txt` instead of `--ha-user/--ha-pass-file`. See [Provisioning and fleet updates](provisioning.md) for securely creating credential files and the trusted-LAN transport boundary. Literal `--ha-pass` and `--ha-token` values remain compatibility options, but expose the value in the original shell command and process list.

For automated provisioning, a token or username/password flow remains available as an advanced fallback. Interactive installations should use Browser sign-in.

## Apparence du tableau de bord par rapport au verrouillage Android

La section avancée de Configurer propose trois commandes indépendantes. Chacune agit sur une couche différente :

| Option Configurer | Ce qu’elle modifie | Ce qui n’est **pas** modifié |
|---|---|---|
| **Masquer la navigation Home Assistant (native)** (activé par défaut) | After Home Assistant connects, asks its native frontend to hide its navigation. Built-in renderer only. | Does not lock Android, hide Android system bars, or inject or modify dashboard CSS. If Home Assistant rejects or does not support the command, the dashboard is left unchanged. |
| **Masquer les barres système Android** (activé par défaut) | Hides Android's status and navigation bars for an edge-to-edge dashboard. Swipe from an edge to reveal them. Built-in renderer only. | Does not prevent someone leaving the app and does not hide Home Assistant's own menus/navigation. |
| **Verrouiller Android sur le tableau de bord (expérimental)** (désactivé par défaut) | With root, hides Android system bars and returns to the selected dashboard within about three seconds when another app or Recents opens. This is a casual-use deterrent, not an adversarial security boundary. | Does not change the Home Assistant dashboard appearance. It has no effect without root. Reboot provides a 60-second unlocked recovery window, then the saved lock is reasserted. |

For a cleaner dashboard, start with **Hide Home Assistant navigation (native)** and/or **Hide Android system bars**. Enable **Lock Android to dashboard** only when discouraging casual escape from the app is required and you have tested the documented release routes: Configure, the Home Assistant switch, adb, seven rapid taps in the top-left corner, or the unlocked window after reboot.

## Filtre d’entités expérimental

> [!WARNING]
> This is an opt-in tester feature. Automatic learning cannot prove every custom-card or dynamic-template dependency, and an incomplete entity set can leave cards missing or stale. Review it on a non-critical panel first and keep the filter-disable rollback available.

The filter applies only to ha-paneld's built-in renderer. It changes the frontend's Home Assistant subscription, so Home Assistant filters the states before serializing and sending them to the panel. The Companion app and other dashboard applications are unaffected.

### Flux de travail automatique

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Entity filtering**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and use its controls, pop-ups and conditional content so ha-paneld can observe runtime dependencies.
4. Review the current, suggested and excluded lists. Pin entities used indirectly by custom cards or templates, and resolve any entity-filter checks shown above the tables.
5. Select **Apply policy set** when the candidate is ready. ha-paneld shows the old and new entity counts before asking for confirmation, then reloads the dashboard with the filtered subscription.

The Entities page explains why each entity was found, records manual pin and exclusion choices, and keeps recognized broad or dynamic rules visible until the user fixes them or explicitly chooses how to proceed. Unrecognized behavior can still exist, so test every dashboard tab after activation. If anything is missing, turn off **Entity filtering** in Configure and reload before revising the candidate.

### Lorsque le panneau maintient le tableau de bord en attente

With automatic filtering on, the built-in renderer never opens Home Assistant unfiltered. Until a scan has produced a set it can vouch for, the panel shows a native hold screen instead of the dashboard, and the hold has three distinct causes. While the first scan is running or has failed, the panel retries it on a widening schedule, because the usual reason is that Home Assistant is not up yet. If Home Assistant rejects the panel's credential, the hold names the sign-in. If the scan finished and found a rule it cannot bound, such as a strategy-generated dashboard or an unbounded selector, the hold asks for a decision: ignore the flagged rules and continue, turn the filter off, or review them on the Entities page. That decision can be made at the panel or from any device on the network at `http://<panel>:8888/entities`, and the hold screen shows that address.

A hold that is waiting on a decision is settled, so the panel does not rescan the catalogue while it waits. It asks Home Assistant whether the dashboard changed, five minutes after the hold settles and then at most hourly, and rescans only when the dashboard's configuration or the account default has actually changed, when the decision is made, or when the panel's Home Assistant settings change. `GET /api/v1/dashboard/entities/sync` reports the cause in `hold_reason` (`synchronizing`, `synchronization`, `authentication` or `decision`) and sets `resync_suspended` while a decision is the only thing outstanding.

An update can force the panel to re-check a dashboard it was already filtering. When that re-check flags a rule on a dashboard the panel had already been running a filter on, the panel records the rule as ignored, restores the entity set it was running, and opens the dashboard rather than hold it; the rule stays visible on the Entities page and can be re-enabled there. This applies only to the re-check an update forces, only when a previously accepted filter exists, and only when the restored set is not empty. Rules the panel can never ignore, such as a dashboard too large to diagnose, still hold the renderer.

### Modèles et épinglages manuels

ha-paneld does not run dashboard templates, so it cannot know which entities a template returns, and it does not guess. What it does with the two kinds of entity a template touches is deliberately different.

Entities a template only **reads**, such as a state tested as a condition, need nothing. Home Assistant renders the template itself and sends the panel the result, over a separate subscription the entity filter does not touch. Those entities are supposed to be absent from the lists on the Entities page, and adding them would only make the subscription larger for no benefit.

Entities a template **returns** are different. They become cards on the dashboard, which read their state through the filtered subscription, so they do have to be in it. ha-paneld cannot discover them without running the template, and choosing to continue past an entity-discovery check does not add them either; that choice only lets automatic updates carry on without them.

To add one, type any part of its name or ID into the search box at the top of the Entities page. The search covers the complete Home Assistant catalogue rather than only the entities already found, and reports how many matches each table holds. Set every entity you need to **Pinned**. A manual pin is kept until you remove it, including across dashboard changes and rescans.

### Réinitialiser les données apprises

Use **Reset learned data** on the Entities page when obsolete dashboard evidence or earlier manual decisions make the candidate misleading. After explicit confirmation it clears learned dashboard membership and evidence, manual pin/exclude overrides, and ignored safety decisions. It preserves the known-good active filter, keeps the Home Assistant catalog used for candidate names, and starts a replacement scan when learning is enabled. This makes reset a rebuild operation rather than an immediate expansion back to the full Home Assistant state stream.

The stronger API reset below can also remove the stored active filter by sending `clear_filter:true`. Use it only when the filter itself must be discarded.

### Liste exacte manuelle

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

Téléversez la liste complète sur le panneau :

```bash
PANEL_IP=192.0.2.10
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data @entity-filter.json \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

Le moteur de rendu intégré se recharge après une mise à jour. Une fois le tableau de bord reconnecté, vérifiez l'état :

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

## Thèmes

**Thème du tableau de bord** (Configurer → Moteur de rendu intégré) détermine qui choisit le mode clair ou sombre :

- **Follow Home Assistant** (option par défaut) laisse le choix à Home Assistant. Le panneau fournit uniquement un point de départ : sous Android 13+, celui-ci suit le réglage du système en temps réel ; sous Android 10-12, il suit le réglage du système au chargement du tableau de bord ; sous Android 9 et versions antérieures, le bouton « Mode sombre » (Configurer → Affichage) le définit. Un thème sélectionné dans Home Assistant prévaut sur ce point de départ.
- **Dark** et **Light** confient le choix au panneau. Cette option est destinée à un tableau de bord en mode kiosque dont la barre latérale est masquée et depuis lequel la page de profil Home Assistant est totalement inaccessible.

Forcer un thème ne modifie que la partie claire/sombre du choix. Un thème nommé et ses couleurs restent exactement tels quels. Revenir à Follow Home Assistant rétablit la partie claire/sombre à sa valeur précédente, ou à Automatique s’il n’y en avait aucune. Le panneau ne modifie jamais le thème enregistré pour votre *compte* Home Assistant ; un panneau réglé sur Dark ne peut donc pas assombrir votre téléphone.

Il existe un cas où le panneau ne peut pas imposer son choix : si cet utilisateur Home Assistant a explicitement choisi Clair ou Sombre (plutôt qu’Automatique), ce choix reste prioritaire, car le remplacer reviendrait à modifier un réglage partagé avec tous les autres appareils auxquels cet utilisateur se connecte. Réglez le thème de l’utilisateur sur Automatique ou utilisez un utilisateur Home Assistant distinct pour le panneau afin que le choix du panneau s’applique. Lorsque cela se produit, le panneau le signale plutôt que de rester silencieux : la carte **Diagnostics d’exécution** des pages `:8888` indique que le thème de Home Assistant remplace le Thème du tableau de bord, et `GET /api/v1/status` le signale sous `renderer` avec la valeur `theme_overridden: true`, accompagné de `theme_policy` et `theme_effective`, tandis que le correctif est indiqué dans `action`.

L’interface web `:8888` est indépendante de tout cela et suit toujours le navigateur dans lequel vous la consultez.

## Rétablissement

Open Configure, select an installed Home Assistant Companion app under **Dashboard app** and save the change. The switch takes effect immediately. Do not select **Auto** for this purpose because Auto uses the built-in renderer when it is ready.

## Limites

- **No support for more than one Home Assistant server, Assist voice control or native notifications.** Keep the HA Companion on the panel where those matter.
- **Aucune fonctionnalité multimédia supplémentaire en plein écran**, telle qu’un sélecteur de fichiers ou une lecture de type diffusion. Ces fonctionnalités sont définitivement hors périmètre ; utilisez Companion si elles sont nécessaires.
- A **current system WebView** is still required to render the Home Assistant frontend. ha-paneld can install a known-good WebView on supported rooted panels; an obsolete WebView produces a health warning in the `:8888` interface.
- Browser sign-in and advanced non-interactive provisioning work without root. Legacy Companion-session import requires root and remains only for existing installations.
