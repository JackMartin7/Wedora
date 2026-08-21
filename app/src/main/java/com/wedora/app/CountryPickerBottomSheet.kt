package com.wedora.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemCountryRowBinding
import com.wedora.app.databinding.SheetCountryPickerBinding
import java.util.Locale

/**
 * Searchable country list, shared by the two capture fields (profile setup and
 * Edit Profile) and by Filters.
 *
 * Selection is handed back through a listener set by the host rather than a
 * fragment result, matching how the app's other sheets talk to their callers.
 * The listener is NOT retained across a configuration change - the sheet
 * dismisses itself on rotation rather than reappearing with a dead callback.
 *
 * [ARG_ALLOW_ANY] adds a leading "Any country" row. Filters needs it as the way
 * to clear the filter; the capture fields do not, since a profile either has a
 * country or has not set one yet.
 */
class CountryPickerBottomSheet : WedoraBottomSheetDialog() {

    companion object {
        private const val ARG_SELECTED = "arg_selected"
        private const val ARG_ALLOW_ANY = "arg_allow_any"

        fun newInstance(selected: String?, allowAny: Boolean) =
            CountryPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SELECTED, selected)
                    putBoolean(ARG_ALLOW_ANY, allowAny)
                }
            }
    }

    /** Null means "Any country" was chosen; only reachable when allowAny is set. */
    var onCountryPicked: ((String?) -> Unit)? = null

    private var binding: SheetCountryPickerBinding? = null
    private lateinit var adapter: CountryAdapter

    private val allowAny: Boolean
        get() = arguments?.getBoolean(ARG_ALLOW_ANY) ?: false

    private val selected: String?
        get() = arguments?.getString(ARG_SELECTED)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetCountryPickerBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return

        // The stored value may be free text from before the picker existed
        // ("USA"), so it is canonicalised before being matched against a row -
        // otherwise an existing selection would show nothing ticked.
        val current = Countries.canonicalise(selected) ?: selected

        adapter = CountryAdapter(current) { name ->
            onCountryPicked?.invoke(name)
            dismiss()
        }
        b.rvCountries.layoutManager = LinearLayoutManager(requireContext())
        b.rvCountries.adapter = adapter

        submit("")

        b.etCountrySearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = submit(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) = Unit
        })
    }

    /**
     * Filters by prefix first, then by containment, so typing "in" puts India
     * and Indonesia above Argentina rather than burying them among every name
     * with those letters somewhere inside.
     */
    private fun submit(query: String) {
        val b = binding ?: return
        val q = query.trim().lowercase(Locale.ENGLISH)

        val matches = if (q.isEmpty()) {
            Countries.names
        } else {
            val lower = Countries.names.map { it to it.lowercase(Locale.ENGLISH) }
            lower.filter { it.second.startsWith(q) }.map { it.first } +
                lower.filter { !it.second.startsWith(q) && it.second.contains(q) }.map { it.first }
        }

        val rows = buildList {
            // The reset row is only offered on an unfiltered list; searching
            // means the user is looking for a country, not for a way out.
            if (allowAny && q.isEmpty()) add(CountryRow.Any)
            matches.forEach { add(CountryRow.Country(it)) }
        }
        adapter.submitRows(rows)
        b.tvCountryEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.rvCountries.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}

/** A row in the picker: a real country, or the "Any country" reset. */
sealed class CountryRow {
    object Any : CountryRow()
    data class Country(val name: String) : CountryRow()
}

private class CountryAdapter(
    private val selected: String?,
    private val onClick: (String?) -> Unit
) : RecyclerView.Adapter<CountryAdapter.Holder>() {

    private var rows: List<CountryRow> = emptyList()

    fun submitRows(next: List<CountryRow>) {
        rows = next
        @Suppress("NotifyDataSetChanged") // whole-list swap on every keystroke
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemCountryRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemCountryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        with(holder.binding) {
            when (row) {
                is CountryRow.Any -> {
                    tvCountryFlag.text = ""
                    tvCountryName.text = root.context.getString(R.string.filter_country_any)
                    ivCountryCheck.visibility =
                        if (selected == null) View.VISIBLE else View.GONE
                    root.setOnClickListener { onClick(null) }
                }
                is CountryRow.Country -> {
                    tvCountryFlag.text = Countries.flagFor(row.name).orEmpty()
                    tvCountryName.text = row.name
                    ivCountryCheck.visibility =
                        if (row.name == selected) View.VISIBLE else View.GONE
                    root.setOnClickListener { onClick(row.name) }
                }
            }
        }
    }
}
