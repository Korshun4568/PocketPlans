package ru.slava.pocketplans

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.slava.pocketplans.data.*

private val Context.preferences by preferencesDataStore(name = "preferences")
@Module @InstallIn(SingletonComponent::class)
object Injection {
    @Provides @Singleton fun database(@ApplicationContext context: Context): PocketDatabase =
        Room.databaseBuilder(context, PocketDatabase::class.java, "pocket.db").build()
    @Provides @Singleton fun preferences(@ApplicationContext context: Context) = context.preferences
    @Provides @Singleton fun clock(): Clock = Clock { System.currentTimeMillis() }
    @Provides @Singleton fun api(): CatalogApi = Retrofit.Builder().baseUrl("https://dummyjson.com/")
        .client(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(CatalogApi::class.java)
}
