> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../adaptive-proximity.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Proximité adaptative et réveil d’un geste de la main

Les capteurs de proximité des panneaux muraux Android ne partagent pas une même échelle exploitable. Certains indiquent une distance, d’autres seulement deux valeurs, la polarité peut varier et les relevés au repos peuvent dériver d’un appareil ou d’une version du micrologiciel à l’autre. Au lieu d’exiger des seuils propres à chaque modèle, ha-paneld apprend la valeur de référence du capteur connecté lorsque la voie est libre, sa référence de proximité, sa polarité et son mode de transmission des relevés.

## Apprentissage et étalonnage guidé

Ouvrez **Configurer → Présence et réveil** pour consulter la phase actuelle. L’apprentissage s’effectue localement et ne nécessite normalement aucune configuration :

1. Tenez-vous à l’écart du panneau pendant qu’il identifie le signal au repos et la valeur de référence de la pièce.
2. Utilisez le panneau normalement afin que les approches et les éloignements intentionnels puissent être distingués des fluctuations au repos.
3. Pour accélérer la configuration, sélectionnez **Enregistrer un geste de la main**, puis effectuez trois gestes volontaires lorsque vous y êtes invité.
4. Une fois le modèle prêt, **Tester un geste de la main** vérifie un geste sans réveiller l’écran.

Touch-to-wake remains available throughout learning. **Forget learned proximity** deletes the evidence for that sensor and returns it to the learning journey; use it after moving the panel, changing firmware or replacing the sensor route.

## Entités Home Assistant

Lorsque le modèle est fiable et que le profil actif expose une source de proximité exploitable, Home Assistant reçoit :

- `binary_sensor.<panel>_proximity` pour indiquer l’occupation proche/éloignée apprise ; et
- `sensor.<panel>_proximity_level` sous la forme d’une valeur de 0 à 100 normalisée pour l’ensemble du parc, où 0 signifie « éloigné » et 100 « proche ».

Les entités restent indisponibles tant que les données sont insuffisantes ou que la voie d’accès au capteur ne fonctionne pas correctement. ha-paneld ne publie pas de valeur donnant une fausse impression de fiabilité à partir d’un modèle non fiable. Les capteurs binaires peuvent tout de même fournir la détection d’occupation et les gestes de réveil, tandis qu’un niveau normalisé pertinent n’est publié que lorsque le signal observé le permet.

## Réveil d’un geste de la main

**Réveil d’un geste de la main** reconnaît un seul mouvement délimité éloigné→proche→éloigné. Une présence prolongée, l’étalonnage guidé, le test et les brèves fluctuations au repos ne réveillent pas l’écran. Cela évite d’interpréter la présence d’une personne près du panneau, ou les relevés instables d’un capteur à signal continu, comme une succession de demandes de réveil.

Cette fonctionnalité nécessite une source de proximité active et un modèle appris prêt à l’emploi, mais la source elle-même peut être un capteur Android ordinaire ou une voie auxiliaire sélectionnée par le profil. L’UI web et les diagnostics indiquent la voie réellement utilisée et son état de préparation ; une déclaration dans le profil ne suffit pas à rendre un capteur disponible.
