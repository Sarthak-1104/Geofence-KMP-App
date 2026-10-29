package com.sarthak.geofencence

import com.sarthak.geofencence.geofence.GeofenceCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeofenceCalculatorTest {
    @Test
    fun sameCoordinatesHaveZeroDistance() {
        assertEquals(0.0, GeofenceCalculator.distanceMeters(28.4595, 77.0266, 28.4595, 77.0266), 0.001)
    }

    @Test
    fun insideAndOutsideUseMeterRadius() {
        assertTrue(GeofenceCalculator.isInside(28.4595, 77.0266, 28.4595, 77.0266, 500.0))
        assertFalse(GeofenceCalculator.isInside(28.50, 77.0266, 28.4595, 77.0266, 500.0))
    }
}
