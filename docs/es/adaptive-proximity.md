> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../adaptive-proximity.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Proximidad adaptativa y activación con un gesto de la mano

Los sensores de proximidad de los paneles de pared Android no comparten una única escala útil. Algunos indican la distancia, otros solo indican dos valores, la polaridad puede variar y las lecturas en reposo pueden fluctuar entre unidades o versiones de firmware. ha-paneld aprende la línea base despejada, la referencia de proximidad, la polaridad y el estilo de notificación del sensor conectado, en lugar de requerir umbrales específicos para cada modelo.

## Aprendizaje y entrenamiento

Abra **Configurar → Presencia y activación** para ver la fase actual. El aprendizaje se ejecuta localmente y normalmente no requiere configuración:

1. Manténgase alejado del panel mientras identifica la señal en reposo y la línea base de la estancia.
2. Use el panel con normalidad para que se puedan distinguir los acercamientos y alejamientos intencionados de las pequeñas fluctuaciones en reposo.
3. Si resulta útil agilizar la configuración, seleccione **Entrenar un gesto de la mano** y realice tres gestos intencionados cuando se le indique.
4. Cuando esté listo, **Probar un gesto de la mano** comprueba un gesto sin activar la pantalla.

Touch-to-wake remains available throughout learning. **Forget learned proximity** deletes the evidence for that sensor and returns it to the learning journey; use it after moving the panel, changing firmware or replacing the sensor route.

## Entidades de Home Assistant

Cuando el modelo es fiable y el perfil activo expone una fuente de proximidad utilizable, Home Assistant recibe:

- `binary_sensor.<panel>_proximity` como ocupación de cerca/lejos determinada mediante aprendizaje; y
- `sensor.<panel>_proximity_level` como un valor de 0 a 100 normalizado para toda la flota, donde 0 corresponde a lejos y 100, a cerca.

Las entidades permanecen no disponibles mientras las evidencias sean insuficientes o la ruta del sensor no funcione correctamente. ha-paneld no publica un valor que parezca fiable a partir de un modelo que no lo es. Los sensores exclusivamente binarios pueden seguir proporcionando ocupación y gestos de activación, mientras que solo se publica un nivel normalizado significativo cuando la señal observada lo permite.

## Activación con un gesto de la mano

**Activación con un gesto de la mano** reconoce un único movimiento acotado lejos→cerca→lejos. La presencia prolongada, el entrenamiento, las pruebas y las pequeñas fluctuaciones en reposo no activan la pantalla. Esto evita interpretar a una persona que permanezca cerca del panel, o a un sensor denso y ruidoso, como un flujo de solicitudes de activación.

La función necesita una fuente de proximidad activa y un modelo aprendido que esté listo, pero la propia fuente puede ser un sensor Android normal o una ruta auxiliar seleccionada por el perfil. La UI web y los diagnósticos indican la ruta real y el estado de preparación; una declaración de perfil por sí sola no crea la disponibilidad del sensor.
