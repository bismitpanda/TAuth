package com.panda.tauth

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
