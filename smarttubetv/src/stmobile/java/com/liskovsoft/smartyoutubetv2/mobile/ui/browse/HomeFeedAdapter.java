package com.liskovsoft.smartyoutubetv2.mobile.ui.browse;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure two-column waterfall adapter for the Home screen: renders only the primary recommended
 * videos feed ("推荐视频") without sub-category shelves, and supports incremental vertical
 * pagination as the user scrolls down.
 */
public class HomeFeedAdapter extends RecyclerView.Adapter<HomeFeedAdapter.VideoHolder> {
    private int mColWidth;
    private final VideoCardAdapter.OnVideoAction mClick;
    private final VideoCardAdapter.OnVideoAction mLongClick;

    private final List<Video> mVideos = new ArrayList<>();
    private VideoGroup mMainGroup;
    private int mMainGroupId = -1;

    public HomeFeedAdapter(int colWidth,
                           VideoCardAdapter.OnVideoAction click,
                           VideoCardAdapter.OnVideoAction longClick) {
        mColWidth = colWidth;
        mClick = click;
        mLongClick = longClick;
    }

    public void setColumnWidth(int colWidth) {
        if (mColWidth != colWidth) {
            mColWidth = colWidth;
            notifyDataSetChanged();
        }
    }

    public void appendGroup(VideoGroup group) {
        if (group == null || group.isEmpty()) {
            return;
        }
        // Only display the main recommended feed in the Home waterfall; ignore secondary
        // category shelves (Shorts rows, topic shelves, channel shelves, etc.).
        if (mMainGroupId != -1 && group.getId() != mMainGroupId) {
            return;
        }
        if (mMainGroupId == -1 && !isRecommendedCandidate(group.getVideos())) {
            return;
        }

        mMainGroup = group;
        mMainGroupId = group.getId();

        List<Video> incoming = filterRecommended(group.getVideos());
        if (isPureTailAppend(mVideos, incoming)) {
            int oldSize = mVideos.size();
            for (int i = oldSize; i < incoming.size(); i++) {
                mVideos.add(incoming.get(i));
            }
            if (incoming.size() > oldSize) {
                notifyItemRangeInserted(oldSize, incoming.size() - oldSize);
            }
        } else {
            mVideos.clear();
            mVideos.addAll(incoming);
            notifyDataSetChanged();
        }
    }

    public void removeVideos(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        if (mVideos.removeAll(videos)) {
            notifyDataSetChanged();
        }
    }

    public void syncVideos(List<Video> changed) {
        if (changed == null || changed.isEmpty()) {
            return;
        }
        for (int i = 0; i < mVideos.size(); i++) {
            Video origin = mVideos.get(i);
            for (Video updated : changed) {
                if (origin.equals(updated)) {
                    origin.sync(updated);
                    notifyItemChanged(i);
                }
            }
        }
    }

    public void clear() {
        mVideos.clear();
        mMainGroup = null;
        mMainGroupId = -1;
        notifyDataSetChanged();
    }

    /** Returns the tail video of the recommended feed group for vertical pagination. */
    public Video getLastMainVideo() {
        if (mMainGroup == null) {
            return null;
        }
        List<Video> raw = mMainGroup.getVideos();
        if (raw != null && !raw.isEmpty()) {
            Video last = raw.get(raw.size() - 1);
            if (last != null && last.getGroup() == null) {
                last.setGroup(mMainGroup);
            }
            return last;
        }
        return mVideos.isEmpty() ? null : mVideos.get(mVideos.size() - 1);
    }

    @Override
    public int getItemCount() {
        return mVideos.size();
    }

    @NonNull
    @Override
    public VideoHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.mobile_home_video_card, parent, false);
        VideoHolder holder = new VideoHolder(view);
        VideoCardAdapter.applyRoundedCorners(holder.thumbFrame, 10f);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull VideoHolder holder, int position) {
        if (holder.itemView.getLayoutParams() instanceof StaggeredGridLayoutManager.LayoutParams) {
            StaggeredGridLayoutManager.LayoutParams lp =
                    (StaggeredGridLayoutManager.LayoutParams) holder.itemView.getLayoutParams();
            if (lp.isFullSpan()) {
                lp.setFullSpan(false);
            }
        }

        Video video = mVideos.get(position);
        if (holder.thumbFrame.getLayoutParams() != null && mColWidth > 0) {
            int expectedHeight = Math.round(mColWidth * 9f / 16f);
            if (holder.thumbFrame.getLayoutParams().height != expectedHeight) {
                holder.thumbFrame.getLayoutParams().height = expectedHeight;
            }
        }

        holder.title.setText(video.getTitle() != null ? video.getTitle() : "");
        holder.author.setText(VideoCardAdapter.formatMetaLine(video));
        VideoCardAdapter.bindChannelAvatar(holder.avatar, video);

        String badge = video.badge;
        if (badge != null && !badge.isEmpty()) {
            holder.duration.setText(badge);
            holder.duration.setVisibility(View.VISIBLE);
        } else {
            holder.duration.setVisibility(View.GONE);
        }
        VideoCardAdapter.bindWatchedBar(holder.progress, holder.duration, video);

        Glide.with(holder.itemView.getContext())
                .load(video.getCardImageUrl())
                .into(holder.thumb);

        holder.itemView.setOnClickListener(v -> {
            if (mClick != null) {
                mClick.onVideo(video);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (mLongClick != null) {
                mLongClick.onVideo(video);
            }
            return true;
        });
        if (holder.moreBtn != null) {
            holder.moreBtn.setOnClickListener(v -> {
                if (mLongClick != null) {
                    mLongClick.onVideo(video);
                }
            });
        }
    }

    // ---- Pure filtering & pagination helpers (package-private for unit tests) ----

    static boolean isRecommendedCandidate(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return false;
        }
        boolean hasRegularVideo = false;
        for (Video v : videos) {
            if (v != null && !v.isShorts && !v.isChannel()) {
                hasRegularVideo = true;
                break;
            }
        }
        return hasRegularVideo;
    }

    static List<Video> filterRecommended(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Video> out = new ArrayList<>(videos.size());
        for (Video v : videos) {
            if (v != null && !v.isShorts) {
                out.add(v);
            }
        }
        return out;
    }

    static boolean isPureTailAppend(List<Video> existing, List<Video> updated) {
        if (existing.isEmpty() || updated.size() < existing.size()) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            Video a = existing.get(i);
            Video b = updated.get(i);
            if (a != b && (a == null || !a.equals(b))) {
                return false;
            }
        }
        return true;
    }

    static final class VideoHolder extends RecyclerView.ViewHolder {
        final View thumbFrame;
        final ImageView thumb;
        final TextView duration;
        final ProgressBar progress;
        final TextView avatar;
        final TextView title;
        final TextView author;
        final View moreBtn;

        VideoHolder(View itemView) {
            super(itemView);
            thumbFrame = itemView.findViewById(R.id.card_thumb_frame);
            thumb = itemView.findViewById(R.id.card_thumb);
            duration = itemView.findViewById(R.id.card_duration);
            progress = itemView.findViewById(R.id.card_progress);
            avatar = itemView.findViewById(R.id.card_avatar);
            title = itemView.findViewById(R.id.card_title);
            author = itemView.findViewById(R.id.card_author);
            moreBtn = itemView.findViewById(R.id.card_more);
        }
    }
}
