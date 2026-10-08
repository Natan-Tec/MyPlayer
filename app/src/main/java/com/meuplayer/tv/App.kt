package com.meuplayer.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/** Configura o carregador de imagens de forma leve para TVs com pouca memória. */
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Ajustes de aparência (brilho, vidro...) valem para todas as telas.
        LookStore.load(this)
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "MeuPlayer/1.0 (Android)")
                        .build()
                )
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.08).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("logos"))
                    .maxSizeBytes(60L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }
}
