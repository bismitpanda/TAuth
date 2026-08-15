package com.panda.tauth.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

@Serializable
enum class Theme {
    @SerialName("system")
    SYSTEM,

    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,
}

@Serializable
enum class SortOrder {
    @SerialName("manual")
    MANUAL,

    @SerialName("issuer")
    ISSUER,

    @SerialName("recentlyAdded")
    RECENTLY_ADDED,
}

@Serializable
data class WindowGeometry(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    // A window that has never been placed has no position, and the platform chooses where it opens.
    val x: Int? = null,
    val y: Int? = null,
) {
    companion object {
        const val DEFAULT_WIDTH = 960
        const val DEFAULT_HEIGHT = 720

        // The floors keep a window the user can act in. The ceilings sit above any display
        // arrangement, so a stored extent stays inside what a window toolkit can be given.
        const val MIN_WIDTH = 480
        const val MIN_HEIGHT = 360
        const val MAX_EXTENT = 16384
        const val MAX_COORDINATE = 16384
    }
}

// Plaintext and read before any password, so anything running as the user can rewrite it. Nothing
// here governs when the vault locks; a value out of range is coerced rather than refused.
@Serializable
data class Preferences(
    val theme: Theme = Theme.SYSTEM,
    val sortOrder: SortOrder = SortOrder.MANUAL,
    val startMinimised: Boolean = false,
    val minimiseToTray: Boolean = true,
    // This is the only place a geometry is decoded from the file, so clamping it here covers every
    // untrusted value the model can receive.
    @Serializable(with = WindowGeometrySerializer::class)
    val window: WindowGeometry = WindowGeometry(),
)

// An unknown key is ignored, and a null or an unknown enum member falls back to the property's own
// default, so a file carrying either still yields preferences the application can open with.
internal val preferencesJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    coerceInputValues = true
    prettyPrint = true
}

// The generated serializer carries the geometry's members in both directions; this one adds the
// clamp on the way back, and holds no member list of its own.
internal object WindowGeometrySerializer : KSerializer<WindowGeometry> {
    private val delegate = WindowGeometry.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: WindowGeometry) = encoder.encodeSerializableValue(delegate, value)

    override fun deserialize(decoder: Decoder): WindowGeometry = decoder.decodeSerializableValue(delegate).clamped()
}

// A size out of range is clamped. A position is kept only when both coordinates are present and land
// where a display could be, and dropped whole otherwise.
private fun WindowGeometry.clamped(): WindowGeometry {
    val coordinates = -WindowGeometry.MAX_COORDINATE..WindowGeometry.MAX_COORDINATE
    val hasPosition = x != null && y != null && x in coordinates && y in coordinates
    return copy(
        width = width.coerceIn(WindowGeometry.MIN_WIDTH, WindowGeometry.MAX_EXTENT),
        height = height.coerceIn(WindowGeometry.MIN_HEIGHT, WindowGeometry.MAX_EXTENT),
        x = if (hasPosition) x else null,
        y = if (hasPosition) y else null,
    )
}
