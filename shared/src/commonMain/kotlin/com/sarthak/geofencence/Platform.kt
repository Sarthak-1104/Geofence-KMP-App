package com.sarthak.geofencence

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform