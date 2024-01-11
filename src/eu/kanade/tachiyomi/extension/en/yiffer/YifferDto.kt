package eu.kanade.tachiyomi.extension.en.yiffer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Manga(
    val id: Int,
    val name: String,
    val tag: String,
    val artist: String,
    val state: String,
    val numberOfPages: Int,
    val userRating: Double,
)

@Serializable
data class SearchResponse(
    val comics: List<Manga>,
    val numberOfPages: Int,
    val page: Int,
)

@Serializable
data class ComicResponse(
    val name: String,
    val numberOfPages: Int,
    val artist: String,
    val id: Int,
    @SerialName("cat")
    val category: String,
    val tag: String,
    val created: String,
    val updated: String,
    val userRating: Double,
    val keywords: List<String> = listOf(),
    val previousComic: String?,
    val nextComic: String?,
)

@Serializable
data class Keyword(
    val name: String,
    val id: Int,
)

data class SearchDto(
    val search: String,
    val page: Int,
    val order: String,
    val categories: List<String>,
    val tags: List<String>,
    val keywords: List<Keyword>,
)
