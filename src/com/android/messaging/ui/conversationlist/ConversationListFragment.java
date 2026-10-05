/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2025 The LineageOS Project
 * Copyright (C) 2026 Anshuman_X (maxxcodebug) - MaxxOS design (modifications)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.ui.conversationlist;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewPropertyAnimator;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewGroupCompat;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.messaging.R;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.data.ConversationListData;
import com.android.messaging.datamodel.data.ConversationListData.ConversationListDataListener;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.ui.ListEmptyView;
import com.android.messaging.ui.SnackBarInteraction;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.ImeUtil;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UiUtils;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a list of conversations.
 */
public class ConversationListFragment extends Fragment implements ConversationListDataListener,
        ConversationListItemView.HostInterface {
    private static final String BUNDLE_ARCHIVED_MODE = "archived_mode";
    private static final String BUNDLE_FORWARD_MESSAGE_MODE = "forward_message_mode";

    private MenuItem mShowBlockedMenuItem;
    private boolean mArchiveMode;
    private boolean mBlockedAvailable;
    private boolean mForwardMessageMode;

    public interface ConversationListFragmentHost {
        void onConversationClick(final ConversationListData listData,
                                        final ConversationListItemData conversationListItemData,
                                        final boolean isLongClick,
                                        final ConversationListItemView conversationView);
        void onCreateConversationClick();
        boolean isConversationSelected(final String conversationId);
        boolean isSwipeAnimatable();
        boolean isSelectionMode();
        boolean hasWindowFocus();
    }

    private ConversationListFragmentHost mHost;
    private RecyclerView mRecyclerView;
    private ExtendedFloatingActionButton mStartNewConversationButton;
    private ListEmptyView mEmptyListMessageView;
    private ConversationListAdapter mAdapter;

    // MaxxOS header / filter state
    private Cursor mRawCursor;
    private int mMaxxFilter = MaxxConversationFilterCursor.FILTER_ALL;
    private String mMaxxQuery = "";
    private View mMaxxSearchButton;
    private View mMaxxTitleBlock;
    private EditText mMaxxSearchInput;
    private final TextView[] mMaxxChips = new TextView[4];

    // Saved Instance State Data - only for temporal data which is nice to maintain but not
    // critical for correctness.
    private static final String SAVED_INSTANCE_STATE_LIST_VIEW_STATE_KEY =
            "conversationListViewState";
    private Parcelable mListState;

    final Binding<ConversationListData> mListBinding = BindingBase.createBinding(this);

    public static ConversationListFragment createArchivedConversationListFragment() {
        return createConversationListFragment(BUNDLE_ARCHIVED_MODE);
    }

    public static ConversationListFragment createForwardMessageConversationListFragment() {
        return createConversationListFragment(BUNDLE_FORWARD_MESSAGE_MODE);
    }

    public static ConversationListFragment createConversationListFragment(String modeKeyName) {
        final ConversationListFragment fragment = new ConversationListFragment();
        if (modeKeyName != null) {
            final Bundle bundle = new Bundle();
            bundle.putBoolean(modeKeyName, true);
            fragment.setArguments(bundle);
        }
        return fragment;
    }

    /**
     * {@inheritDoc} from Fragment
     */
    @Override
    public void onCreate(final Bundle bundle) {
        super.onCreate(bundle);
        mListBinding.getData().init(LoaderManager.getInstance(this), mListBinding);
        mAdapter = new ConversationListAdapter(getActivity(), null, this);
    }

    @Override
    public void onResume() {
        super.onResume();

        mHost = (ConversationListFragmentHost) getActivity();
        setScrolledToNewestConversationIfNeeded();

        updateUi();
    }

    public void setScrolledToNewestConversationIfNeeded() {
        if (!mArchiveMode
                && !mForwardMessageMode
                && isScrolledToFirstConversation()
                && mHost.hasWindowFocus()) {
            mListBinding.getData().setScrolledToNewestConversation(true);
        }
    }

    private boolean isScrolledToFirstConversation() {
        int firstItemPosition = ((LinearLayoutManager) mRecyclerView.getLayoutManager())
                .findFirstCompletelyVisibleItemPosition();
        return firstItemPosition == 0;
    }

    /**
     * {@inheritDoc} from Fragment
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        mListBinding.unbind();
        mHost = null;
    }

    /**
     * {@inheritDoc} from Fragment
     */
    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.conversation_list_fragment,
                container, false);
        mRecyclerView = rootView.findViewById(android.R.id.list);
        mEmptyListMessageView = rootView.findViewById(R.id.no_conversations_view);
        mEmptyListMessageView.setImageHint(R.drawable.ic_oobe_conv_list);
        // The default behavior for default layout param generation by LinearLayoutManager is to
        // provide width and height of WRAP_CONTENT, but this is not desirable for
        // ConversationListFragment; the view in each row should be a width of MATCH_PARENT so that
        // the entire row is tappable.
        final Activity activity = getActivity();
        final LinearLayoutManager manager = new LinearLayoutManager(activity) {
            @Override
            public RecyclerView.LayoutParams generateDefaultLayoutParams() {
                return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        };
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            int mCurrentState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView, final int dx,
                                   final int dy) {
                if (mCurrentState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL
                        || mCurrentState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
                    ImeUtil.get().hideImeKeyboard(getActivity(), mRecyclerView);
                }

                if (isScrolledToFirstConversation()) {
                    setScrolledToNewestConversationIfNeeded();
                } else {
                    mListBinding.getData().setScrolledToNewestConversation(false);
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull final RecyclerView recyclerView,
                                             final int newState) {
                mCurrentState = newState;
            }
        });
        mRecyclerView.addOnItemTouchListener(new ConversationListSwipeHelper(mRecyclerView));

        if (savedInstanceState != null) {
            mListState = savedInstanceState.getParcelable(SAVED_INSTANCE_STATE_LIST_VIEW_STATE_KEY,
                    Parcelable.class);
        }

        mStartNewConversationButton = rootView.findViewById(R.id.start_new_conversation_button);
        if (mArchiveMode || mForwardMessageMode) {
            mStartNewConversationButton.setVisibility(View.GONE);
        } else {
            mStartNewConversationButton.setVisibility(View.VISIBLE);
            mStartNewConversationButton.setOnClickListener(clickView ->
                    mHost.onCreateConversationClick());
        }

        setupMaxxChrome(rootView);

        // The root view has a non-null background, which by default is deemed by the framework
        // to be a "transition group," where all child views are animated together during an
        // activity transition. However, we want each individual items in the recycler view to
        // show explode animation themselves, so we explicitly tag the root view to be a non-group.
        ViewGroupCompat.setTransitionGroup(rootView, false);

        setHasOptionsMenu(true);
        return rootView;
    }

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        LogUtil.v(LogUtil.BUGLE_TAG, "Attaching List");
        final Bundle arguments = getArguments();
        if (arguments != null) {
            mArchiveMode = arguments.getBoolean(BUNDLE_ARCHIVED_MODE, false);
            mForwardMessageMode = arguments.getBoolean(BUNDLE_FORWARD_MESSAGE_MODE, false);
        }
        mListBinding.bind(DataModel.get().createConversationListData(context, this, mArchiveMode));
    }


    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mListState != null) {
            outState.putParcelable(SAVED_INSTANCE_STATE_LIST_VIEW_STATE_KEY, mListState);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mListState = mRecyclerView.getLayoutManager().onSaveInstanceState();
        mListBinding.getData().setScrolledToNewestConversation(false);
    }

    @Override
    public void onConversationListCursorUpdated(final ConversationListData data,
            final Cursor cursor) {
        mListBinding.ensureBound(data);
        mRawCursor = cursor;
        final Cursor shown = buildMaxxCursor(cursor);
        final Cursor oldCursor = mAdapter.swapCursor(shown);
        updateEmptyListUi(shown == null || shown.getCount() == 0);
        if (mListState != null && cursor != null && oldCursor == null) {
            mRecyclerView.getLayoutManager().onRestoreInstanceState(mListState);
        }
    }

    @Override
    public void setBlockedParticipantsAvailable(final boolean blockedAvailable) {
        mBlockedAvailable = blockedAvailable;
        if (mShowBlockedMenuItem != null) {
            mShowBlockedMenuItem.setVisible(blockedAvailable);
        }
    }

    public void updateUi() {
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull final Menu menu) {
        super.onPrepareOptionsMenu(menu);
        final MenuItem startNewConversationMenuItem =
                menu.findItem(R.id.action_start_new_conversation);
        if (startNewConversationMenuItem != null) {
            // It is recommended for the Floating Action button functionality to be duplicated as a
            // menu
            AccessibilityManager accessibilityManager = (AccessibilityManager)
                    requireActivity().getSystemService(Context.ACCESSIBILITY_SERVICE);
            startNewConversationMenuItem.setVisible(accessibilityManager
                    .isTouchExplorationEnabled());
        }

        final MenuItem archive = menu.findItem(R.id.action_show_archived);
        if (archive != null) {
            archive.setVisible(true);
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (!isAdded()) {
            // Guard against being called before we're added to the activity
            return;
        }

        mShowBlockedMenuItem = menu.findItem(R.id.action_show_blocked_contacts);
        if (mShowBlockedMenuItem != null) {
            mShowBlockedMenuItem.setVisible(mBlockedAvailable);
        }
    }

    /**
     * {@inheritDoc} from ConversationListItemView.HostInterface
     */
    @Override
    public void onConversationClicked(final ConversationListItemData conversationListItemData,
            final boolean isLongClick, final ConversationListItemView conversationView) {
        final ConversationListData listData = mListBinding.getData();
        mHost.onConversationClick(listData, conversationListItemData, isLongClick,
                conversationView);
    }

    /**
     * {@inheritDoc} from ConversationListItemView.HostInterface
     */
    @Override
    public boolean isConversationSelected(final String conversationId) {
        return mHost.isConversationSelected(conversationId);
    }

    @Override
    public boolean isSwipeAnimatable() {
        return mHost.isSwipeAnimatable();
    }

    // Show and hide empty list UI as needed with appropriate text based on view specifics
    private void updateEmptyListUi(final boolean isEmpty) {
        if (isEmpty) {
            int emptyListText;
            if (!mListBinding.getData().getHasFirstSyncCompleted()) {
                emptyListText = R.string.conversation_list_first_sync_text;
            } else if (mArchiveMode) {
                emptyListText = R.string.archived_conversation_list_empty_text;
            } else {
                emptyListText = R.string.conversation_list_empty_text;
            }
            mEmptyListMessageView.setTextHint(emptyListText);
            mEmptyListMessageView.setVisibility(View.VISIBLE);
            mEmptyListMessageView.setIsImageVisible(true);
            mEmptyListMessageView.setIsVerticallyCentered(true);
        } else {
            mEmptyListMessageView.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------------------------------
    // MaxxOS header, filter chips, search and floating navigation
    // ---------------------------------------------------------------------------------------

    private Cursor buildMaxxCursor(final Cursor raw) {
        if (raw == null) {
            return null;
        }
        if (mMaxxFilter == MaxxConversationFilterCursor.FILTER_ALL && mMaxxQuery.isEmpty()) {
            return raw;
        }
        return new MaxxConversationFilterCursor(raw, mMaxxFilter, mMaxxQuery);
    }

    private void applyMaxxFilter() {
        if (mRawCursor == null || mAdapter == null) {
            return;
        }
        final Cursor shown = buildMaxxCursor(mRawCursor);
        mAdapter.swapCursor(shown);
        updateEmptyListUi(shown == null || shown.getCount() == 0);
    }

    private void setMaxxFilter(final int filter) {
        mMaxxFilter = filter;
        for (int i = 0; i < mMaxxChips.length; i++) {
            if (mMaxxChips[i] != null) {
                mMaxxChips[i].setSelected(i == filter);
            }
        }
        applyMaxxFilter();
    }

    private void setupMaxxChrome(final ViewGroup rootView) {
        final View header = rootView.findViewById(R.id.maxx_header_container);
        final View nav = rootView.findViewById(R.id.maxx_bottom_nav);
        if (mArchiveMode || mForwardMessageMode) {
            // Keep the stock look for archived / forward-message pickers.
            header.setVisibility(View.GONE);
            nav.setVisibility(View.GONE);
            return;
        }

        // Filter chips
        final int[] chipIds = {R.id.maxx_chip_all, R.id.maxx_chip_personal,
                R.id.maxx_chip_business, R.id.maxx_chip_otp};
        for (int i = 0; i < chipIds.length; i++) {
            final int filter = i;
            mMaxxChips[i] = rootView.findViewById(chipIds[i]);
            mMaxxChips[i].setSelected(i == mMaxxFilter);
            mMaxxChips[i].setOnClickListener(v -> setMaxxFilter(filter));
        }

        // Search
        mMaxxSearchButton = rootView.findViewById(R.id.maxx_search_button);
        mMaxxTitleBlock = rootView.findViewById(R.id.maxx_title_block);
        mMaxxSearchInput = rootView.findViewById(R.id.maxx_search_input);
        mMaxxSearchButton.setOnClickListener(v -> {
            if (mMaxxSearchInput.getVisibility() == View.VISIBLE) {
                closeMaxxSearch();
            } else {
                openMaxxSearch();
            }
        });
        mMaxxSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int a, final int b,
                    final int c) {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int a, final int b,
                    final int c) {
            }

            @Override
            public void afterTextChanged(final Editable e) {
                mMaxxQuery = e == null ? "" : e.toString();
                applyMaxxFilter();
            }
        });

        // Overflow menu
        rootView.findViewById(R.id.maxx_more_button).setOnClickListener(this::showMaxxOverflow);

        // Bottom navigation
        rootView.findViewById(R.id.maxx_nav_contacts).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        ContactsContract.Contacts.CONTENT_URI));
            } catch (final ActivityNotFoundException e) {
                LogUtil.w(LogUtil.BUGLE_TAG, "No contacts app available");
            }
        });
        rootView.findViewById(R.id.maxx_nav_settings).setOnClickListener(v ->
                UIIntents.get().launchSettingsActivity(getActivity()));
        final View assistant = rootView.findViewById(R.id.maxx_nav_assistant);
        // Hidden until the Assistant tab is given an action (see maxx_show_assistant_tab).
        assistant.setVisibility(getResources().getBoolean(R.bool.maxx_show_assistant_tab)
                ? View.VISIBLE : View.GONE);
    }

    private void openMaxxSearch() {
        mMaxxTitleBlock.setVisibility(View.INVISIBLE);
        mMaxxSearchInput.setVisibility(View.VISIBLE);
        mMaxxSearchInput.requestFocus();
        final InputMethodManager imm = (InputMethodManager) requireActivity()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(mMaxxSearchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeMaxxSearch() {
        mMaxxSearchInput.setText("");
        mMaxxSearchInput.setVisibility(View.GONE);
        mMaxxTitleBlock.setVisibility(View.VISIBLE);
        ImeUtil.get().hideImeKeyboard(getActivity(), mMaxxSearchInput);
    }

    private void showMaxxOverflow(final View anchor) {
        final PopupMenu popup = new PopupMenu(requireActivity(), anchor);
        final Menu menu = popup.getMenu();
        final int archived = 1, blocked = 2, settings = 3;
        menu.add(Menu.NONE, archived, Menu.NONE, R.string.action_menu_show_archived);
        if (mBlockedAvailable) {
            menu.add(Menu.NONE, blocked, Menu.NONE, R.string.blocked_contacts_title);
        }
        menu.add(Menu.NONE, settings, Menu.NONE, R.string.action_settings);
        popup.setOnMenuItemClickListener(item -> {
            final int id = item.getItemId();
            if (id == archived) {
                UIIntents.get().launchArchivedConversationsActivity(getActivity());
            } else if (id == blocked) {
                UIIntents.get().launchBlockedParticipantsActivity(getActivity());
            } else if (id == settings) {
                UIIntents.get().launchSettingsActivity(getActivity());
            }
            return true;
        });
        popup.show();
    }

    @Override
    public List<SnackBarInteraction> getSnackBarInteractions() {
        final List<SnackBarInteraction> interactions = new ArrayList<>(1);
        final SnackBarInteraction fabInteraction =
                new SnackBarInteraction.BasicSnackBarInteraction(mStartNewConversationButton);
        interactions.add(fabInteraction);
        return interactions;
    }

    private ViewPropertyAnimator getNormalizedFabAnimator() {
        return mStartNewConversationButton.animate()
                .setInterpolator(UiUtils.DEFAULT_INTERPOLATOR)
                .setDuration(getActivity().getResources().getInteger(
                        R.integer.fab_animation_duration_ms));
    }

    public void dismissFab() {
        // To prevent clicking while animating.
        mStartNewConversationButton.setEnabled(false);
        final MarginLayoutParams lp =
                (MarginLayoutParams) mStartNewConversationButton.getLayoutParams();
        final float fabWidthWithLeftRightMargin = mStartNewConversationButton.getWidth()
                + lp.leftMargin + lp.rightMargin;
        final int direction = AccessibilityUtil.isLayoutRtl(mStartNewConversationButton) ? -1 : 1;
        getNormalizedFabAnimator().translationX(direction * fabWidthWithLeftRightMargin);
    }

    public void showFab() {
        getNormalizedFabAnimator().translationX(0).withEndAction(() -> {
            // Re-enable clicks after the animation.
            mStartNewConversationButton.setEnabled(true);
        });
    }

    @Override
    public void startFullScreenPhotoViewer(
            final Uri initialPhoto, final Rect initialPhotoBounds, final Uri photosUri) {
        UIIntents.get().launchFullScreenPhotoViewer(
                getActivity(), initialPhoto, initialPhotoBounds, photosUri);
    }

    @Override
    public void startFullScreenVideoViewer(final Uri videoUri) {
        UIIntents.get().launchFullScreenVideoViewer(getActivity(), videoUri);
    }

    @Override
    public boolean isSelectionMode() {
        return mHost != null && mHost.isSelectionMode();
    }
}
