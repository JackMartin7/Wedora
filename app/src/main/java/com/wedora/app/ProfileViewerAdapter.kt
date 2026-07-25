package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemProfileViewerBinding

/** The premium "Who Viewed My Profile" list — newest view first. */
class ProfileViewerAdapter(
    private val onClick: (ProfileViewer) -> Unit = {}
) : ListAdapter<ProfileViewer, ProfileViewerAdapter.ViewerViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ProfileViewer>() {
            override fun areItemsTheSame(a: ProfileViewer, b: ProfileViewer) =
                a.viewerUid == b.viewerUid
            override fun areContentsTheSame(a: ProfileViewer, b: ProfileViewer) = a == b
        }
    }

    inner class ViewerViewHolder(
        private val binding: ItemProfileViewerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(viewer: ProfileViewer) = with(binding) {
            ivViewerAvatar.loadRemoteProfilePhoto(viewer.photoUrl)
            tvViewerName.text = viewer.name
            tvViewerTime.text = root.context.getString(
                R.string.profile_viewer_viewed_format,
                formatRelativeShort(root.context, viewer.viewedAt)
            )
            onlineDot.root.bindOnlineDot(viewer.lastSeen)

            if (viewer.distanceBadge == null) {
                tvViewerDistance.visibility = View.GONE
            } else {
                tvViewerDistance.visibility = View.VISIBLE
                tvViewerDistance.text = viewer.distanceBadge
            }

            root.setOnClickListener { onClick(viewer) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewerViewHolder {
        val binding = ItemProfileViewerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
