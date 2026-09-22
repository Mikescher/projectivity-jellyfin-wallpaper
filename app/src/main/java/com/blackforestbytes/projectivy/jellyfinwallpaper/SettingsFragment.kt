package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : GuidedStepSupportFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var users: List<JellyfinUser> = emptyList()
    private var libraries: List<Item> = emptyList()
    private var filters: QueryFiltersLegacy = QueryFiltersLegacy()
    private var status: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        PreferencesManager.init(requireContext())
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        getString(R.string.plugin_name),
        "v${BuildConfig.VERSION_NAME}\n\n${getString(R.string.plugin_description)}",
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.drawable.ic_plugin)
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = context
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_STATUS)
                .title(R.string.setting_status).description(R.string.status_unknown)
                .infoOnly(true).focusable(false).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_SERVER_URL)
                .title(R.string.setting_server_url)
                .description(PreferencesManager.serverUrl.ifEmpty { getString(R.string.not_set) })
                .editDescription(PreferencesManager.serverUrl)
                .descriptionEditable(true).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_TOKEN)
                .title(R.string.setting_api_key).description(maskedToken())
                .editDescription(PreferencesManager.token)
                .descriptionEditable(true).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_USER)
                .title(R.string.setting_user)
                .description(PreferencesManager.userName.ifEmpty { getString(R.string.not_set) })
                .subActions(mutableListOf()).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_LIBRARIES)
                .title(R.string.setting_libraries).description(librarySummary()).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_TYPES)
                .title(R.string.setting_item_types)
                .description(PreferencesManager.itemTypes.sorted().joinToString(", ")).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_BALANCE)
                .title(R.string.setting_balance).description(R.string.setting_balance_desc)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(PreferencesManager.balanceTypes).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_GENRES)
                .title(R.string.setting_genres)
                .description(summary(PreferencesManager.genres)).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_RATINGS)
                .title(R.string.setting_ratings)
                .description(summary(PreferencesManager.officialRatings)).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_PLAYED)
                .title(R.string.setting_watch_state).description(playedFilterLabel())
                .subActions(playedFilterSubActions()).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_STYLE)
                .title(R.string.setting_style).description(styleLabel())
                .subActions(styleSubActions()).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_SORT)
                .title(R.string.setting_sort).description(sortLabel())
                .subActions(sortSubActions()).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_LIMIT)
                .title(R.string.setting_limit).description(limitLabel())
                .subActions(limitSubActions()).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_FOUR_K)
                .title(R.string.setting_4k).description(R.string.setting_4k_desc)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(PreferencesManager.fourK).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_CLIENT)
                .title(R.string.setting_client).description(clientLabel())
                .subActions(clientSubActions()).build()
        )
        actions.add(
            GuidedAction.Builder(ctx).id(ACTION_REFRESH)
                .title(R.string.setting_refresh).description(R.string.setting_refresh_desc).build()
        )
    }

    override fun onResume() {
        super.onResume()
        refreshDescriptions()
        loadRemoteData()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_LIBRARIES -> openMultiSelect(
                getString(R.string.setting_libraries), "libraries",
                libraries.map { it.id }, libraries.map { it.name }
            )
            ACTION_TYPES -> openMultiSelect(
                getString(R.string.setting_item_types), "item_types",
                ITEM_TYPE_VALUES, ITEM_TYPE_VALUES, emptyMeansAll = false
            )
            ACTION_GENRES -> openMultiSelect(
                getString(R.string.setting_genres), "genres", filters.genres, filters.genres
            )
            ACTION_RATINGS -> openMultiSelect(
                getString(R.string.setting_ratings), "official_ratings",
                filters.officialRatings, filters.officialRatings
            )
            ACTION_FOUR_K -> {
                PreferencesManager.fourK = !PreferencesManager.fourK
                action.isChecked = PreferencesManager.fourK
                notifyActionChanged(findActionPositionById(action.id))
            }
            ACTION_BALANCE -> {
                PreferencesManager.balanceTypes = !PreferencesManager.balanceTypes
                action.isChecked = PreferencesManager.balanceTypes
                notifyActionChanged(findActionPositionById(action.id))
            }
            ACTION_REFRESH -> {
                Toast.makeText(context, R.string.refreshing, Toast.LENGTH_SHORT).show()
                forceRefresh()
            }
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        when (action.id) {
            ACTION_SERVER_URL -> {
                PreferencesManager.serverUrl = action.editDescription.toString()
                action.description = PreferencesManager.serverUrl.ifEmpty { getString(R.string.not_set) }
            }
            ACTION_TOKEN -> {
                PreferencesManager.token = action.editDescription.toString()
                action.description = maskedToken()
            }
        }
        notifyActionChanged(findActionPositionById(action.id))
        loadRemoteData()
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        when {
            action.id >= SUB_USER_BASE && action.id < SUB_USER_BASE + 1000 -> {
                users.getOrNull((action.id - SUB_USER_BASE).toInt())?.let { user ->
                    PreferencesManager.userId = user.id
                    PreferencesManager.userName = user.name
                    // A different user sees different libraries and genres.
                    PreferencesManager.libraries = emptySet()
                    updateDescription(ACTION_USER, user.name)
                    updateDescription(ACTION_LIBRARIES, librarySummary())
                    loadRemoteData()
                }
            }
            action.id >= SUB_PLAYED_BASE && action.id < SUB_PLAYED_BASE + 1000 -> {
                PreferencesManager.playedFilter = PlayedFilter.entries[(action.id - SUB_PLAYED_BASE).toInt()]
                updateDescription(ACTION_PLAYED, playedFilterLabel())
            }
            action.id >= SUB_CLIENT_BASE && action.id < SUB_CLIENT_BASE + 1000 -> {
                val index = (action.id - SUB_CLIENT_BASE).toInt()
                PreferencesManager.clientPackage = clientValues()[index]
                updateDescription(ACTION_CLIENT, clientLabel())
            }
            action.id >= SUB_STYLE_BASE && action.id < SUB_STYLE_BASE + 1000 -> {
                PreferencesManager.wallpaperStyle =
                    WallpaperStyle.entries[(action.id - SUB_STYLE_BASE).toInt()]
                updateDescription(ACTION_STYLE, styleLabel())
            }
            action.id >= SUB_SORT_BASE && action.id < SUB_SORT_BASE + 1000 -> {
                PreferencesManager.sortMode = SortMode.entries[(action.id - SUB_SORT_BASE).toInt()]
                updateDescription(ACTION_SORT, sortLabel())
            }
            action.id >= SUB_LIMIT_BASE && action.id < SUB_LIMIT_BASE + 1000 -> {
                PreferencesManager.wallpaperLimit =
                    PreferencesManager.LIMIT_CHOICES[(action.id - SUB_LIMIT_BASE).toInt()]
                updateDescription(ACTION_LIMIT, limitLabel())
            }
        }
        return true
    }

    private fun openMultiSelect(
        title: String,
        key: String,
        values: List<String>,
        labels: List<String>,
        emptyMeansAll: Boolean = true,
    ) {
        GuidedStepSupportFragment.add(parentFragmentManager, MultiSelectStepFragment.newInstance(title, key, values, labels, emptyMeansAll))
    }

    private fun updateDescription(actionId: Long, description: String) {
        findActionById(actionId)?.description = description
        notifyActionChanged(findActionPositionById(actionId))
    }

    private fun refreshDescriptions() {
        updateDescription(ACTION_LIBRARIES, librarySummary())
        updateDescription(ACTION_TYPES, PreferencesManager.itemTypes.sorted().joinToString(", ")
            .ifEmpty { getString(R.string.all) })
        updateDescription(ACTION_GENRES, summary(PreferencesManager.genres))
        updateDescription(ACTION_RATINGS, summary(PreferencesManager.officialRatings))
        updateDescription(ACTION_STATUS, status.ifEmpty { getString(R.string.status_unknown) })
        updateDescription(ACTION_CLIENT, clientLabel())
    }

    private fun loadRemoteData() {
        if (PreferencesManager.serverUrl.isEmpty() || PreferencesManager.token.isEmpty()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = JellyfinClient(
                        PreferencesManager.serverUrl,
                        PreferencesManager.token,
                        PreferencesManager.deviceId,
                    )
                    val info = client.systemInfo()
                    val userList = client.users()
                    val userId = PreferencesManager.userId.ifEmpty {
                        userList.firstOrNull()?.also {
                            PreferencesManager.userId = it.id
                            PreferencesManager.userName = it.name
                        }?.id.orEmpty()
                    }
                    val views = if (userId.isNotEmpty()) client.userViews(userId) else emptyList()
                    val filterData = if (userId.isNotEmpty()) {
                        client.mergedFilters(userId, PreferencesManager.libraries, PreferencesManager.itemTypes)
                    } else QueryFiltersLegacy()
                    Triple(info, userList, views to filterData)
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { (info, userList, rest) ->
                val (views, filterData) = rest
                users = userList
                libraries = views.filter { it.collectionType in SUPPORTED_COLLECTION_TYPES }
                    .ifEmpty { views }
                filters = filterData
                info.id?.let { PreferencesManager.serverId = it }
                status = getString(R.string.status_connected, info.serverName.orEmpty(), info.version.orEmpty())
                findActionById(ACTION_USER)?.subActions = userSubActions()
                updateDescription(ACTION_USER, PreferencesManager.userName.ifEmpty { getString(R.string.not_set) })
                refreshDescriptions()
            }.onFailure { error ->
                status = getString(R.string.status_error, error.message ?: error::class.java.simpleName)
                updateDescription(ACTION_STATUS, status)
            }
        }
    }

    private fun forceRefresh() {
        val appContext = requireContext().applicationContext
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { WallpaperRepository.refreshBlocking(appContext) }
            }
            if (!isAdded) return@launch
            count.onSuccess {
                Toast.makeText(appContext, getString(R.string.refreshed, it), Toast.LENGTH_LONG).show()
                WallpaperRepository.notifyProjectivy(appContext)
            }.onFailure {
                Toast.makeText(appContext, getString(R.string.status_error, it.message.orEmpty()), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun userSubActions(): MutableList<GuidedAction> = users.mapIndexed { index, user ->
        GuidedAction.Builder(context)
            .id(SUB_USER_BASE + index)
            .title(user.name)
            .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
            .checked(user.id == PreferencesManager.userId)
            .build()
    }.toMutableList()

    private fun playedFilterSubActions(): MutableList<GuidedAction> =
        PlayedFilter.entries.mapIndexed { index, filter ->
            GuidedAction.Builder(context)
                .id(SUB_PLAYED_BASE + index)
                .title(getString(PLAYED_FILTER_LABELS[index]))
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(filter == PreferencesManager.playedFilter)
                .build()
        }.toMutableList()

    private fun playedFilterLabel(): String =
        getString(PLAYED_FILTER_LABELS[PreferencesManager.playedFilter.ordinal])

    private fun clientValues(): List<String> =
        listOf(DeepLinks.AUTO, DeepLinks.NONE) + DeepLinks.PLAYERS.map { it.packageName }

    private fun clientLabels(): List<String> =
        listOf(getString(R.string.client_auto), getString(R.string.client_none)) +
            DeepLinks.PLAYERS.map { it.label }

    private fun clientSubActions(): MutableList<GuidedAction> {
        val values = clientValues()
        val labels = clientLabels()
        return values.mapIndexed { index, pkg ->
            GuidedAction.Builder(context)
                .id(SUB_CLIENT_BASE + index)
                .title(labels[index])
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(pkg == PreferencesManager.clientPackage)
                .build()
        }.toMutableList()
    }

    private fun clientLabel(): String {
        val selected = PreferencesManager.clientPackage
        val index = clientValues().indexOf(selected)
        val label = if (index >= 0) clientLabels()[index] else getString(R.string.client_none)
        if (selected != DeepLinks.AUTO) return label
        val detected = DeepLinks.resolve(requireContext(), DeepLinks.AUTO)
        return getString(
            R.string.client_auto_detected,
            detected?.label ?: getString(R.string.client_none)
        )
    }

    private fun styleSubActions(): MutableList<GuidedAction> =
        WallpaperStyle.entries.mapIndexed { index, style ->
            GuidedAction.Builder(context)
                .id(SUB_STYLE_BASE + index)
                .title(getString(STYLE_LABELS[index]))
                .description(getString(STYLE_DESCRIPTIONS[index]))
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(style == PreferencesManager.wallpaperStyle)
                .build()
        }.toMutableList()

    private fun styleLabel(): String =
        getString(STYLE_LABELS[PreferencesManager.wallpaperStyle.ordinal])

    private fun sortSubActions(): MutableList<GuidedAction> =
        SortMode.entries.mapIndexed { index, mode ->
            GuidedAction.Builder(context)
                .id(SUB_SORT_BASE + index)
                .title(getString(SORT_LABELS[index]))
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(mode == PreferencesManager.sortMode)
                .build()
        }.toMutableList()

    private fun sortLabel(): String = getString(SORT_LABELS[PreferencesManager.sortMode.ordinal])

    private fun limitSubActions(): MutableList<GuidedAction> =
        PreferencesManager.LIMIT_CHOICES.mapIndexed { index, limit ->
            GuidedAction.Builder(context)
                .id(SUB_LIMIT_BASE + index)
                .title(limit.toString())
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(limit == PreferencesManager.wallpaperLimit)
                .build()
        }.toMutableList()

    private fun limitLabel(): String = PreferencesManager.wallpaperLimit.toString()

    private fun maskedToken(): String {
        val token = PreferencesManager.token
        return when {
            token.isEmpty() -> getString(R.string.not_set)
            token.length <= 4 -> "••••"
            else -> "••••••••" + token.takeLast(4)
        }
    }

    private fun librarySummary(): String {
        val selected = PreferencesManager.libraries
        if (selected.isEmpty()) return getString(R.string.all)
        val names = libraries.filter { it.id in selected }.map { it.name }
        return if (names.isEmpty()) getString(R.string.n_selected, selected.size) else names.joinToString(", ")
    }

    private fun summary(values: Set<String>): String =
        if (values.isEmpty()) getString(R.string.all) else values.sorted().joinToString(", ")

    companion object {
        private const val ACTION_STATUS = 1L
        private const val ACTION_SERVER_URL = 2L
        private const val ACTION_TOKEN = 3L
        private const val ACTION_USER = 4L
        private const val ACTION_LIBRARIES = 5L
        private const val ACTION_TYPES = 6L
        private const val ACTION_GENRES = 7L
        private const val ACTION_RATINGS = 8L
        private const val ACTION_PLAYED = 9L
        private const val ACTION_FOUR_K = 10L
        private const val ACTION_CLIENT = 11L
        private const val ACTION_REFRESH = 12L
        private const val ACTION_STYLE = 13L
        private const val ACTION_SORT = 14L
        private const val ACTION_LIMIT = 15L
        private const val ACTION_BALANCE = 16L

        private const val SUB_USER_BASE = 100_000L
        private const val SUB_CLIENT_BASE = 200_000L
        private const val SUB_PLAYED_BASE = 300_000L
        private const val SUB_STYLE_BASE = 400_000L
        private const val SUB_SORT_BASE = 500_000L
        private const val SUB_LIMIT_BASE = 600_000L

        /** Indexed by [WallpaperStyle.ordinal]. */
        private val STYLE_LABELS = listOf(R.string.style_backdrop, R.string.style_composed)
        private val STYLE_DESCRIPTIONS =
            listOf(R.string.style_backdrop_desc, R.string.style_composed_desc)

        /** Indexed by [SortMode.ordinal]. */
        private val SORT_LABELS = listOf(R.string.sort_random, R.string.sort_recent)

        /** Indexed by [PlayedFilter.ordinal]. */
        private val PLAYED_FILTER_LABELS = listOf(
            R.string.watch_state_all,
            R.string.watch_state_unwatched,
            R.string.watch_state_watched,
        )

        private val ITEM_TYPE_VALUES = listOf("Movie", "Series")
        private val SUPPORTED_COLLECTION_TYPES = setOf("movies", "tvshows", "boxsets", "homevideos")

    }
}
