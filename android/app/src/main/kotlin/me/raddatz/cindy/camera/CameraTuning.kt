package me.raddatz.cindy.camera

/** Pure decisions about the capture configuration, kept free of camera types for unit tests. */
object CameraTuning {
    /**
     * The AE target frame-rate range for [fps], from the device's `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`.
     *
     * iOS pins min and max frame duration to 1/fps whenever a supported range contains fps. The
     * fixed range [fps, fps] is the exact equivalent; devices that do not list it get the range
     * containing fps with the highest lower bound, the closest they allow. `null` when no range
     * contains fps — then the device default stays, as on iOS.
     */
    fun targetFpsRange(available: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? {
        available.firstOrNull { it.first == fps && it.second == fps }?.let { return it }
        return available
            .filter { it.first <= fps && fps <= it.second }
            .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { -it.second })
    }
}
