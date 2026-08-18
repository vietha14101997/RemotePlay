package com.reka.remoteplay.feature.connection.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore(name = "pairing_store")

/**
 * Fingerprints/labels are NOT secret (public in SDP) — plain storage per contract, mirroring the
 * Host's `%APPDATA%\RemoteScreen\pairing.json`. `fingerprint` is the full `a=fingerprint:` value
 * (e.g. "sha-256 AB:CD:...") of a host this device has successfully paired with.
 */
@JsonClass(generateAdapter = true)
data class PairedHost(
    val fingerprint: String,
    val label: String = "",
    val pairedAt: Long = 0
)

/**
 * Pure list-merge/serialization logic, deliberately separated from [PairingStore] (which needs a
 * Context/DataStore) so it's unit-testable on the plain JVM without Robolectric.
 */
object PairedHostListCodec {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, PairedHost::class.java)
    private val adapter = moshi.adapter<List<PairedHost>>(listType)

    fun decode(raw: String?): List<PairedHost> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            adapter.fromJson(raw) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(hosts: List<PairedHost>): String = adapter.toJson(hosts)

    /** Upsert by fingerprint (exact match); the freshly-paired entry is kept newest-first. */
    fun upsert(existing: List<PairedHost>, entry: PairedHost): List<PairedHost> =
        listOf(entry) + existing.filter { it.fingerprint != entry.fingerprint }

    fun remove(existing: List<PairedHost>, fingerprint: String): List<PairedHost> =
        existing.filter { it.fingerprint != fingerprint }

    fun isKnown(existing: List<PairedHost>, fingerprint: String): Boolean =
        existing.any { it.fingerprint == fingerprint }
}

/**
 * DataStore-backed persistent allowlist of paired host DTLS fingerprints (Android side of
 * `pairing-protocol-contract-v1.md`'s "Storage" section). Atomic writes are provided by
 * DataStore itself; app-private storage is the equivalent of the Host's owner-only-ACL file.
 */
@Singleton
class PairingStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private object Keys {
        val PAIRED_HOSTS = stringPreferencesKey("paired_hosts")
    }

    val pairedHosts: Flow<List<PairedHost>> = context.pairingDataStore.data.map { prefs ->
        PairedHostListCodec.decode(prefs[Keys.PAIRED_HOSTS])
    }

    suspend fun snapshot(): List<PairedHost> = pairedHosts.first()

    suspend fun isPaired(fingerprint: String): Boolean =
        PairedHostListCodec.isKnown(snapshot(), fingerprint)

    /** Called after a successful mutual proof verification — persists the host's fingerprint. */
    suspend fun recordPaired(fingerprint: String, label: String = "") {
        context.pairingDataStore.edit { prefs ->
            val updated = PairedHostListCodec.upsert(
                PairedHostListCodec.decode(prefs[Keys.PAIRED_HOSTS]),
                PairedHost(fingerprint, label, System.currentTimeMillis())
            )
            prefs[Keys.PAIRED_HOSTS] = PairedHostListCodec.encode(updated)
        }
    }

    /** Unpair path — removes a host fingerprint so the next connection requires re-pairing. */
    suspend fun unpair(fingerprint: String) {
        context.pairingDataStore.edit { prefs ->
            val updated = PairedHostListCodec.remove(
                PairedHostListCodec.decode(prefs[Keys.PAIRED_HOSTS]),
                fingerprint
            )
            prefs[Keys.PAIRED_HOSTS] = PairedHostListCodec.encode(updated)
        }
    }
}
