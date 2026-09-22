package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.util.UUID

object PreferencesManager {

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_LIBRARIES = "libraries"
    private const val KEY_ITEM_TYPES = "item_types"
    private const val KEY_GENRES = "genres"
    private const val KEY_RATINGS = "official_ratings"
    private const val KEY_UNPLAYED_ONLY = "unplayed_only"
    private const val KEY_FOUR_K = "four_k"
    private const val KEY_CLIENT_PACKAGE = "client_package"
    private const val KEY_DEVICE_ID = "device_id"

    lateinit var preferences: SharedPreferences
        private set

    fun init(context: Context) {
        if (!::preferences.isInitialized) {
            preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    private val json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    private fun string(key: String, default: String = "") = preferences.getString(key, default) ?: default
    private fun putString(key: String, value: String) = preferences.edit().putString(key, value).apply()
    private fun stringSet(key: String): Set<String> = preferences.getStringSet(key, emptySet()) ?: emptySet()
    private fun putStringSet(key: String, value: Set<String>) =
        preferences.edit().putStringSet(key, value).apply()

    var serverUrl: String
        get() = string(KEY_SERVER_URL)
        set(value) = putString(KEY_SERVER_URL, value.trim())

    var token: String
        get() = string(KEY_TOKEN)
        set(value) = putString(KEY_TOKEN, value.trim())

    var userId: String
        get() = string(KEY_USER_ID)
        set(value) = putString(KEY_USER_ID, value)

    var userName: String
        get() = string(KEY_USER_NAME)
        set(value) = putString(KEY_USER_NAME, value)

    var libraries: Set<String>
        get() = stringSet(KEY_LIBRARIES)
        set(value) = putStringSet(KEY_LIBRARIES, value)

    /** Empty means "let Jellyfin decide"; we default it to Movie+Series on first run. */
    var itemTypes: Set<String>
        get() = preferences.getStringSet(KEY_ITEM_TYPES, setOf("Movie", "Series"))
            ?: setOf("Movie", "Series")
        set(value) = putStringSet(KEY_ITEM_TYPES, value)

    var genres: Set<String>
        get() = stringSet(KEY_GENRES)
        set(value) = putStringSet(KEY_GENRES, value)

    var officialRatings: Set<String>
        get() = stringSet(KEY_RATINGS)
        set(value) = putStringSet(KEY_RATINGS, value)

    var unplayedOnly: Boolean
        get() = preferences.getBoolean(KEY_UNPLAYED_ONLY, false)
        set(value) = preferences.edit().putBoolean(KEY_UNPLAYED_ONLY, value).apply()

    var fourK: Boolean
        get() = preferences.getBoolean(KEY_FOUR_K, false)
        set(value) = preferences.edit().putBoolean(KEY_FOUR_K, value).apply()

    var clientPackage: String
        get() = string(KEY_CLIENT_PACKAGE)
        set(value) = putString(KEY_CLIENT_PACKAGE, value)

    /** Stable per-install id; Jellyfin ties the session to it. */
    val deviceId: String
        get() {
            val existing = string(KEY_DEVICE_ID)
            if (existing.isNotEmpty()) return existing
            val generated = UUID.randomUUID().toString()
            putString(KEY_DEVICE_ID, generated)
            return generated
        }

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty() && token.isNotEmpty() && userId.isNotEmpty()

    fun queryConfig() = QueryConfig(
        userId = userId,
        parentIds = libraries,
        itemTypes = itemTypes,
        genres = genres,
        officialRatings = officialRatings,
        unplayedOnly = unplayedOnly,
    )

    /** Any change here invalidates the cached wallpaper list. */
    fun configFingerprint(): String = listOf(
        serverUrl, userId,
        libraries.sorted().joinToString(","),
        itemTypes.sorted().joinToString(","),
        genres.sorted().joinToString(","),
        officialRatings.sorted().joinToString(","),
        unplayedOnly.toString(), fourK.toString(),
    ).joinToString("|")

    fun export(): String = buildJsonObject {
        preferences.all.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, JsonPrimitive(value))
                is Long -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is String -> put(key, JsonPrimitive(value))
                is Set<*> -> put(key, JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) }))
            }
        }
    }.toString()

    fun import(prefs: String): Boolean = try {
        val element = json.parseToJsonElement(prefs)
        if (element !is JsonObject) throw IllegalArgumentException("Expected a JSON object")
        val editor = preferences.edit()
        element.forEach { (key, value) ->
            when (value) {
                is JsonPrimitive -> when {
                    value.isString -> editor.putString(key, value.content)
                    value.booleanOrNull != null -> editor.putBoolean(key, value.boolean)
                    value.intOrNull != null -> editor.putInt(key, value.int)
                    value.longOrNull != null -> editor.putLong(key, value.long)
                }
                is JsonArray -> editor.putStringSet(
                    key,
                    value.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                )
                else -> Unit
            }
        }
        editor.apply()
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error importing preferences", e)
        false
    }

    private const val TAG = "PreferencesManager"
}
