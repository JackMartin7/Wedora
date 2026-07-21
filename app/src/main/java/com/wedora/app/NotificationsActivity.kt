package com.wedora.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.wedora.app.databinding.ActivityNotificationsBinding

/**
 * Who liked you. Tapping a row opens that person's profile.
 *
 * The list is empty for now — it's wired to a real Firestore query in the
 * follow-up commit. Deliberately no sample rows: fabricated notifications
 * naming people who never liked you would be worse than an honest empty state.
 */
class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding

    private val adapter = NotificationsAdapter { item ->
        startActivity(ProfileDetailActivity.intent(this, item.likerUserId))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter

        showNotifications(emptyList())
    }

    private fun showNotifications(items: List<NotificationItem>) {
        binding.progressLoading.visibility = View.GONE

        val isEmpty = items.isEmpty()
        binding.tvNotificationsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvNotifications.visibility = if (isEmpty) View.GONE else View.VISIBLE

        adapter.submitList(items)
    }
}
