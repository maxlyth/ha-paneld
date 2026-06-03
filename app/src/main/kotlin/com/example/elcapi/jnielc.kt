package com.example.elcapi

/**
 * Clean-room JNI binding to the vendor `libjnielc.so` (rk3576 panel front RGB LED).
 *
 * The package `com.example.elcapi` and the method names below are dictated by the .so's exported
 * symbols (`Java_com_example_elcapi_jnielc_ledseek`, ...). They are interop-necessary facts about
 * the binary interface, NOT vendor source — ha-paneld bundles none of the vendor's code. The .so
 * itself carries no licence (all-rights-reserved) and is never committed; it is loaded at runtime
 * only if the operator has installed it on the panel (load-if-present, see Rk3576LedController).
 *
 * Channel flags for [ledseek]: 0xa1=red, 0xa2=green, 0xa3=blue. Value range 0..15 (4-bit PWM).
 * Call order: seekstart() -> ledseek()* -> seekstop(). The vendor Java also declared `*3()`
 * variants; they are omitted because they are not implemented in the shipped .so.
 *
 * No `System.loadLibrary` static initialiser here on purpose — the load is done (and its failure
 * caught) by the adapter, so a missing .so disables the capability gracefully instead of throwing
 * at class-load.
 */
object jnielc {
    external fun seekstart(): Int
    external fun seekstop(): Int
    external fun ledseek(flag: Int, progress: Int): Int
    external fun ledoff(): Int
}
