package com.sarthak.geofencence.geofence

import kotlin.math.*

object GeofenceCalculator {

    fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadius = 6_371_000.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a =
            sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) *
                    cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)

        val c = 2 * atan2(
            sqrt(a),
            sqrt(1 - a)
        )

        return earthRadius * c
    }

    fun isInside(
        userLat: Double,
        userLon: Double,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): Boolean {
        return distanceMeters(
            userLat,
            userLon,
            centerLat,
            centerLon
        ) <= radiusMeters
    }
}