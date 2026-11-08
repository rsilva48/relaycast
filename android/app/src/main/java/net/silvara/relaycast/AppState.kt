package net.silvara.relaycast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One person on this TV: a relay identity (token) approved from a phone. */
@Serializable
data class Profile(val id: String, val name: String, val token: String)

/** Whether the TV has a network connection, for clearer messages. */
object Connectivity {
    @Volatile
    var isOnline: Boolean = true
        private set

    fun start(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        isOnline = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
            }

            override fun onLost(network: Network) {
                isOnline = false
            }
        })
    }
}

/**
 * Relay address and profiles, saved on the device. Android keeps app data
 * per device user, so on Google TV every Google profile has its own list.
 */
class AppState(context: Context) {
    private val storage = context.getSharedPreferences("relaycast", Context.MODE_PRIVATE)
    private val profilesSerializer = ListSerializer(Profile.serializer())

    var relayUrl by mutableStateOf(storage.getString("relayUrl", null))
        private set
    var profiles by mutableStateOf(loadProfiles())
        private set
    var activeProfileId by mutableStateOf(storage.getString("activeProfile", null))
        private set

    /** Identifies this TV to the relay (for remote control from the phone). */
    val deviceId: String = storage.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        storage.edit().putString("deviceId", it).apply()
    }

    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileId }

    fun client(profile: Profile): RelayClient = RelayClient(relayUrl.orEmpty(), profile.token)

    /** A client without identity, for health checks and pairing. */
    fun anonymousClient(url: String = relayUrl.orEmpty()): RelayClient = RelayClient(url, "")

    fun setRelay(url: String) {
        if (url != relayUrl) {
            // Identities belong to one relay.
            val editor = storage.edit()
            profiles.forEach { editor.remove("history.${it.id}") }
            editor.apply()
            profiles = emptyList()
            activeProfileId = null
        }
        relayUrl = url
        save()
    }

    fun addProfile(name: String, token: String): Profile {
        // Pairing the same identity again only renames it.
        val existing = profiles.firstOrNull { it.token == token }
        val profile = existing?.copy(name = name) ?: Profile(UUID.randomUUID().toString(), name, token)
        profiles = profiles.filterNot { it.id == profile.id } + profile
        activeProfileId = profile.id
        save()
        return profile
    }

    /** The name from the relay (set on a phone) or typed on the TV. */
    fun renameProfile(profile: Profile, name: String) {
        if (name.isBlank() || profiles.none { it.id == profile.id && it.name != name }) return
        profiles = profiles.map { if (it.id == profile.id) it.copy(name = name) else it }
        save()
    }

    fun removeProfile(profile: Profile) {
        storage.edit().remove("history.${profile.id}").apply()
        profiles = profiles.filterNot { it.id == profile.id }
        if (activeProfileId == profile.id) activeProfileId = null
        save()
    }

    fun selectProfile(profile: Profile?) {
        activeProfileId = profile?.id
        save()
    }

    private fun loadProfiles(): List<Profile> = storage.getString("profiles", null)
        ?.let { runCatching { RelayClient.json.decodeFromString(profilesSerializer, it) }.getOrNull() }
        ?: emptyList()

    private fun save() {
        storage.edit()
            .putString("relayUrl", relayUrl)
            .putString("profiles", RelayClient.json.encodeToString(profilesSerializer, profiles))
            .putString("activeProfile", activeProfileId)
            .apply()
    }
}
