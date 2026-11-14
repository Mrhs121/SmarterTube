package com.liskovsoft.smartyoutubetv2.mobile.ui.browse;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.mobile.ui.about.MobileAboutActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * Native portrait Home screen. Implements {@link BrowseView} and is driven by the
 * existing {@link BrowsePresenter} unchanged — all section/video-group loading logic is
 * reused from the TV code; only the view layer is new.
 *
 * ROW sections (Home, Trending, Music) render as stacked horizontal shelves; GRID
 * sections (Subscriptions, History, ...) render as a 2-column grid. The drawer menu
 * opens with an edge-swipe or the toolbar button.
 */
public class MobileBrowseFragment extends Fragment implements BrowseView, MediaServiceManager.AccountChangeListener {
    private BrowsePresenter mPresenter;
    private DrawerLayout mDrawer;
    private SwipeRefreshLayout mSwipeRefresh;
    private RecyclerView mContentList;
    private ProgressBar mProgressBar;
    private TextView mEmptyMessage;
    private View mEmptyContainer;
    private Button mEmptyButton;
    private TextView mToolbarTitle;
    private View mToolbarLogo;
    private ImageView mAccountView;
    private View mHomeChipsScroll;
    private LinearLayout mHomeChips;
    private SectionAdapter mSectionAdapter;
    private VideoCardAdapter mGridAdapter;
    private ShelfAdapter mShelfAdapter;
    private HomeFeedAdapter mHomeAdapter;
    private SettingsItemAdapter mSettingsAdapter;
    private boolean mProgressShowing;
    private boolean mSwipeRefreshing;
    private boolean mSectionSelected;
    private int mCurrentSectionId = -1;
    private int mLastHomePagedCount = -1;
    private int mGridCardWidth;
    private int mGridSpan;
    private int mHomeSpan;
    private int mHomeColWidth;
    private int mShelfCardWidth;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mobile_browse_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mDrawer = view.findViewById(R.id.drawer_layout);
        mContentList = view.findViewById(R.id.content_list);
        mSwipeRefresh = view.findViewById(R.id.swipe_refresh);
        mProgressBar = view.findViewById(R.id.progress_bar);
        mEmptyMessage = view.findViewById(R.id.empty_message);
        mEmptyContainer = view.findViewById(R.id.empty_container);
        mEmptyButton = view.findViewById(R.id.empty_button);
        mToolbarTitle = view.findViewById(R.id.toolbar_title);
        mToolbarLogo = view.findViewById(R.id.toolbar_logo);
        mHomeChipsScroll = view.findViewById(R.id.home_chips_scroll);
        mHomeChips = view.findViewById(R.id.home_chips);

        view.findViewById(R.id.btn_menu).setOnClickListener(v -> {
            if (mDrawer.isDrawerOpen(GravityCompat.START)) {
                mDrawer.closeDrawer(GravityCompat.START);
            } else {
                mDrawer.openDrawer(GravityCompat.START);
            }
        });

        view.findViewById(R.id.btn_search).setOnClickListener(v ->
                SearchPresenter.instance(getContext()).startSearch(null));

        // Account switcher — mirrors SmartTube TV's title-bar orb (NavigateTitleView):
        // tap cycles to the next account, long-press opens account management.
        mAccountView = view.findViewById(R.id.btn_account);
        mAccountView.setOnClickListener(v ->
                AccountSelectionPresenter.instance(getContext()).nextAccountOrDialog());
        mAccountView.setOnLongClickListener(v -> {
            AccountSettingsPresenter.instance(getContext()).show();
            return true;
        });
        MediaServiceManager.instance().addAccountListener(this);
        updateAccountIcon();

        view.findViewById(R.id.drawer_about).setOnClickListener(v -> {
            if (mDrawer != null) {
                mDrawer.closeDrawer(GravityCompat.START);
            }
            startActivity(new Intent(getContext(), MobileAboutActivity.class));
        });

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        mGridSpan = getResources().getInteger(R.integer.mobile_grid_span);
        mGridCardWidth = screenWidth / mGridSpan;
        mHomeSpan = getResources().getInteger(R.integer.mobile_home_span);
        mHomeColWidth = computeHomeColWidth(screenWidth, mHomeSpan);
        mShelfCardWidth = (int) (screenWidth * 0.42f);

        mSectionAdapter = new SectionAdapter(this::onSectionPicked);
        RecyclerView sectionList = view.findViewById(R.id.section_list);
        sectionList.setLayoutManager(new LinearLayoutManager(getContext()));
        sectionList.setAdapter(mSectionAdapter);

        initBottomNav(view);

        // Open the menu with an on-screen right-swipe — the left screen edge belongs to
        // the system Back gesture and must not be used for this. See mSwipeToOpenMenu.
        mContentList.addOnItemTouchListener(mSwipeToOpenMenu);

        mSwipeRefresh.setColorSchemeResources(R.color.brand_accent);
        mSwipeRefresh.setProgressBackgroundColorSchemeResource(R.color.mobile_surface);
        mSwipeRefresh.setOnRefreshListener(() -> {
            // Settings section has nothing to reload; otherwise re-fetch the current section.
            if (mPresenter == null || mSettingsAdapter != null) {
                stopSwipeRefresh();
                return;
            }
            mSwipeRefreshing = true;
            mPresenter.refresh(false);
        });

        mPresenter = BrowsePresenter.instance(getContext());
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void onSectionPicked(BrowseSection section) {
        if (mDrawer != null) {
            mDrawer.closeDrawer(GravityCompat.START);
        }
        if (section.getType() == BrowseSection.TYPE_SETTINGS_GRID && getContext() != null) {
            // Settings is its own screen on top of this one, not a section: Back returns to the
            // section the user was on, untouched (no reload, scroll position kept).
            MobileSettingsActivity.start(getContext(), false);
            return;
        }
        selectSection(mSectionAdapter.indexOf(section), true);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        MediaServiceManager.instance().removeAccountListener(this);
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public void onAccountChanged(Account account) {
        updateAccountIcon();
    }

    /**
     * Load the signed-in account's circular avatar into the toolbar button — the touch-UI
     * counterpart of {@code NavigateTitleView.updateAccountIcon()}. Falls back to the default
     * account placeholder when no account is selected (or it has no avatar).
     */
    private void updateAccountIcon() {
        if (mAccountView == null) {
            return;
        }

        Account current = MediaServiceManager.instance().getSelectedAccount();

        if (current != null && current.getAvatarImageUrl() != null) {
            // A real photo: drop the placeholder's monochrome tint so it shows in full colour.
            ImageViewCompat.setImageTintList(mAccountView, null);
            Glide.with(this)
                    .load(current.getAvatarImageUrl())
                    .placeholder(R.drawable.browse_title_account)
                    .circleCrop()
                    .into(mAccountView);
        } else {
            Glide.with(this).clear(mAccountView);
            // Re-apply the toolbar tint so the placeholder matches the other toolbar icons.
            ImageViewCompat.setImageTintList(mAccountView, ColorStateList.valueOf(
                    ContextCompat.getColor(mAccountView.getContext(), R.color.mobile_text_primary)));
            mAccountView.setImageResource(R.drawable.browse_title_account);
        }
    }

    // ----- BrowseView -----

    @Override
    public void addSection(int index, BrowseSection section) {
        if (mSectionAdapter != null && section != null) {
            mSectionAdapter.add(index, section);
            rebuildCategoryChips();
            syncBottomNav();
            // Auto-open a default section so the user lands on videos rather than an empty
            // "pick a section" Home — notably after sign-in, where the section list is
            // rebuilt. Deferred via post() so a presenter-driven selection during the same
            // load wins (it sets mSectionSelected first); otherwise we open the first
            // section (the Home feed). Re-arms on removeAllSections (a full refresh).
            if (!mSectionSelected && mContentList != null) {
                mContentList.post(this::autoSelectDefaultSection);
            }
        }
    }

    private void autoSelectDefaultSection() {
        if (mSectionSelected || mSectionAdapter == null || mSectionAdapter.getItemCount() == 0) {
            return;
        }
        selectSection(0, false);
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (mSectionAdapter != null) {
            mSectionAdapter.remove(section);
            rebuildCategoryChips();
            syncBottomNav();
        }
    }

    @Override
    public void removeAllSections() {
        if (mSectionAdapter != null) {
            mSectionAdapter.clear();
        }
        rebuildCategoryChips();
        syncBottomNav();
        // Section list is being rebuilt — re-arm the default-section auto-open.
        mSectionSelected = false;
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (mSectionAdapter == null || index < 0 || index >= mSectionAdapter.getItemCount()) {
            return;
        }
        BrowseSection section = mSectionAdapter.getItem(index);
        mSectionSelected = true;
        mCurrentSectionId = section.getId();
        mSectionAdapter.setSelected(section);
        boolean isHome = section.getId() == MediaGroup.TYPE_HOME;
        if (mToolbarLogo != null) {
            mToolbarLogo.setVisibility(isHome ? View.VISIBLE : View.GONE);
        }
        if (mToolbarTitle != null) {
            mToolbarTitle.setText(isHome ? getString(R.string.app_name) : section.getTitle());
        }
        rebuildCategoryChips();
        syncBottomNav();
        setupContentForSection(section);
        if (mPresenter != null) {
            mPresenter.onSectionFocused(section.getId());
        }
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null || mContentList == null) {
            return;
        }
        switch (group.getAction()) {
            case VideoGroup.ACTION_REPLACE:
                mLastHomePagedCount = -1;
                if (mHomeAdapter != null) mHomeAdapter.clear();
                if (mShelfAdapter != null) mShelfAdapter.clear();
                if (mGridAdapter != null) mGridAdapter.clear();
                hideEmptyMessage();
                // Local grids (Notifications, Playback queue, Blocked channels) deliver their whole
                // list as one REPLACE group, not clear-then-append.
                if (!group.isEmpty() && mGridAdapter != null) {
                    mGridAdapter.setVideos(group.getVideos());
                }
                break;
            case VideoGroup.ACTION_REMOVE:
            case VideoGroup.ACTION_REMOVE_AUTHOR:
                if (mGridAdapter != null) mGridAdapter.remove(group.getVideos());
                if (mShelfAdapter != null) mShelfAdapter.removeVideos(group.getVideos());
                if (mHomeAdapter != null) mHomeAdapter.removeVideos(group.getVideos());
                break;
            case VideoGroup.ACTION_SYNC:
                // Percent-watched refresh (e.g. back from the player): redraw the watched bars.
                if (mGridAdapter != null) mGridAdapter.sync(group.getVideos());
                if (mShelfAdapter != null) mShelfAdapter.syncVideos(group.getVideos());
                if (mHomeAdapter != null) mHomeAdapter.syncVideos(group.getVideos());
                break;
            default: // ACTION_APPEND / ACTION_PREPEND
                if (group.isEmpty()) {
                    break;
                }
                hideEmptyMessage();
                if (mHomeAdapter != null) {
                    mHomeAdapter.appendGroup(group);
                    mContentList.post(this::maybePrefetchMoreHome);
                } else if (mShelfAdapter != null) {
                    mShelfAdapter.appendGroup(group);
                } else if (mGridAdapter != null) {
                    // A continuation carries the full cumulative list, so replace.
                    mGridAdapter.setVideos(group.getVideos());
                }
                break;
        }
    }

    @Override
    public void updateSection(SettingsGroup group) {
        if (group == null || mContentList == null) {
            return;
        }
        hideEmptyMessage();
        mHomeAdapter = null;
        mShelfAdapter = null;
        mGridAdapter = null;
        mSettingsAdapter = new SettingsItemAdapter(MobileSettingsRows.build(getActivity(), group.getItems()));
        mContentList.setLayoutManager(new LinearLayoutManager(getContext()));
        mContentList.setAdapter(mSettingsAdapter);
    }

    @Override
    public void clearSection(BrowseSection section) {
        if (mHomeAdapter != null) mHomeAdapter.clear();
        if (mShelfAdapter != null) mShelfAdapter.clear();
        if (mGridAdapter != null) mGridAdapter.clear();
    }

    @Override
    public void selectSectionItem(int index) {
        // Touch UI: no D-pad selection to restore.
    }

    @Override
    public void selectSectionItem(Video item) {
        // Touch UI: no D-pad selection to restore.
    }

    @Override
    public void showError(ErrorFragmentData data) {
        if (mEmptyContainer == null) {
            return;
        }
        mEmptyMessage.setText(data != null ? data.getMessage() : "");
        if (data != null && data.getActionText() != null) {
            mEmptyButton.setText(data.getActionText());
            mEmptyButton.setOnClickListener(v -> data.onAction());
            mEmptyButton.setVisibility(View.VISIBLE);
        } else {
            mEmptyButton.setVisibility(View.GONE);
        }
        mEmptyContainer.setVisibility(View.VISIBLE);
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgressShowing = show;
        // While pull-to-refresh is active the SwipeRefreshLayout spinner stands in for
        // the centre bar; don't show both.
        if (mProgressBar != null) {
            mProgressBar.setVisibility(show && !mSwipeRefreshing ? View.VISIBLE : View.GONE);
        }
        if (!show) {
            stopSwipeRefresh();
        }
    }

    private void stopSwipeRefresh() {
        mSwipeRefreshing = false;
        if (mSwipeRefresh != null) {
            mSwipeRefresh.setRefreshing(false);
        }
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgressShowing;
    }

    @Override
    public void focusOnContent() {
        // Touch UI: no focus to move.
    }

    @Override
    public boolean isEmpty() {
        return (mHomeAdapter == null || mHomeAdapter.getItemCount() == 0)
                && (mGridAdapter == null || mGridAdapter.getItemCount() == 0)
                && (mShelfAdapter == null || mShelfAdapter.getItemCount() == 0)
                && (mSettingsAdapter == null || mSettingsAdapter.getItemCount() == 0);
    }

    @Override
    public void updateBadge() {
        // Keep the toolbar account avatar in sync (the phone counterpart of the TV account orb).
        updateAccountIcon();
    }

    // ----- helpers -----

    private int computeHomeColWidth(int screenWidth, int spanCount) {
        float density = getResources().getDisplayMetrics().density;
        int outerPad = Math.round(12 * density); // 6dp left + 6dp right on mContentList
        int perCardPad = Math.round(12 * density); // 6dp start + 6dp end on mobile_home_video_card
        return Math.max(1, (screenWidth - outerPad) / Math.max(1, spanCount) - perCardPad);
    }

    private void setupContentForSection(BrowseSection section) {
        hideEmptyMessage();
        mContentList.clearOnScrollListeners();
        int type = section.getType();
        float density = getResources().getDisplayMetrics().density;
        int bottomPad = Math.round(8 * density);
        if (section.getId() == MediaGroup.TYPE_HOME) {
            mLastHomePagedCount = -1;
            int sidePad = Math.round(6 * density);
            mContentList.setPadding(sidePad, Math.round(4 * density), sidePad, bottomPad);
            mHomeAdapter = new HomeFeedAdapter(mHomeColWidth, mVideoClick, mVideoLongClick);
            mShelfAdapter = null;
            mGridAdapter = null;
            mSettingsAdapter = null;
            StaggeredGridLayoutManager lm = new StaggeredGridLayoutManager(
                    mHomeSpan, StaggeredGridLayoutManager.VERTICAL);
            lm.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_NONE);
            mContentList.setLayoutManager(lm);
            mContentList.setAdapter(mHomeAdapter);
            mContentList.addOnScrollListener(mHomeScrollListener);
        } else if (type == BrowseSection.TYPE_ROW) {
            mContentList.setPadding(0, 0, 0, bottomPad);
            mShelfAdapter = new ShelfAdapter(mShelfCardWidth, mVideoClick, mVideoLongClick,
                    mShelfScrollEnd);
            mHomeAdapter = null;
            mGridAdapter = null;
            mSettingsAdapter = null;
            mContentList.setLayoutManager(new LinearLayoutManager(getContext()));
            mContentList.setAdapter(mShelfAdapter);
        } else if (type == BrowseSection.TYPE_SETTINGS_GRID) {
            mContentList.setPadding(0, 0, 0, bottomPad);
            // The adapter is supplied separately via updateSection(SettingsGroup).
            mHomeAdapter = null;
            mShelfAdapter = null;
            mGridAdapter = null;
        } else {
            mContentList.setPadding(0, 0, 0, bottomPad);
            mGridAdapter = new VideoCardAdapter(mGridCardWidth, mVideoClick, mVideoLongClick);
            if (type == BrowseSection.TYPE_SHORTS_GRID) {
                mGridAdapter.setAdaptiveStyles(true);
                mGridAdapter.setVerticalShorts(true);
            }
            mHomeAdapter = null;
            mShelfAdapter = null;
            mSettingsAdapter = null;
            mContentList.setLayoutManager(new GridLayoutManager(getContext(), mGridSpan));
            mContentList.setAdapter(mGridAdapter);
            mContentList.addOnScrollListener(mGridScrollListener);
        }
    }

    // Hosting activity declares configChanges="orientation|..." so it is NOT recreated on
    // rotation; re-read the grid span (values-sw600dp-land widens it) and resize cards.
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        mGridSpan = getResources().getInteger(R.integer.mobile_grid_span);
        mGridCardWidth = screenWidth / mGridSpan;
        mHomeSpan = getResources().getInteger(R.integer.mobile_home_span);
        mHomeColWidth = computeHomeColWidth(screenWidth, mHomeSpan);
        mShelfCardWidth = (int) (screenWidth * 0.42f);

        if (mContentList != null && mContentList.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) mContentList.getLayoutManager()).setSpanCount(mGridSpan);
        } else if (mContentList != null
                && mContentList.getLayoutManager() instanceof StaggeredGridLayoutManager) {
            ((StaggeredGridLayoutManager) mContentList.getLayoutManager()).setSpanCount(mHomeSpan);
        }
        if (mGridAdapter != null) {
            mGridAdapter.setCardWidth(mGridCardWidth);
        }
        if (mHomeAdapter != null) {
            mHomeAdapter.setColumnWidth(mHomeColWidth);
        }
    }

    private void initBottomNav(View root) {
        bindNavTab(root, R.id.nav_home, MediaGroup.TYPE_HOME);
        bindNavTab(root, R.id.nav_shorts, MediaGroup.TYPE_SHORTS);
        bindNavTab(root, R.id.nav_subscriptions, MediaGroup.TYPE_SUBSCRIPTIONS);
        bindNavTab(root, R.id.nav_playlists, MediaGroup.TYPE_USER_PLAYLISTS);
        bindNavTab(root, R.id.nav_history, MediaGroup.TYPE_HISTORY);
        syncBottomNav();
    }

    private void bindNavTab(View root, int tabId, int sectionId) {
        View tab = root.findViewById(tabId);
        if (tab == null) {
            return;
        }
        tab.setOnClickListener(v -> {
            if (mSectionAdapter == null) {
                return;
            }
            int idx = mSectionAdapter.indexOfId(sectionId);
            if (idx >= 0) {
                selectSection(idx, false);
            }
        });
    }

    private void syncBottomNav() {
        View root = getView();
        if (root == null) {
            return;
        }
        syncNavTab(root, R.id.nav_home, R.id.nav_home_icon, R.id.nav_home_label, MediaGroup.TYPE_HOME);
        syncNavTab(root, R.id.nav_shorts, R.id.nav_shorts_icon, R.id.nav_shorts_label, MediaGroup.TYPE_SHORTS);
        syncNavTab(root, R.id.nav_subscriptions, R.id.nav_subscriptions_icon, R.id.nav_subscriptions_label, MediaGroup.TYPE_SUBSCRIPTIONS);
        syncNavTab(root, R.id.nav_playlists, R.id.nav_playlists_icon, R.id.nav_playlists_label, MediaGroup.TYPE_USER_PLAYLISTS);
        syncNavTab(root, R.id.nav_history, R.id.nav_history_icon, R.id.nav_history_label, MediaGroup.TYPE_HISTORY);
    }

    private void syncNavTab(View root, int tabId, int iconId, int labelId, int sectionId) {
        View tab = root.findViewById(tabId);
        if (tab == null) {
            return;
        }
        boolean available = mSectionAdapter != null && mSectionAdapter.indexOfId(sectionId) >= 0;
        tab.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available) {
            return;
        }
        boolean selected = sectionId == mCurrentSectionId;
        int color = ContextCompat.getColor(requireContext(),
                selected ? R.color.mobile_text_primary : R.color.mobile_text_secondary);
        ImageView icon = root.findViewById(iconId);
        if (icon != null) {
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color));
        }
        TextView label = root.findViewById(labelId);
        if (label != null) {
            label.setTextColor(color);
            label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void rebuildCategoryChips() {
        if (mHomeChips == null || mHomeChipsScroll == null || mSectionAdapter == null) {
            return;
        }
        mHomeChips.removeAllViews();
        int count = 0;
        float density = getResources().getDisplayMetrics().density;
        int padH = Math.round(14 * density);
        int padV = Math.round(7 * density);
        // Put Home first, then content sections (skip utility-only drawer entries like Notifications/Queue).
        java.util.List<Integer> ordered = new java.util.ArrayList<>(mSectionAdapter.getItemCount());
        int homeIdx = mSectionAdapter.indexOfId(MediaGroup.TYPE_HOME);
        if (homeIdx >= 0) {
            ordered.add(homeIdx);
        }
        for (int i = 0; i < mSectionAdapter.getItemCount(); i++) {
            if (i == homeIdx) {
                continue;
            }
            BrowseSection section = mSectionAdapter.getItem(i);
            if (section == null || section.getType() == BrowseSection.TYPE_SETTINGS_GRID
                    || section.getTitle() == null || section.getTitle().isEmpty()) {
                continue;
            }
            int id = section.getId();
            if (id == MediaGroup.TYPE_NOTIFICATIONS || id == MediaGroup.TYPE_PLAYBACK_QUEUE
                    || id == MediaGroup.TYPE_BLOCKED_CHANNELS) {
                continue;
            }
            ordered.add(i);
        }
        for (int sectionIndex : ordered) {
            BrowseSection section = mSectionAdapter.getItem(sectionIndex);
            boolean selected = section.getId() == mCurrentSectionId;
            TextView chip = new TextView(requireContext());
            chip.setText(section.getTitle());
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setSingleLine(true);
            chip.setBackgroundResource(R.drawable.bg_sort_chip);
            chip.setPadding(padH, padV, padH, padV);
            chip.setSelected(selected);
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setTextColor(ContextCompat.getColor(requireContext(),
                    selected ? R.color.mobile_background : R.color.mobile_text_primary));
            chip.setOnClickListener(v -> selectSection(sectionIndex, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(8 * density));
            mHomeChips.addView(chip, lp);
            count++;
        }
        mHomeChipsScroll.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
    }

    private final VideoCardAdapter.OnVideoAction mVideoClick = video -> {
        if (mPresenter == null) {
            return;
        }
        // On the "Channels" multi-grid section, onVideoItemSelected mirrors the TV
        // two-pane behavior: it replaces the grid with the tapped channel's uploads
        // (meant for the TV's right-hand pane). The phone has a single grid, so that
        // silently overwrites the channel list — and it stays overwritten after returning
        // from the channel page until a manual pull-to-refresh. Skip the selection step
        // here; onVideoItemClicked still opens the channel page. Every other section keeps
        // onVideoItemSelected so scroll-position memory works.
        if (!mPresenter.isMultiGridChannelUploadsSection()) {
            mPresenter.onVideoItemSelected(video);
        }
        mPresenter.onVideoItemClicked(video);
    };

    private final VideoCardAdapter.OnVideoAction mVideoLongClick = video -> {
        if (mPresenter != null) {
            mPresenter.onVideoItemLongClicked(video);
        }
    };

    private final ShelfAdapter.OnShelfScrollEnd mShelfScrollEnd = last -> {
        if (mPresenter != null && last != null) {
            mPresenter.onScrollEnd(last);
        }
    };

    private final RecyclerView.OnScrollListener mGridScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            if (dy <= 0 || mProgressShowing || mGridAdapter == null) {
                return;
            }
            RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
            if (!(lm instanceof GridLayoutManager)) {
                return;
            }
            int lastVisible = ((GridLayoutManager) lm).findLastVisibleItemPosition();
            if (lastVisible >= mGridAdapter.getItemCount() - 4) {
                Video last = mGridAdapter.getLast();
                if (last != null && mPresenter != null) {
                    mPresenter.onScrollEnd(last);
                }
            }
        }
    };

    private void maybePrefetchMoreHome() {
        if (mHomeAdapter == null || mContentList == null || mPresenter == null || mProgressShowing) {
            return;
        }
        int count = mHomeAdapter.getItemCount();
        if (count == 0 || count == mLastHomePagedCount || count >= 12) {
            return;
        }
        if (!mContentList.canScrollVertically(1)) {
            Video last = mHomeAdapter.getLastMainVideo();
            if (last != null) {
                mLastHomePagedCount = count;
                mPresenter.onScrollEnd(last);
            }
        }
    }

    private final RecyclerView.OnScrollListener mHomeScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            if (dy <= 0 || mProgressShowing || mHomeAdapter == null) {
                return;
            }
            RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
            if (!(lm instanceof StaggeredGridLayoutManager)) {
                return;
            }
            int[] positions = ((StaggeredGridLayoutManager) lm).findLastVisibleItemPositions(null);
            int lastVisible = -1;
            for (int pos : positions) {
                if (pos > lastVisible) {
                    lastVisible = pos;
                }
            }
            int count = mHomeAdapter.getItemCount();
            if (count > 0 && count != mLastHomePagedCount && lastVisible >= count - 6) {
                Video last = mHomeAdapter.getLastMainVideo();
                if (last != null && mPresenter != null) {
                    mLastHomePagedCount = count;
                    mPresenter.onScrollEnd(last);
                }
            }
        }
    };

    private void showEmptyMessage(String message) {
        if (mEmptyContainer == null) {
            return;
        }
        mEmptyMessage.setText(message);
        mEmptyButton.setVisibility(View.GONE);
        mEmptyContainer.setVisibility(View.VISIBLE);
    }

    private void hideEmptyMessage() {
        if (mEmptyContainer != null) {
            mEmptyContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Opens the drawer on an on-screen right-swipe. Registered on the outer content
     * RecyclerView, so it is consulted before the inner shelves: a shelf that can still
     * scroll toward its start keeps the gesture; a shelf already at its start (or a grid
     * section with no horizontal scroller) yields it to open the menu.
     */
    private final RecyclerView.OnItemTouchListener mSwipeToOpenMenu = new RecyclerView.OnItemTouchListener() {
        private float mDownX;
        private float mDownY;
        private boolean mDecided;
        private boolean mTriggered;

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getX();
                    mDownY = e.getY();
                    mDecided = false;
                    mTriggered = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (mDecided) {
                        return mTriggered;
                    }
                    float dx = e.getX() - mDownX;
                    float dy = e.getY() - mDownY;
                    int slop = ViewConfiguration.get(rv.getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) < slop * 2 && Math.abs(dy) < slop * 2) {
                        return false; // wait for a clear direction
                    }
                    mDecided = true;
                    if (dx > 0 && dx > Math.abs(dy) && shelfAtStart(rv, mDownX, mDownY)) {
                        mTriggered = true;
                        if (mDrawer != null) {
                            mDrawer.openDrawer(GravityCompat.START);
                        }
                    }
                    return mTriggered;
                default:
                    return false;
            }
        }

        @Override
        public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            // Gesture is fully handled in onInterceptTouchEvent.
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            // no-op
        }
    };

    /**
     * True if a right-swipe at (x, y) should open the menu: either there is no horizontal
     * shelf under the finger (grid section or empty area), or the shelf there is already
     * at its start and cannot scroll further toward it.
     */
    private boolean shelfAtStart(RecyclerView contentList, float x, float y) {
        View child = contentList.findChildViewUnder(x, y);
        if (child == null) {
            return true;
        }
        View shelfList = child.findViewById(R.id.shelf_list);
        if (shelfList == null) {
            return true;
        }
        return !shelfList.canScrollHorizontally(-1);
    }
}
