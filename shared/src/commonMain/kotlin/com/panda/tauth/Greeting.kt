package com.panda.tauth

class Greeting {
    private val platform = getPlatform()

    fun greet(): String = sayHello(platform.name)
}
