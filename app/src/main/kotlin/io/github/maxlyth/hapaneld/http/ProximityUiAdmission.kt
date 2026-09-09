package io.github.maxlyth.hapaneld.http

/** Calibration collection additionally requires Begin on the visible physical panel. */
internal fun proximityUiRequestAllowed(
    origin: String?, referer: String?, host: String?, fetchSite: String?, uiMarker: String?,
): Boolean = uiMarker == "1" && (!origin.isNullOrBlank() || !referer.isNullOrBlank()) &&
    (fetchSite == null || fetchSite == "same-origin") &&
    OriginGuard.allowed("POST", origin, referer, host)
