package com.g41us.movio.data.provider

class ProviderRegistry(
    val catalogProviders: List<CatalogProvider> = listOf(
        TvMazeCatalogProvider(),
        JikanAnimeProvider()
    )
) {
    fun catalogProvider(id: String): CatalogProvider? =
        catalogProviders.firstOrNull { it.id == id }
}
