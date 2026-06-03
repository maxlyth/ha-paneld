package io.github.maxlyth.hapaneld.input

/**
 * Bridges the system-instantiated [PanelAccessibilityService] to the MQTT bridge. The a11y service
 * is created by the framework (not by us), so it can't hold a reference to the bridge directly;
 * the bridge registers a [listener] here and the service emits key events through it.
 */
object ButtonBus {
    @Volatile
    var listener: ((event: String) -> Unit)? = null

    fun emit(event: String) {
        listener?.invoke(event)
    }
}
