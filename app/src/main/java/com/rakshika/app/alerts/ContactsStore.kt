package com.rakshika.app.alerts

import android.content.Context
import com.rakshika.app.data.model.EmergencyContact
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists the configured alert numbers to SharedPreferences (survives restart).
 * Both [com.rakshika.app.RakshikaViewModel] and
 * [com.rakshika.app.ride.RideViewModel] read the recipient list from here, so
 * there is a single on-disk source of truth.
 */
class ContactsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("rakshika_contacts", Context.MODE_PRIVATE)

    fun load(): List<EmergencyContact> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULTS.also { save(it) }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EmergencyContact(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Contact"),
                    phone = o.optString("phone", ""),
                    relation = o.optString("relation", "Contact"),
                    alertsEnabled = o.optBoolean("alertsEnabled", true)
                )
            }
        }.getOrElse { DEFAULTS }
    }

    fun save(contacts: List<EmergencyContact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("phone", c.phone)
                    .put("relation", c.relation)
                    .put("alertsEnabled", c.alertsEnabled)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** Numbers that should receive an alert SMS right now. */
    fun recipients(): List<String> =
        load().filter { it.alertsEnabled && it.phone.isNotBlank() }.map { it.phone }

    private companion object {
        const val KEY = "contacts_json"
        val DEFAULTS = listOf(
            EmergencyContact(
                id = "seed-primary",
                name = "Primary contact",
                phone = "+918707803069",
                relation = "Emergency",
                alertsEnabled = true
            )
        )
    }
}
