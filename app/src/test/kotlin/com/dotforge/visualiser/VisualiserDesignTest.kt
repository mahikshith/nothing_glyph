package com.dotforge.visualiser

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Music visualiser, "One of my designs": the design, dim when quiet, full on the beat. */
class VisualiserDesignTest {
    private val p = DeviceProfile.Phone3

    @Test fun loudnessFollowsTheDrawnFrame() {
        assertEquals(0f, VisualiserDesign.loudness(Frame.blank(p)), 0.001f)
        assertEquals(1f, VisualiserDesign.loudness(Frame.blank(p).fill(255)), 0.001f)
    }

    @Test fun theDesignIsDimWhenQuietAndFullOnTheBeat() {
        val design = Frame.blank(p).fill(255)
        val quiet = VisualiserDesign.frame(design, 0f)
        val loud = VisualiserDesign.frame(design, 1f)
        val cell = p.ledCells.first()
        assertEquals(255, loud.pixels[cell])
        assertTrue(quiet.pixels[cell] in 40..60)
    }
}
