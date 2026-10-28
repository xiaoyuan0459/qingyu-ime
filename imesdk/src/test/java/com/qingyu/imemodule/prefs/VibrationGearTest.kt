package com.qingyu.imemodule.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class VibrationGearTest {

    @Test
    fun normalizeKeepsEveryGear() {
        for (gear in VibrationGear.SYSTEM..VibrationGear.STRONG) {
            assertEquals(gear, VibrationGear.normalize(gear))
        }
    }

    @Test
    fun normalizeClampsLegacySliderProgress() {
        assertEquals(VibrationGear.STRONG, VibrationGear.normalize(100))
        assertEquals(VibrationGear.STRONG, VibrationGear.normalize(42))
        assertEquals(VibrationGear.SYSTEM, VibrationGear.normalize(-3))
    }

    @Test
    fun gearCountMatchesLabels() {
        assertEquals(5, VibrationGear.COUNT)
        assertEquals(VibrationGear.COUNT - 1, VibrationGear.STRONG)
    }
}
