package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemLikesMatchedUserBinding

/**
 * Horizontal strip of circular avatars for the Likes tab's "Who Viewed Your
 * Profile" row — same item layout as [MatchedUserAdapter]'s "Users Matched"
 * strip, since both are the same circular-avatar-plus-name treatment.
 */
class ProfileViewerStripAdapter(
    private val onClick: (ProfileViewer) -> Unit = {}
) : ListAdapter<ProfileViewer, ProfileViewerStripAdapter.ViewerViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ProfileViewer>() {
            override fun areItemsTheSame(a: ProfileViewer, b: ProfileViewer) =
                a.viewerUid == b.viewerUid
            override fun areContentsTheSame(a: ProfileViewer, b: ProfileViewer) = a == b
        }
    }

    inner class ViewerViewHolder(
        private val binding: ItemLikesMatchedUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(viewer: ProfileViewer) = with(binding) {
            ivMatchedAvatar.loadRemoteProfilePhoto(viewer.photoUrl)
            tvMatchedName.text = viewer.name
            onlineDot.root.bindOnlineDot(viewer.lastSeen)

            if (viewer.distanceBadge == null) {
                tvMatchedDistance.visibility = View.GONE
            } else {
                tvMatchedDistance.visibility = View.VISIBLE
                tvMatchedDistance.text = viewer.distanceBadge
            }

            root.setOnClickListener { onClick(viewer) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewerViewHolder {
        val binding = ItemLikesMatchedUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
