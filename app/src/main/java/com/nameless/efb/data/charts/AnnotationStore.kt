package com.nameless.efb.data.charts

import com.nameless.efb.data.db.dao.PlateAnnotationDao
import com.nameless.efb.data.db.entity.PlateAnnotationEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AnnotationType { LINE, CIRCLE, TEXT, ARROW }

/**
 * A single drawing mark on an approach plate.
 *
 * [points] are in plate pixel coordinates (as produced by [latLonToPlatePixel]).
 * [colour] is an ARGB Int. For TEXT annotations, [text] holds the content.
 */
@Serializable
data class PlateAnnotation(
    val type: AnnotationType,
    val points: List<Pair<Float, Float>>,
    val colour: Int = 0xFF_FFFF00.toInt(),  // yellow default
    val text: String = "",
)

/**
 * Persists and retrieves plate annotations keyed by Navigraph plate ID (CH-03).
 *
 * Annotations are stored as a JSON list in [PlateAnnotationEntity.annotationsJson].
 */
class AnnotationStore(private val dao: PlateAnnotationDao) {

    suspend fun loadAnnotations(plateId: String): List<PlateAnnotation> {
        val entity = dao.forPlate(plateId) ?: return emptyList()
        return try {
            Json.decodeFromString(entity.annotationsJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveAnnotations(plateId: String, annotations: List<PlateAnnotation>) {
        val json = Json.encodeToString(annotations)
        dao.insertOrReplace(
            PlateAnnotationEntity(
                plateId          = plateId,
                annotationsJson  = json,
                updatedAt        = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearAnnotations(plateId: String) {
        dao.deleteForPlate(plateId)
    }
}
