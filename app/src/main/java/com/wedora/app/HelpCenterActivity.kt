package com.wedora.app

import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.wedora.app.databinding.ActivityHelpCenterBinding
import com.wedora.app.databinding.ItemFaqBinding
import java.util.Locale

/** One FAQ entry. Held as string resources so the copy stays localisable. */
private data class Faq(@StringRes val questionRes: Int, @StringRes val answerRes: Int)

private val FAQS = listOf(
    Faq(R.string.faq_q_matching, R.string.faq_a_matching),
    Faq(R.string.faq_q_verify, R.string.faq_a_verify),
    Faq(R.string.faq_q_subscription, R.string.faq_a_subscription),
    Faq(R.string.faq_q_report, R.string.faq_a_report),
    Faq(R.string.faq_q_block, R.string.faq_a_block),
    Faq(R.string.faq_q_delete, R.string.faq_a_delete),
    Faq(R.string.faq_q_no_matches, R.string.faq_a_no_matches),
    Faq(R.string.faq_q_location, R.string.faq_a_location)
)

/**
 * Searchable FAQ accordion, plus a route to email support.
 *
 * Rows are inflated into a container rather than driven by a RecyclerView:
 * there are eight of them and they never scroll off far enough to matter, and
 * without recycling the expand state can't be rebound onto the wrong row —
 * which is the usual source of an accordion showing two answers open at once.
 */
class HelpCenterActivity : AppCompatActivity() {

    private companion object {
        const val CHEVRON_ROTATION_MS = 200L
        const val ANSWER_FADE_MS = 180L

        /** How far the answer slides down as it fades in, in dp. */
        const val ANSWER_SLIDE_DP = 8f
    }

    private lateinit var binding: ActivityHelpCenterBinding

    /** The open row, or null when everything is collapsed. Only ever one. */
    private var expandedRow: ItemFaqBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowChatSupport.setOnClickListener { emailSupport() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showFaqs(s?.toString().orEmpty())
        })

        showFaqs("")
    }

    // ----- List + search --------------------------------------------------

    /**
     * Rebuilds the list for [query], matching against questions *and* answers
     * so searching a term that only appears in an answer ("spam folder",
     * "Google Play") still finds its question.
     *
     * A rebuild rather than per-row visibility toggling: it keeps the dividers
     * and the accordion state consistent for free, and eight rows is nothing
     * to inflate.
     */
    private fun showFaqs(query: String) {
        val trimmed = query.trim()
        val matches = if (trimmed.isEmpty()) FAQS else FAQS.filter { it.matches(trimmed) }

        expandedRow = null
        binding.faqContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        matches.forEach { faq ->
            val row = ItemFaqBinding.inflate(inflater, binding.faqContainer, true)
            row.tvQuestion.setText(faq.questionRes)
            row.tvAnswer.setText(faq.answerRes)
            row.faqHeader.setOnClickListener { toggle(row) }
        }

        val hasResults = matches.isNotEmpty()
        binding.faqContainer.visibility = if (hasResults) View.VISIBLE else View.GONE
        binding.tvNoResults.visibility = if (hasResults) View.GONE else View.VISIBLE
        if (!hasResults) {
            binding.tvNoResults.text = getString(R.string.help_no_results, trimmed)
        }
        // The section heading belongs to the list, so it goes with it.
        binding.tvSectionLabel.visibility = if (hasResults) View.VISIBLE else View.GONE
    }

    /** Case-insensitive match on either half of the entry. */
    private fun Faq.matches(query: String): Boolean {
        val needle = query.lowercase(Locale.getDefault())
        return getString(questionRes).lowercase(Locale.getDefault()).contains(needle) ||
            getString(answerRes).lowercase(Locale.getDefault()).contains(needle)
    }

    // ----- Accordion ------------------------------------------------------

    /** Opening a row closes whichever was open, so only one answer shows. */
    private fun toggle(row: ItemFaqBinding) {
        // Identity, not equality: rows are compared by which inflated instance
        // they are, and a binding class doesn't define equals anyway.
        val alreadyOpen = row === expandedRow

        expandedRow?.let { collapse(it) }
        expandedRow = null

        if (!alreadyOpen) {
            expand(row)
            expandedRow = row
        }
    }

    private fun expand(row: ItemFaqBinding) {
        rotateChevron(row, to = 180f)

        val slide = ANSWER_SLIDE_DP * resources.displayMetrics.density
        row.tvAnswer.apply {
            alpha = 0f
            translationY = -slide
            visibility = View.VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(ANSWER_FADE_MS).start()
        }
    }

    private fun collapse(row: ItemFaqBinding) {
        rotateChevron(row, to = 0f)
        // Hidden immediately rather than faded out: the row above it is
        // usually expanding at the same moment, and animating both directions
        // at once reads as a flicker.
        row.tvAnswer.animate().cancel()
        row.tvAnswer.visibility = View.GONE
    }

    /** Rotates from wherever it currently is, so a fast double-tap doesn't jump. */
    private fun rotateChevron(row: ItemFaqBinding, to: Float) {
        ObjectAnimator.ofFloat(row.ivChevron, View.ROTATION, row.ivChevron.rotation, to)
            .setDuration(CHEVRON_ROTATION_MS)
            .start()
    }

    // ----- Support --------------------------------------------------------

    /**
     * ACTION_SENDTO with a mailto: URI, so only email apps can respond — a
     * plain ACTION_SEND would offer every messaging and social app installed.
     */
    private fun emailSupport() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${getString(R.string.support_email)}")
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.support_subject))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Common enough on emulators and stripped-down devices to be worth
            // saying plainly rather than letting the tap do nothing.
            Toast.makeText(this, R.string.error_no_email_app, Toast.LENGTH_LONG).show()
        }
    }
}
