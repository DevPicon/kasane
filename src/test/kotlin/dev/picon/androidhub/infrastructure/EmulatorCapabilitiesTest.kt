package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.EmulatorCommand
import dev.picon.androidhub.core.FoldPosture
import dev.picon.androidhub.core.DisplaySize
import dev.picon.androidhub.core.DisplaySurface
import kotlin.test.*

class EmulatorCapabilitiesTest {
    @Test
    fun `screen corners map back to natural panel coordinates in every orientation`() {
        val normal = DisplaySurface(DisplaySize(101, 201))
        val landscape = DisplaySurface(DisplaySize(201, 101), rotation = 1)
        assertEquals(0 to 0, pointerCoordinates(0f, 0f, normal))
        assertEquals(100 to 200, pointerCoordinates(1f, 1f, normal))
        assertEquals(100 to 0, pointerCoordinates(0f, 0f, landscape))
        assertEquals(0 to 200, pointerCoordinates(1f, 1f, landscape))
        assertEquals(100 to 200, pointerCoordinates(0f, 0f, normal.copy(rotation = 2)))
        assertEquals(0 to 0, pointerCoordinates(1f, 1f, normal.copy(rotation = 2)))
        assertEquals(0 to 200, pointerCoordinates(0f, 0f, landscape.copy(rotation = 3)))
        assertEquals(100 to 0, pointerCoordinates(1f, 1f, landscape.copy(rotation = 3)))
    }

    @Test
    fun `handset has navigation and rotation without foldable controls`() {
        val controls = hardwareCapabilities(mapOf("hw.accelerometer" to "yes", "hw.sensor.hinge" to "no"))
        assertTrue(controls.supports(EmulatorCommand.Home))
        assertTrue(controls.supports(EmulatorCommand.Rotate))
        assertTrue(controls.postures.isEmpty())
        assertFalse(controls.supports(EmulatorCommand.SetPosture(FoldPosture.OPEN)))
    }

    @Test
    fun `unnamed foldable exposes only its declared postures`() {
        val controls = hardwareCapabilities(mapOf("hw.sensor.hinge" to "true", "hw.sensor.posture_list" to "1, 3, 5, 99"))
        assertEquals(setOf(FoldPosture.FOLDED, FoldPosture.OPEN, FoldPosture.TENT), controls.postures)
        assertFalse(controls.rotation)
        assertFalse(controls.supports(EmulatorCommand.SetPosture(FoldPosture.HALF_OPEN)))
    }

    @Test
    fun `missing posture list never invents capabilities`() {
        assertTrue(hardwareCapabilities(mapOf("hw.device.name" to "pixel_fold", "hw.sensor.hinge" to "yes")).postures.isEmpty())
        assertTrue(hardwareCapabilities(mapOf("hw.sensor.posture_list" to "1,2,3")).postures.isEmpty())
    }
}
