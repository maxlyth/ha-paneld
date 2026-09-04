> [!IMPORTANT]
> This document is machine-generated and automatically cross-checked, but it has not been systematically reviewed by speakers of this language. The English documentation is authoritative. [Read the English source](../../README.md) or [open a translation correction issue](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../../app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="../../app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![Version](https://img.shields.io/github/v/release/maxlyth/ha-paneld?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/maxlyth/ha-paneld/releases)
[![Licence](https://assets.ha-paneld.com/docs/badge/license-apache-2-0-8aa187e4.svg)](../../LICENSE)

<!-- docs-i18n-language-picker:start -->
[English](../../README.md) · [Deutsch](../de/README.md) · **Français** · [Italiano](../it/README.md) · [Español](../es/README.md) · [简体中文](../zh-Hans/README.md)
<!-- docs-i18n-language-picker:end -->

**L’application universelle de tableaux de bord Home Assistant pour les écrans muraux Android.**

ha-paneld rend les tableaux de bord Home Assistant pratiques sur des écrans qui, autrement, semblent trop lents ou peu commodes à utiliser. Les écrans peu puissants peuvent ralentir ou mettre plusieurs secondes à répondre lorsqu’ils sont connectés à une installation Home Assistant de grande taille. L’une des principales causes est que l’écran reçoit et traite les mises à jour de bien plus d’entités que son tableau de bord n’en affiche. **Le moteur de rendu intégré de ha-paneld peut déterminer quelles entités sont utilisées par le tableau de bord et demander à Home Assistant de n’envoyer que leurs états**. En conditions réelles, cela peut réduire la charge liée aux entités de 10–100× et rendre enfin ce tableau de bord utilisable.

ha-paneld fournit également un ensemble cohérent de commandes dans Home Assistant pour différentes marques d’écrans muraux. Selon le matériel, cela peut inclure l’écran, les LED, les boutons, les capteurs, les relais et l’audio. La découverte MQTT ajoute les commandes disponibles sans configuration YAML propre à chaque appareil, tandis que le programme d’installation prend en charge la configuration d’Android.

Cette application est destinée aux écrans muraux dédiés, et non aux téléphones personnels. La prise en charge du matériel est décrite dans de simples profils YAML, ce qui permet aux propriétaires et aux fabricants d’ajouter un autre écran sans recompiler l’application.

L’interface web permet de configurer un écran, d’installer des logiciels et de déterminer ce qui ne fonctionne pas depuis un seul endroit. Ses outils de performance mesurent le temps de réponse du tableau de bord, les rechargements inattendus, la charge du CPU et du GPU, la fréquence d’horloge, la température et les processus les plus sollicités. Le programme d’installation fournit la même procédure de configuration et de mise à jour pour un ensemble hétérogène d’écrans, tandis que le lanceur intégré et la navigation à l’écran facilitent l’utilisation des écrans dépourvus de touches matérielles.

<picture>
  <source media="(prefers-color-scheme: light)" srcset="https://assets.ha-paneld.com/docs/screenshot/hero-light-a17f5f14.webp">
  <img src="https://assets.ha-paneld.com/docs/screenshot/hero-dark-aeb93099.webp" alt="Tableau de bord ha-paneld affichant en direct l’état de l’écran, les performances et les commandes d’affichage">
</picture>

<details>
<summary><strong>Captures d’écran supplémentaires</strong></summary>

| Tableau de bord | Configurer |
|---|---|
| <a href="../img/ui-dashboard-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-dashboard-light.png"><img src="../img/ui-dashboard-dark.png" alt="Onglet Tableau de bord" width="420"></picture></a> | <a href="../img/ui-configure-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-configure-light.png"><img src="../img/ui-configure-dark.png" alt="Onglet Configurer" width="420"></picture></a> |

| Entités | Installer |
|---|---|
| <a href="../img/ui-entities-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-entities-light.png"><img src="../img/ui-entities-dark.png" alt="Onglet Entités" width="420"></picture></a> | <a href="../img/ui-install-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-install-light.png"><img src="../img/ui-install-dark.png" alt="Onglet Installer" width="420"></picture></a> |

| Profil | Journaux |
|---|---|
| <a href="../img/ui-profile-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-profile-light.png"><img src="../img/ui-profile-dark.png" alt="Onglet Profil" width="420"></picture></a> | <a href="../img/ui-logs-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-logs-light.png"><img src="../img/ui-logs-dark.png" alt="Onglet des journaux" width="420"></picture></a> |

| Écran d’attente | Explorateur de l’API REST |
|---|---|
| <img src="../img/standing-screen.png" alt="Écran d’attente de ha-paneld avec l’adresse de configuration et le code QR" width="420"> | <picture><source media="(prefers-color-scheme: light)" srcset="../img/api-explorer-light.png"><img src="../img/api-explorer-dark.png" alt="Explorateur de l’API REST" width="420"></picture> |

</details>

## Installation

Si vous ne savez pas si ha-paneld peut fonctionner sur votre panneau, consultez [Panneaux et état de la prise en charge](#panneaux-et-état-de-la-prise-en-charge) avant l’installation.

Commencez par rendre ADB accessible sur le réseau. Sur certains panneaux, cette option se trouve dans les options pour les développeurs ; d’autres nécessitent une connexion USB ponctuelle pour exécuter `adb tcpip 5555`. Le [guide de provisionnement](../provisioning.md) et les [guides du matériel](../hardware/) propres à chaque modèle expliquent les méthodes disponibles. Exécutez ensuite cette commande depuis un ordinateur disposant de `adb` sur le même réseau :

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

> [!IMPORTANT]
> **Sous Windows, utilisez Git Bash ou WSL, et non PowerShell.** Le programme d’installation est un script `bash` . Git Bash est inclus avec [Git for Windows](https://gitforwindows.org/). Installez `adb` avec `winget install Google.PlatformTools`, rouvrez l’interpréteur de commandes, puis exécutez la commande. Sous macOS et Linux, elle peut être exécutée telle quelle.

Vous n’avez pas besoin de cloner le dépôt ni de fournir d’options. Le programme d’installation vérifie que `adb` et `curl` sont disponibles, demande l’adresse du panneau et explique chaque modification avant de l’effectuer. Il télécharge la dernière version stable signée, l’installe et vérifie que ha-paneld a démarré correctement.

Si une étape requise échoue, le programme d’installation indique le problème et se ferme sans prétendre que l’installation a réussi. Corrigez le problème et exécutez de nouveau la même commande.

> [!IMPORTANT]
> **Vérifiez Home Assistant et le WebView système du panneau avant le premier chargement du tableau de bord.** Le moteur de rendu intégré nécessite Home Assistant 2026.4.2 ou une version ultérieure, ainsi qu’un WebView moderne. Même un panneau neuf peut contenir un WebView trop ancien pour afficher un tableau de bord actuel. Consultez [Configuration requise du moteur de rendu intégré](../built-in-renderer.md#requirements-and-compatibility) et [Mise à jour du WebView système](../hardware/README.md#updating-the-system-webview).

Pour suivre la dernière version publiée, y compris les versions candidates, ajoutez `--prerelease`. Une version stable plus récente reste prioritaire :

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
```

Le même programme d’installation prend en charge le provisionnement d’un panneau unique sans intervention manuelle. Consultez [Provisionnement et mises à jour du parc](../provisioning.md) pour les installations automatisées par script, l’amorçage USB, les panneaux sans ADB réseau et les mises à jour de l’ensemble du parc.

ha-paneld n’est pas distribué via Google Play ; l’installation nécessite donc toujours un chargement latéral. Cela s’applique également aux panneaux récents qui ont par ailleurs accès au Play Store.

### Autres méthodes d’installation

- **F-Droid sur le panneau :** ajoutez [le dépôt F-Droid de ha-paneld](../fdroid.md) pour installer et mettre à jour les versions stables sans ordinateur. F-Droid vous avertit lorsqu’une mise à jour est disponible et vous permet de l’installer sur le panneau ; les versions candidates ne sont pas incluses. Le micrologiciel Sonoff NSPanel Pro 4.0.0 et versions ultérieures inclut F-Droid. Cette méthode installe l’application, mais les fonctionnalités nécessitant un accès root requièrent toujours les étapes de provisionnement habituelles.
- **Chargement latéral manuel ou amorçage USB :** utilisez l’APK de la [dernière version](https://github.com/maxlyth/ha-paneld/releases) et suivez [Provisionnement et mises à jour du parc](../provisioning.md) pour les autorisations et la configuration restantes.

## Choisir le mode d’exécution du tableau de bord

Utilisez le moteur de rendu intégré lorsque vous souhaitez filtrer les entités du tableau de bord. Il permet également de se connecter depuis un autre navigateur, de sélectionner un onglet précis du tableau de bord et d’accélérer le démarrage et la récupération. Après le redémarrage de l’application, il peut rouvrir le dernier tableau de bord vérifié qui était défini par défaut pour le compte, pendant qu’il actualise en arrière-plan la liste des tableaux de bord de Home Assistant.

L’ [application Home Assistant Companion](https://github.com/home-assistant/android) officielle est également prise en charge. Utilisez-la lorsque le panneau a besoin de plusieurs serveurs Home Assistant, de la commande vocale Assist ou de notifications natives. Sur un panneau sans Google Play et disposant d’une méthode d’installation prise en charge, utilisez l’onglet Install de ha-paneld. Le sélecteur applique la limite de compatibilité de ce panneau au lieu de supposer que la dernière version de Companion fonctionnera dessus.

Les deux options restent prises en charge. Le filtrage des entités du tableau de bord fonctionne uniquement avec le moteur de rendu intégré de ha-paneld.

<a id="panels-and-support-status"></a>

## Panneaux et état de la prise en charge

ha-paneld n’a pas besoin d’être installé en tant qu’application système. Les commandes Android de base, telles que la luminosité, la navigation et la synthèse vocale, fonctionnent sur les panneaux compatibles. Les LED, les relais, l’extinction réelle de l’écran et certains capteurs nécessitent la prise en charge de ce modèle dans son [profil de panneau](../profiles/README.md). Les événements des boutons matériels nécessitent la capture par le service d’accessibilité Android ou une méthode de profil vérifiée.

| Panneau | État | Android / ABI | Remarques |
|---|---|---|---|
| Sonoff NSPanel Pro / Pro 120 | Pris en charge | Android 8.1, arm64-v8a | PX30 / rk3326-S ; le firmware d’origine fournit un accès ADB root et le provisionnement normal installe l’utilitaire root authentifié de ha-paneld |
| Tuya TPA10 | Pris en charge | Android 11, armeabi-v7a | rk3566 avec espace utilisateur 32 bits |
| Electron WF1589T | Pris en charge | Android 14, arm64-v8a | Firmware userdebug rk3576 ; `adb root`, barre de navigation Android native et commande de la LED RGB |
| ZHICAI SMT1019 | Testé par la communauté, certaines fonctionnalités sont expérimentales | Android 14, arm64-v8a | rk3576 ; le micrologiciel d’origine ne dispose d’aucun accès root accessible aux applications. L’outil auxiliaire authentifié peut fournir un accès supplémentaire au matériel là où il est installé. La précision de la régulation climatique et la prise en charge de la proximité nécessitent encore davantage de tests sur le matériel. [Problème #8](https://github.com/maxlyth/ha-paneld/issues/8) |
| ZX-SMT156 / RK3566_T | Préliminaire | Android 13, arm64-v8a | La LED RGB et la luminosité/proximité fonctionnent sans accès root. La prise en charge de la régulation climatique est facultative ; les relais et l’accès root sont encore en cours de caractérisation. [Problème #24](https://github.com/maxlyth/ha-paneld/issues/24) |
| Smatek S9E | Expérimental | Android 11, arm64-v8a | Profil pour les relais intégrés, les LED des boutons et la proximité. Une confirmation en conditions réelles sur le matériel S9E reste nécessaire. |
| Shelly Wall Display (d’origine) | Logiciel d’origine incompatible | Android 7.0, armeabi-v7a | La version d’Android est antérieure à la version minimale requise par ha-paneld. |
| Shelly Wall Display X2 | À des fins de recherche uniquement | Android 8.1, armeabi-v7a | Aucune méthode d’installation confirmée pour ha-paneld. |
| Shelly Wall Display X1i / X2i / XL | Recherche uniquement | Android 11, arm64-v8a | Les métadonnées de profil doivent encore être séparées par modèle. Aucun chemin d’installation de ha-paneld n’est confirmé. |

Consultez la [documentation du matériel](../hardware/) pour connaître la configuration propre à chaque modèle, les limitations connues et les détails du matériel obtenus par rétro-ingénierie.

## Fonctionnalités de contrôle du matériel

Chaque panneau publie uniquement les commandes prises en charge par son profil et le matériel détecté. Leurs noms et leur comportement restent cohérents entre les modèles.

| Fonctionnalité | Contrôle via Home Assistant ou l’API |
|---|---|
| Luminosité de l’écran | `light.<panel>_screen` luminosité |
| Écran allumé/éteint | `light.<panel>_screen` allumé/éteint ; extinction réelle de l’écran lorsque le profil la prend en charge, sinon réduction sûre de la luminosité |
| LED RGB | `light.<panel>_led` sur les panneaux équipés d’un matériel LED pris en charge |
| Boutons matériels | `event.<panel>_button` lorsque la capture via l’accessibilité Android ou une méthode de profil vérifiée est disponible |
| Luminosité ambiante et proximité | `sensor.<panel>_illuminance`, `binary_sensor.<panel>_proximity` et un `sensor.<panel>_proximity_level` normalisé de 0 (loin) à 100 (près) |
| Luminosité adaptative | Apprentissage facultatif sur sept jours à partir du capteur de luminosité du panneau ou d’une entité d’éclairement Home Assistant |
| Ouvrir une URL | `text.<panel>_navigate` |
| Commandes du tableau de bord et redémarrage | Boutons Home Assistant, ainsi que les actions Dashboard, Reload et de navigation dans le panneau Controls distant |
| Audio TTS et annonces | `POST /play` et `number.<panel>_volume`; consultez le [guide TTS](../tts.md) |
| Capture du tableau de bord et appui à distance | Les panneaux disposant d’une méthode de capture d’écran prise en charge peuvent afficher et actualiser l’écran depuis l’onglet Dashboard ; le mode assoupli permet également de renvoyer un clic au panneau |
| Informations et configuration du panneau | Ouvrez `http://<panel>:8888/`, également accessible via **Visiter** sur la page de l’appareil Home Assistant |

Home Assistant découvre ces commandes via MQTT sans YAML. Les principales familles d’entités, l’API HTTP et les détails de l’association sont présentés dans [docs/api.md](../api.md). Vous pouvez également parcourir et essayer l’API HTTP d’un panneau à l’adresse `http://<panel>:8888/api`.

## Sécurité et accès root

### Mode de sécurité renforcée

Le mode assoupli est activé par défaut et destiné à un réseau domestique de confiance. Utilisez le [mode de sécurité renforcée](../security-mode.md) lorsque des appareils moins fiables partagent le réseau. Le mode de sécurité renforcée nécessite un accès physique au panneau. Une personne doit approuver les actions à distance à fort impact sur l’écran du panneau ; elles ne peuvent pas être approuvées à distance. Les captures d’écran restent consultables, mais les appuis à distance sont désactivés. Ce réglage doit être activé séparément sur chaque panneau et n’est pas copié lors d’une sauvegarde, d’une restauration ou d’un provisionnement de parc.

### Fonctionnalités nécessitant un accès root

Certains composants matériels du panneau sont masqués aux applications Android ordinaires et nécessitent donc un accès root. La disponibilité de cet accès dépend du micrologiciel du panneau, et non de ha-paneld. Certains panneaux exposent `su`; sur d’autres, le programme d’installation peut ajouter le petit assistant root de ha-paneld. Cet assistant ne fournit ni shell généraliste ni accès illimité aux fichiers.

L’interface web signale les commandes indisponibles par un cadenas et explique ce qui manque au panneau. Le programme d’installation et les diagnostics indiquent également le niveau d’accès disponible.

**Aucun accès root requis :** association à Home Assistant, luminosité et atténuation de l’écran, annonces audio, les deux options de tableau de bord, interface web, API REST, ainsi que sauvegarde et restauration de la configuration. Retour, Récents, réveil par geste et barre de navigation logicielle dépendent de la fonctionnalité Android ou du capteur correspondant, mais ne nécessitent pas intrinsèquement d’accès root.

**Un accès root ou l’assistant authentifié peut être nécessaire :** extinction physique du rétroéclairage, mise en veille Android si le profil la sélectionne, commande de la LED RGB sur certains panneaux, contrôle de l’application du fournisseur, redémarrage et gouverneur du processeur. Si le profil actif ne dispose d’aucun moyen sûr d’éteindre complètement l’écran, ha-paneld en réduit plutôt la luminosité.

**Un accès direct à `su` dans ha-paneld reste nécessaire :** verrouillage d’Android sur le tableau de bord, journaux système complets, commande des relais lorsque le profil l’exige et ancien chemin d’importation de session Companion. Une sauvegarde complète peut inclure une connexion Companion existante, qui passe toujours par l’assistant authentifié : le protocole limité aux descripteurs est l’unique chemin, y compris sur les panneaux avec accès root direct.

Il existe une [solution de repli avancée](../provisioning.md#shizuku-fallback-for-unrooted-panels) limitée pour les panneaux réellement dépourvus d’accès root, mais elle ne fait pas partie du parcours normal pour le matériel pris en charge et ne fournit pas les fonctionnalités matérielles réservées à l’accès root.

## Guides et références

### Utilisation de ha-paneld

- [Provisionnement et mises à jour du parc](../provisioning.md): installation sans intervention, configuration d’ADB par USB et réseau, sauvegardes et mises à jour de l’ensemble du parc.
- [Moteur de rendu intégré](../built-in-renderer.md): prérequis, connexion à distance, sélection du tableau de bord, récupération et limitations intentionnelles.
- [Performances](../performance.md): découvrez pourquoi un tableau de bord est lent et mesurez l’effet du filtrage des entités.
- [Luminosité adaptative](../adaptive-brightness.md): sélectionnez une source lumineuse, comprenez l’apprentissage et réinitialisez l’historique après avoir déplacé un panneau.
- [Proximité adaptative et réveil d’un geste de la main](../adaptive-proximity.md): configurez la détection de proximité et apprenez le geste de réveil au panneau.
- [Modes de sécurité](../security-mode.md): comprenez le mode Assoupli et le mode Sécurité renforcée, notamment les actions qui nécessitent la présence d’une personne devant le panneau.
- [TTS](../tts.md): générez un message vocal avec un moteur TTS de Home Assistant et envoyez-le à un panneau.

### Développement et extension de ha-paneld

- [API HTTP, MQTT et Home Assistant](../api.md): points de terminaison HTTP, principales familles d’entités MQTT, association et découverte. La spécification lisible par machine est disponible sur un panneau à l’adresse `/api/v1/openapi.json`.
- [Profils de panneau](../profiles/): créez, testez et partagez la prise en charge d’un autre panneau sans recompiler l’application.
- [Références matérielles](../hardware/): configuration propre à chaque modèle, capteurs, commandes, firmware et notes de rétro-ingénierie.
- [Compilation à partir des sources](../building.md) et [développement local](../local-builds.md): compilez avec Docker, le conteneur de développement ou une chaîne d’outils Android locale.
- [Feuille de route](../roadmap.md): travaux prévus. Les travaux terminés sont consignés dans le [journal des modifications](../../CHANGELOG.md).

La page `GET /diag` du panneau génère un rapport sur le matériel, le firmware et les capacités à joindre aux rapports de bogue. Vérifiez-le et masquez les informations sensibles avant toute publication.

## Autres applications en mode kiosque

### Fully Kiosk

Je déconseille d’exécuter [Fully Kiosk Browser](https://www.fully-kiosk.com/) et ha-paneld ensemble. Les deux tenteraient de gérer l’écran, le comportement du mode kiosque et les commandes à distance, ce qui créerait deux emplacements pour configurer le même panneau.

<details>
<summary>Pourquoi je déconseille d’exécuter les deux en même temps</summary>

- Fully Kiosk est un logiciel commercial à code source fermé. Ses fonctions d’administration à distance nécessitent une [licence payante pour chaque appareil](https://license.fully-kiosk.com/license/single).
- Le filtrage des entités fait partie du moteur de rendu intégré de ha-paneld ; un navigateur distinct ne peut donc pas l’utiliser.
- Fully Kiosk se configure séparément sur chaque appareil, ce qui devient peu pratique lorsque plusieurs marques de panneaux différentes doivent se comporter de manière cohérente.

Utilisez une seule application de tableau de bord sur le panneau : le moteur de rendu intégré de ha-paneld, Companion ou un navigateur kiosque distinct s’il offre une fonctionnalité absente des deux autres.

</details>

### FreeKiosk

[FreeKiosk](https://github.com/RushB-fr/freekiosk) n’a aucun lien avec ha-paneld malgré la similitude des noms. Il est gratuit et open source, mais utilise React Native et exécute donc un autre moteur JavaScript en parallèle du tableau de bord Home Assistant. Cette charge supplémentaire peut être importante sur les panneaux qui ne disposent que de 1–2 Go de RAM.

## Discussion communautaire

Je ne souhaitais pas configurer un serveur Discord ou un espace de travail Slack pour un projet géré par une seule personne ; j’expérimente donc Matrix et Element. Rejoignez [#ha-paneld:matrix.org](https://matrix.to/#/#ha-paneld:matrix.org) dans votre client Matrix habituel, ou consultez-le sans compte dans [Element Web](https://app.element.io/#/room/#ha-paneld:matrix.org).

Ne publiez pas de configurations ni de liens vers des fichiers dans les issues ou les discussions GitHub, sauf si vous acceptez qu’ils restent publics pour toujours. Le salon Matrix est lui aussi public et lisible par tous, mais Matrix permet également d’envoyer des messages directs privés pour les informations d’assistance qui ne doivent pas faire partie d’un registre public permanent. Masquez les identifiants, les URL privées et les informations personnelles avant de publier des configurations, des journaux ou des liens vers des fichiers, où que ce soit.

## Vous souhaitez que votre panneau soit pris en charge ?

ha-paneld n’a pas de bouton de don. Il est gratuit, et le « paiement » qui le fait réellement avancer est la prise en charge de davantage de panneaux. Cela nécessite du matériel à étudier.

Commencez par le [guide des profils d’exécution](../profiles/README.md). Le profil Generic peut produire un brouillon passif que vous pouvez valider, tester et partager sans compiler l’application. Avant qu’un profil puisse être intégré à ha-paneld, j’ai encore besoin de données provenant de l’appareil réel, en particulier concernant ses boutons, LED, relais et capteurs.

Si vous souhaitez aider :

- **Créez et partagez un profil.** Ouvrez `http://<panel-ip>:8888/profiles`, téléchargez le brouillon de l’appareil Generic, puis suivez les guides de [test](../profiles/testing.md) et de [partage](../profiles/sharing.md) . Un profil communautaire peut être utile avant d’être prêt à être distribué avec ha-paneld.
- **Ouvrez une issue avec les diagnostics du panneau.** Accédez à `http://<panel-ip>:8888/diag`, vérifiez et expurgez le rapport, puis collez-le dans une nouvelle issue. Cela suffit pour commencer. Je vous accompagnerai dans une courte série de tests pour les boutons, LED, relais ou capteurs qui nécessitent la présence de quelqu’un auprès du panneau.
- **Envoyez-moi le panneau.** Je suis basé au Royaume-Uni et peux volontiers effectuer directement la rétro-ingénierie. C’est le moyen le plus rapide d’obtenir une prise en charge complète du matériel. Vous le récupérerez (j’en ai déjà beaucoup trop) ; ouvrez d’abord une issue afin que nous puissions convenir des détails.

Le résultat est toujours ouvert : votre panneau devient un profil que tout le monde peut utiliser. Voilà le don.

## Développement

Si vous souhaitez travailler sur ha-paneld lui-même, commencez par [CONTRIBUTING.md](../../CONTRIBUTING.md). La documentation destinée aux développeurs couvre la [compilation à partir du code source](../building.md), les [compilations locales et dans un conteneur de développement](../local-builds.md), l’ [API HTTP et MQTT](../api.md), le [développement de profils de panneaux](../profiles/README.md), l’ [environnement de test du navigateur](../../test/README.md)et le [processus de publication](../RELEASING.md).

J’ai délibérément fourni suffisamment d’informations pour utiliser le conteneur de développement fourni et créer une version de test locale. Ne soumettez pas sans les modifier des pull requests ou des tickets générés par ordinateur : lisez et comprenez chaque partie du texte et du code proposés, puis reformulez-les avec vos propres mots. Ce projet est géré par une seule personne et je n’ai pas le temps d’examiner des contenus générés par ordinateur sans aucun filtrage. Soyez concis et écrivez pour des humains ; si vous avez un doute, demandez d’abord.

<details>
<summary><strong>Pile technologique</strong></summary>

- **Application :** [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX](https://github.com/androidx/androidx) et [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines).
- **HTTP et WebSocket Home Assistant :** [Ktor](https://github.com/ktorio/ktor) .
- **MQTT :** [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client), avec son client MQTT 5 et son transport NIO entièrement en Java.
- **mDNS :** [JmDNS](https://github.com/jmdns/jmdns), qui publie `_ha-paneld._tcp` afin que les instances de ha-paneld puissent se trouver pour le sélecteur multipanneau. ha-paneld signale lorsque cette annonce s’interrompt et ne peut pas être rétablie.
- **Profils d’exécution :** [SnakeYAML Engine](https://github.com/snakeyaml/snakeyaml-engine) pour YAML 1.2, avec [CodeMirror](https://codemirror.net/) et son [paquet de prise en charge du langage YAML](https://github.com/codemirror/lang-yaml) dans l’éditeur de profils.
- **QR et journalisation :** [ZXing](https://github.com/zxing/zxing) pour les codes QR de configuration et [SLF4J](https://github.com/qos-ch/slf4j) pour la journalisation de Ktor et HiveMQ via Logcat.

La sélection et la mise à jour des dépendances suivent la [politique du projet relative aux dépendances et à la chaîne d’approvisionnement](../../SECURITY.md#dependency-and-supply-chain-policy).

</details>

## Traductions

Les traductions sont générées et vérifiées automatiquement. Elles n’ont pas été systématiquement relues par des locuteurs de chaque langue ; le texte anglais reste donc la référence. Si une formulation est ambiguë ou incorrecte, [ouvrez un ticket de correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

## Remerciements

Merci à **Seaky** pour [NSPanel Pro Tools](https://github.com/seaky/nspanel_pro_tools_apk), l’un des projets qui m’ont donné envie de créer ha-paneld. ha-paneld n’est pas une réimplémentation open source de NSPanel Pro Tools. Il est devenu une plateforme de panneaux muraux bien plus complète, dotée de son propre moteur de rendu de tableaux de bord, du filtrage des entités, de profils matériels d’exécution, de fonctions de diagnostic et du provisionnement pour plusieurs marques de panneaux. Les deux projets proposent désormais des ensembles de fonctionnalités très différents et ne doivent pas être considérés comme interchangeables, même sur un panneau Sonoff.

## Licence

Apache-2.0. Consultez [LICENSE](../../LICENSE).
