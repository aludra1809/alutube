package org.schabi.newpipe.aether

import android.content.SharedPreferences
import java.util.Map
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SharedPreferences] for JVM tests. Mirrors the subset of the
 * Android API used by the Aether settings layer (getBoolean/getString/editor),
 * so preference round-trips can be tested without an emulator.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = ConcurrentHashMap<String, Any?>()

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun edit(): SharedPreferences.Editor = Editor()

    // --- unused in the Aether settings layer -------------------------------

    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun getAll(): MutableMap<String, *> = values
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getStringSet(key: String, defValue: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValue
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) removals += key else pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            return this
        }

        override fun commit(): Boolean {
            removals.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }

        // --- unused in the Aether settings layer ---------------------------

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
    }
}
