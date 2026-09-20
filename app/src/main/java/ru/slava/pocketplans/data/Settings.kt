package ru.slava.pocketplans.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*

data class Preferences(val dark: Boolean = false, val priceSort: Boolean = false,
    val background: Boolean = true, val ttlHours: Int = 24)
@Singleton
class Settings @Inject constructor(private val store: DataStore<androidx.datastore.preferences.core.Preferences>) {
    private val dark = booleanPreferencesKey("dark")
    private val sort = booleanPreferencesKey("price_sort")
    private val background = booleanPreferencesKey("background")
    private val ttl = intPreferencesKey("ttl_hours")
    val values: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { Preferences(it[dark] ?: false, it[sort] ?: false, it[background] ?: true, it[ttl] ?: 24) }.distinctUntilChanged()
    suspend fun dark(value: Boolean) { store.edit { it[dark] = value } }
    suspend fun priceSort(value: Boolean) { store.edit { it[sort] = value } }
    suspend fun background(value: Boolean) { store.edit { it[background] = value } }
    suspend fun ttl(hours: Int) { require(hours in listOf(1, 6, 24)); store.edit { it[ttl] = hours } }
}
