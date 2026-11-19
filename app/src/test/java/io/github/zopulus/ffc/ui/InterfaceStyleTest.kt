package io.github.zopulus.ffc.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceStyleTest {
    @Test
    fun missingOrUnknownPreferenceDefaultsToCoui() {
        assertEquals(InterfaceStyle.COUI, InterfaceStyle.fromPreference(null))
        assertEquals(InterfaceStyle.COUI, InterfaceStyle.fromPreference("unsupported"))
    }

    @Test
    fun couiIsTheFirstStyleOption() {
        assertEquals(InterfaceStyle.COUI, InterfaceStyle.entries.first())
    }

    @Test
    fun savedStyleIsRestoredAcrossLaunches() {
        assertEquals(InterfaceStyle.MD3, InterfaceStyle.fromPreference("md3"))
        assertEquals(InterfaceStyle.COUI, InterfaceStyle.fromPreference("coui"))
    }
}
