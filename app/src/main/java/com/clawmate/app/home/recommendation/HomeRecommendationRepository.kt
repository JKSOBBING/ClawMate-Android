package com.clawmate.app.home.recommendation

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.datamanager.DailyLogPlannerEventMapper
import com.clawmate.app.datamanager.DailyLogEntry
import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * Repository boundary for generating Home recommendations from real Android
 * data — both real Gallery items (Phase 28) and real app-private daily-log
 * entries (Phase 29). It owns the explicit-user-action generate path, the
 * configuration gate, the no-data short-circuit and the mapping from real
 * typed sources into bounded planner events.
 *
 * A real implementation is built by [HomeRecommendationFactory]; tests inject a
 * fake source/repository. No background work, service, WorkManager or
 * auto-scheduling exists here — [generate] runs only from an explicit call.
 */
interface HomeRecommendationRepository {

    /**
     * Explicitly generate recommendations from the currently-stored gallery
     * items only (Phase-28 gallery-only behaviour, kept for compatibility).
     */
    suspend fun generate(photoItems: List<GalleryPhotoItem>): HomeRecommendationGenerationResult

    /**
     * Explicitly generate recommendations from gallery items **and** real
     * daily-log entries (Phase 29). The mixed source is fed to the planner as
     * bounded events; `coveredSourceIds` then spans both gallery ids and
     * daily-log stable source refs. Zero network I/O happens when both are
     * absent. Defaults to the gallery-only path for backward compatibility.
     */
    suspend fun generate(
        photoItems: List<GalleryPhotoItem>,
        dailyLogEntries: List<DailyLogEntry>,
    ): HomeRecommendationGenerationResult = generate(photoItems)

    /** Whether the Planner backend is configured (gate used to enable the button). */
    fun isConfigured(): Boolean = true
}

/** Outcome of one explicit [HomeRecommendationRepository.generate] call. */
data class HomeRecommendationGenerationResult(
    val state: HomeRecommendationGenerationState,
    /** The bounded planner events that were actually sent (empty when none). */
    val events: List<HomeRecommendationPlannerEvent> = emptyList(),
    /** The real source ids (gallery ids + daily-log stable source refs) that produced the planner events. */
    val coveredSourceIds: List<String> = emptyList(),
)

/**
 * Real [HomeRecommendationRepository] implementing the full gating + planning
 * flow. Zero network I/O happens when the Planner backend is not configured
 * ([HomeRecommendationConfig.fromEndpointConfig] == null) or when there is no
 * usable gallery-derived input; both short-circuit to an honest typed state.
 */
class GalleryBackedHomeRecommendationRepository internal constructor(
    private val configLoad: () -> HomeRecommendationConfig?,
    private val plannerFactory: (HomeRecommendationConfig) -> HomeRecommendationPlanner,
    private val currentDateProvider: () -> String,
) : HomeRecommendationRepository {

    override fun isConfigured(): Boolean = configLoad() != null

    override suspend fun generate(photoItems: List<GalleryPhotoItem>): HomeRecommendationGenerationResult =
        generate(photoItems, emptyList())

    override suspend fun generate(
        photoItems: List<GalleryPhotoItem>,
        dailyLogEntries: List<DailyLogEntry>,
    ): HomeRecommendationGenerationResult {
        val config = configLoad()
        if (config == null) {
            return HomeRecommendationGenerationResult(state = HomeRecommendationGenerationState.NotConfigured)
        }
        val events = GalleryPlannerEventMapper.toPlannerEvents(photoItems) +
            DailyLogPlannerEventMapper.toPlannerEvents(dailyLogEntries)
        if (events.isEmpty()) {
            return HomeRecommendationGenerationResult(state = HomeRecommendationGenerationState.NoData)
        }
        val planner = plannerFactory(config)
        return try {
            val result = planner.plan(events, currentDateProvider())
            // The normalizer validates source_event_ids against BOTH gallery and
            // daily-log event ids, so non-input ids are dropped.
            HomeRecommendationGenerationResult(
                state = HomeRecommendationGenerationState.Success(result.recommendations),
                events = result.events,
                coveredSourceIds = result.events.map { it.eventId },
            )
        } catch (e: HomeRecommendationException) {
            HomeRecommendationGenerationResult(
                state = HomeRecommendationGenerationState.Error(
                    message = e.safeMessage,
                    userMessage = e.userMessage,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            HomeRecommendationGenerationResult(
                state = HomeRecommendationGenerationState.Error(
                    message = e.message ?: "推荐生成失败",
                    userMessage = "推荐生成失败，请稍后重试。",
                ),
            )
        }
    }

    companion object {
        /** A fake repository useful for composables/tests. */
        fun empty(): HomeRecommendationRepository =
            object : HomeRecommendationRepository {
                override suspend fun generate(
                    photoItems: List<GalleryPhotoItem>,
                ): HomeRecommendationGenerationResult =
                    HomeRecommendationGenerationResult(state = HomeRecommendationGenerationState.NoData)
            }
    }
}

/** Convenience constructor for the happy path (used by the factory). */
internal fun buildGalleryBackedRepository(
    configLoad: () -> HomeRecommendationConfig?,
    plannerFactory: (HomeRecommendationConfig) -> HomeRecommendationPlanner,
    currentDateProvider: () -> String,
): HomeRecommendationRepository =
    GalleryBackedHomeRecommendationRepository(
        configLoad = configLoad,
        plannerFactory = plannerFactory,
        currentDateProvider = currentDateProvider,
    )
