package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Generic checkbox list. Items are top-level actions rather than sub-actions, because only
 * top-level actions can be refreshed through notifyActionChanged — which lets the checkbox state be
 * driven from our own stored set instead of from Leanback's implicit toggling.
 */
class MultiSelectStepFragment : GuidedStepSupportFragment() {

    private lateinit var prefKey: String
    private lateinit var values: List<String>
    private lateinit var labels: List<String>
    private var emptyMeansAll: Boolean = true

    private val selected: MutableSet<String> by lazy {
        (PreferencesManager.preferences.getStringSet(prefKey, emptySet()) ?: emptySet()).toMutableSet()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        PreferencesManager.init(requireContext())
        val args = requireArguments()
        prefKey = args.getString(ARG_KEY)!!
        values = args.getStringArrayList(ARG_VALUES) ?: emptyList()
        labels = args.getStringArrayList(ARG_LABELS) ?: values
        emptyMeansAll = args.getBoolean(ARG_EMPTY_MEANS_ALL, true)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        val subtitle = if (emptyMeansAll) getString(R.string.multiselect_empty_hint) else ""
        return Guidance(requireArguments().getString(ARG_TITLE), subtitle, getString(R.string.plugin_name), null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        values.forEachIndexed { index, value ->
            actions.add(
                GuidedAction.Builder(context)
                    .id(index.toLong())
                    .title(labels.getOrElse(index) { value })
                    .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                    .checked(value in selected)
                    .build()
            )
        }
        if (values.isEmpty()) {
            actions.add(
                GuidedAction.Builder(context)
                    .id(ACTION_NONE)
                    .title(R.string.nothing_available)
                    .enabled(false)
                    .build()
            )
        }
    }

    override fun onCreateButtonActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(context).id(ACTION_CLEAR).title(R.string.clear_selection).build()
        )
        actions.add(
            GuidedAction.Builder(context).id(ACTION_DONE).title(R.string.done).build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_DONE -> {
                persist()
                parentFragmentManager.popBackStack()
            }
            ACTION_CLEAR -> {
                selected.clear()
                persist()
                values.indices.forEach { index ->
                    findActionById(index.toLong())?.isChecked = false
                    notifyActionChanged(findActionPositionById(index.toLong()))
                }
            }
            in 0 until values.size.toLong() -> {
                val value = values[action.id.toInt()]
                if (!selected.remove(value)) selected.add(value)
                // Drive the view from our set, so this is correct whether or not Leanback
                // already toggled the action itself.
                action.isChecked = value in selected
                notifyActionChanged(findActionPositionById(action.id))
                persist()
            }
        }
    }

    private fun persist() {
        PreferencesManager.preferences.edit().putStringSet(prefKey, selected.toSet()).apply()
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_KEY = "key"
        private const val ARG_VALUES = "values"
        private const val ARG_LABELS = "labels"
        private const val ARG_EMPTY_MEANS_ALL = "empty_means_all"

        private const val ACTION_DONE = 1_000_000L
        private const val ACTION_CLEAR = 1_000_001L
        private const val ACTION_NONE = 1_000_002L

        fun newInstance(
            title: String,
            prefKey: String,
            values: List<String>,
            labels: List<String> = values,
            emptyMeansAll: Boolean = true,
        ) = MultiSelectStepFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_KEY, prefKey)
                putStringArrayList(ARG_VALUES, ArrayList(values))
                putStringArrayList(ARG_LABELS, ArrayList(labels))
                putBoolean(ARG_EMPTY_MEANS_ALL, emptyMeansAll)
            }
        }
    }
}
