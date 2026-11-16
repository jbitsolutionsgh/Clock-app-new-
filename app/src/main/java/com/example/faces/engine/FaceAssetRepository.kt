package com.example.faces.engine

import android.content.Context
import android.util.Log
import com.example.core.CoreSingletons
import com.example.core.FaceDefinitionError
import com.example.core.FaceDiagnosticsProvider
import com.example.core.FaceEngineDiagnostics
import com.example.core.WffCompatibilitySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Loads and caches watch face JSON definitions from assets/faces once per process.
 * Invalid definitions are skipped safely and exposed to DiagnosticsScreen via [FaceDiagnosticsProvider].
 */
internal object FaceAssetRepository : FaceDiagnosticsProvider {

  private const val TAG = "FaceAssetRepository"
  private const val FACES_DIR = "faces"

  private val _diagnostics = MutableStateFlow(FaceEngineDiagnostics())
  override val diagnostics: StateFlow<FaceEngineDiagnostics> = _diagnostics.asStateFlow()

  @Volatile
  private var loaded = false

  @Volatile
  private var cachedDefinitions: Map<String, FaceDefinition> = emptyMap()

  /** Ensures assets/faces JSON files have been parsed and registers this repository with CoreSingletons. */
  fun ensureInitialized(context: Context) {
    CoreSingletons.faceDiagnosticsProvider = this
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      reloadLocked(context.applicationContext)
      loaded = true
    }
  }

  /** Updates the imported WFF face compatibility reports shown in Diagnostics. */
  fun updateWffDiagnostics(wffFaces: List<WffCompatibilitySummary>) {
    _diagnostics.value = _diagnostics.value.copy(wffFaces = wffFaces)
  }

  /** Returns a parsed [FaceDefinition] by id, or null if missing/invalid. */
  fun getById(id: String): FaceDefinition? = cachedDefinitions[id]

  /** Returns all loaded [FaceDefinition] instances in deterministic order. */
  fun allDefinitions(): List<FaceDefinition> = cachedDefinitions.values.toList()

  private fun reloadLocked(appContext: Context) {
    val assetManager = appContext.assets
    val files = runCatching { assetManager.list(FACES_DIR) }
      .getOrNull()
      ?.filter { it.endsWith(".json", ignoreCase = true) }
      ?.sorted()
      ?: emptyList()

    val defs = LinkedHashMap<String, FaceDefinition>()
    val errors = mutableListOf<FaceDefinitionError>()

    for (fileName in files) {
      val path = "$FACES_DIR/$fileName"
      val rawText = runCatching {
        assetManager.open(path).bufferedReader().use { it.readText() }
      }.getOrElse { err ->
        val reason = "Read failed: ${err.message ?: err.javaClass.simpleName}"
        Log.e(TAG, "Skipping $path: $reason")
        errors += FaceDefinitionError(fileName = fileName, reason = reason)
        continue
      }

      when (val result = FaceDefinitionParser.parse(rawText)) {
        is FaceDefinitionParser.ParseResult.Success -> {
          val def = result.definition
          if (defs.containsKey(def.id)) {
            val reason = "Duplicate face id '${def.id}'"
            Log.e(TAG, "Skipping $path: $reason")
            errors += FaceDefinitionError(fileName = fileName, reason = reason)
          } else {
            defs[def.id] = def
          }
        }
        is FaceDefinitionParser.ParseResult.Error -> {
          Log.e(TAG, "Invalid face definition $path: ${result.reason}")
          errors += FaceDefinitionError(fileName = fileName, reason = result.reason)
        }
      }
    }

    cachedDefinitions = defs
    _diagnostics.value = _diagnostics.value.copy(
      loadedDefinitions = defs.values.map { it.id to it.name },
      errors = errors,
    )
  }
}
