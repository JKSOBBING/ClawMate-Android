package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildResult
import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildStatus
import com.clawmate.app.datamanager.ProfileTypes.ProfileDomainState
import com.clawmate.app.datamanager.ProfileTypes.ProfileState
import com.clawmate.app.datamanager.ProfileTypes.PROFILE_DOMAIN_KEYS
import java.io.File

/**
 * The single explicit entry point for the Phase-32 **profile index / claims**
 * build. Invoking [run] reads the already-persisted Phase-30 event index,
 * projects the four persona domains, and — only when a model is configured and
 * real events exist — upgrades each domain with **strictly validated** model
 * claims. It never runs on launch / recomposition / background: only the
 * explicit user "生成画像" caller invokes it.
 *
 * Honest typed outcomes:
 *  - **EMPTY** — no real indexed events; zero network and no artifact is written.
 *  - **LOCAL_ONLY** — no model configured (zero network); summaries come only
 *    from real event text and `claims` stay empty (never confused with model
 *    claims).
 *  - **MODEL** — at least one model-backed domain with admitted claims.
 *  - **PARTIAL** — model was attempted but not every domain produced claims.
 *  - **ERROR** — the model path failed; the deterministic local result is
 *    preserved and a previously persisted valid (model-backed) state with the
 *    same real evidence is **not overwritten** with a downgrade.
 *
 * Every domain hash is derived from the real events ([ProfileDomainUtils.
 * domainInputHash]) so deterministic-reuse keeps admitted model claims stable
 * across repeated runs over unchanged input.
 */
class ProfileIndexRunner(
    private val baseDir: File,
    private val builder: ProfileIndexBuilder = ProfileIndexBuilder(baseDir),
    private val modelFounder: (() -> ProfileModelConfig?)? = { ProfileModelConfig.fromEndpointConfig() },
) {

    /**
     * A per-domain model generator seam. Tests inject a fake (or a configured
     * one); production uses [ProfileModelClient]. Returns null when model
     * generation is unavailable for that domain (never throws).
     */
    interface DomainModelGateway {
        suspend fun generate(
            events: List<LocalConsolidationTypes.IndexedEvent>,
            local: ProfileDomainState,
        ): DomainUpgrade?
    }

    /**
     * Run the profile build and persist the honest result. Never throws.
     * [gateway] defaults to a real [ProfileModelClient] resolved from
     * [modelFounder]; tests inject a fake via [ProfileIndexRunner.forTest].
     */
    suspend fun run(gateway: DomainModelGateway? = resolveGateway()): ProfileBuildResult {
        val events = builder.loadEvents()
        if (events.isEmpty()) {
            return ProfileBuildResult(eventCount = 0, status = ProfileBuildStatus.EMPTY)
        }
        val local = builder.projectLocal(events)
        val previous = builder.loadProfileState()

        val activeGateway = gateway
        val wantModel = activeGateway != null
        val domainStates = LinkedHashMap<String, ProfileDomainState>()
        var modelFailed = false
        for (domain in PROFILE_DOMAIN_KEYS) {
            val localState = local.getValue(domain)
            if (wantModel && localState.eventCount > 0 && activeGateway != null) {
                val reusable = tryReuse(previous, domain, localState)
                if (reusable != null) {
                    domainStates[domain] = reusable
                    continue
                }
                val upgrade = activeGateway.generate(eventsForDomain(events, localState), localState)
                if (upgrade is DomainUpgrade.Model) {
                    domainStates[domain] = upgrade.toState(localState)
                } else {
                    if (upgrade is DomainUpgrade.Local && upgrade.fromModelFailure) {
                        modelFailed = true
                    }
                    // Keep the local projection for the failed / empty domain, but
                    // first try to retain any prior valid model domain with the
                    // same real evidence (do not downgrade valid state).
                    val prior = priorModelDomain(previous, domain, localState)
                    domainStates[domain] = prior ?: localState
                }
            } else {
                domainStates[domain] = localState
            }
        }

        val state = builder.buildProfileDocument(events, domainStates)
        val status = resolveOverall(builder.overallStatus(domainStates), wantModel, modelFailed)
        val finalState = state.copy(status = status.token)

        // Never overwrite a previously persisted valid model-backed state with a
        // strictly-worse (local-only) downgrade that a model failure produced.
        if (status == ProfileBuildStatus.ERROR && priorHasValidModel(previous)) {
            return toLocalPreservedResult(previous)
        }
        builder.writeProfile(finalState)
        return toResult(finalState)
    }

    /** Resolve the production model gateway; null when not configured (zero network). */
    private fun resolveGateway(): DomainModelGateway? {
        val config = modelFounder?.invoke() ?: return null
        return ProfileModelClient(builderChatClient(config), config)
    }

    /** Keep prior model-domain when its real evidence hash still matches (deterministic reuse). */
    private fun tryReuse(previous: ProfileState, domain: String, local: ProfileDomainState): ProfileDomainState? {
        val prior = previous.domains[domain] ?: return null
        if (prior.inputHash != local.inputHash) return null
        if (prior.generation != "model" && prior.generation != "model_empty") return null
        return prior.copy(
            eventIds = local.eventIds,
            eventCount = local.eventCount,
            startDate = local.startDate,
            endDate = local.endDate,
        )
    }

    /** Return a prior valid model domain only when its evidence hash still matches (avoid downgrade). */
    private fun priorModelDomain(previous: ProfileState, domain: String, local: ProfileDomainState): ProfileDomainState? {
        val prior = previous.domains[domain] ?: return null
        if (prior.inputHash != local.inputHash) return null
        if (prior.generation != "model") return null
        return prior.copy(
            eventIds = local.eventIds,
            eventCount = local.eventCount,
            startDate = local.startDate,
            endDate = local.endDate,
        )
    }

    private fun priorHasValidModel(previous: ProfileState): Boolean =
        previous.domains.values.any { it.generation == "model" && it.claims.isNotEmpty() }

    private fun eventsForDomain(
        events: List<LocalConsolidationTypes.IndexedEvent>,
        local: ProfileDomainState,
    ): List<LocalConsolidationTypes.IndexedEvent> =
        local.eventIds.mapNotNull { id -> events.firstOrNull { it.eventId == id } }

    private fun resolveOverall(base: ProfileBuildStatus, wantModel: Boolean, modelFailed: Boolean): ProfileBuildStatus =
        when {
            base == ProfileBuildStatus.MODEL -> ProfileBuildStatus.MODEL
            wantModel && modelFailed && base == ProfileBuildStatus.LOCAL_ONLY -> ProfileBuildStatus.ERROR
            wantModel && base == ProfileBuildStatus.LOCAL_ONLY -> ProfileBuildStatus.PARTIAL
            wantModel && base == ProfileBuildStatus.PARTIAL -> ProfileBuildStatus.PARTIAL
            else -> base
        }

    private fun toLocalPreservedResult(previous: ProfileState): ProfileBuildResult {
        val generation = previous.domains.mapValues { (_, d) -> d.generation }
        return ProfileBuildResult(
            eventCount = previous.eventCount,
            domainCounts = previous.domains.mapValues { (_, d) -> d.eventCount },
            claimCount = previous.claimCount,
            generatedAt = previous.generatedAt,
            status = ProfileBuildStatus.ERROR,
            domainGeneration = generation,
        )
    }

    private fun toResult(state: ProfileState): ProfileBuildResult {
        val generation = state.domains.mapValues { (_, d) -> d.generation }
        return ProfileBuildResult(
            eventCount = state.eventCount,
            domainCounts = state.domains.mapValues { (_, d) -> d.eventCount },
            claimCount = state.claimCount,
            generatedAt = state.generatedAt,
            status = ProfileBuildStatus.fromToken(state.status),
            domainGeneration = generation,
        )
    }

    private fun builderChatClient(config: ProfileModelConfig): com.clawmate.app.chat.cloud.ChatClient {
        val httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return com.clawmate.app.chat.cloud.ChatCompletionsClient(
            httpClient,
            com.clawmate.app.chat.cloud.ChatServerConfig(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                modelName = config.modelName,
            ),
        )
    }

    companion object {
        /** Test seam: a runner with a forced model config (still never auto-calls). */
        fun forTest(
            baseDir: File,
            config: ProfileModelConfig,
        ): ProfileIndexRunner = ProfileIndexRunner(
            baseDir = baseDir,
            modelFounder = { config },
        )

        /** Test seam: a runner with model disabled (no config). */
        fun localOnly(baseDir: File): ProfileIndexRunner =
            ProfileIndexRunner(baseDir = baseDir, modelFounder = { null })
    }
}
