/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.media.session.MediaController.TransportControls;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.UserHandle;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Interfaces with the {@link MediaSessionManager} to compose the right set of actions to show (only
 * if there are no actions from the PiP activity itself). The active media controller is only set
 * when there is a media session from the top PiP activity.
 */
public class PipMediaController {

    private static final String ACTION_PLAY = "com.android.systemui.pip.phone.PLAY";
    private static final String ACTION_PAUSE = "com.android.systemui.pip.phone.PAUSE";

    /**
     * A listener interface to receive notification on changes to the media actions.
     */
    public interface ActionListener {
        /**
         * Called when the media actions changes.
         */
        void onMediaActionsChanged(List<RemoteAction> actions);
    }

    private final Context mContext;
    private final IActivityManager mActivityManager;

    private final MediaSessionManager mMediaSessionManager;
    private MediaController mMediaController;

    private RemoteAction mPauseAction;
    private RemoteAction mPlayAction;

    private BroadcastReceiver mPlayPauseActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(ACTION_PLAY)) {
                mMediaController.getTransportControls().play();
            } else if (action.equals(ACTION_PAUSE)) {
                mMediaController.getTransportControls().pause();
            }
        }
    };

    private MediaController.Callback mPlaybackChangedListener = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (!mListeners.isEmpty()) {
                notifyActionsChanged(getMediaActions());
            }
        }
    };

    private ArrayList<ActionListener> mListeners = new ArrayList<>();

    public PipMediaController(Context context, IActivityManager activityManager) {
        mContext = context;
        mActivityManager = activityManager;
        IntentFilter mediaControlFilter = new IntentFilter();
        mediaControlFilter.addAction(ACTION_PLAY);
        mediaControlFilter.addAction(ACTION_PAUSE);
        mContext.registerReceiver(mPlayPauseActionReceiver, mediaControlFilter);

        createMediaActions();
        mMediaSessionManager =
                (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        mMediaSessionManager.addOnActiveSessionsChangedListener(controllers -> {
            resolveActiveMediaController(controllers);
        }, null);
    }

    /**
     * Handles when an activity is pinned.
     */
    public void onActivityPinned() {
        // Once we enter PiP, try to find the active media controller for the top most activity
        resolveActiveMediaController(mMediaSessionManager.getActiveSessions(null));
    }

    /**
     * Adds a new media action listener.
     */
    public void addListener(ActionListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
            listener.onMediaActionsChanged(getMediaActions());
        }
    }

    /**
     * Removes a media action listener.
     */
    public void removeListener(ActionListener listener) {
        listener.onMediaActionsChanged(Collections.EMPTY_LIST);
        mListeners.remove(listener);
    }

    /**
     * Gets the set of media actions currently available.
     */
    private List<RemoteAction> getMediaActions() {
        if (mMediaController == null || mMediaController.getPlaybackState() == null) {
            return Collections.EMPTY_LIST;
        }

        ArrayList<RemoteAction> mediaActions = new ArrayList<>();
        int state = mMediaController.getPlaybackState().getState();
        boolean isPlaying = MediaSession.isActiveState(state);
        long actions = mMediaController.getPlaybackState().getActions();
        if (!isPlaying && ((actions & PlaybackState.ACTION_PLAY) != 0)) {
            mediaActions.add(mPauseAction);
        } else if (isPlaying && ((actions & PlaybackState.ACTION_PAUSE) != 0)) {
            mediaActions.add(mPlayAction);
        }
        return mediaActions;
    }

    /**
     * Creates the standard media buttons that we may show.
     */
    private void createMediaActions() {
        String pauseDescription = mContext.getString(R.string.pip_pause);
        mPauseAction = new RemoteAction(Icon.createWithResource(mContext,
                R.drawable.ic_pause_white_24dp), pauseDescription, pauseDescription,
                        PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_PAUSE),
                                FLAG_UPDATE_CURRENT));

        String playDescription = mContext.getString(R.string.pip_play);
        mPlayAction = new RemoteAction(Icon.createWithResource(mContext,
                R.drawable.ic_play_arrow_white_24dp), playDescription, playDescription,
                        PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_PLAY),
                                FLAG_UPDATE_CURRENT));
    }

    /**
     * Tries to find and set the active media controller for the top PiP activity.
     */
    private void resolveActiveMediaController(List<MediaController> controllers) {
        if (controllers != null) {
            final ComponentName topActivity = PipUtils.getTopPinnedActivity(mContext,
                    mActivityManager);
            if (topActivity != null) {
                for (int i = 0; i < controllers.size(); i++) {
                    final MediaController controller = controllers.get(i);
                    if (controller.getPackageName().equals(topActivity.getPackageName())) {
                        setActiveMediaController(controller);
                        return;
                    }
                }
            }
        }
        setActiveMediaController(null);
    }

    /**
     * Sets the active media controller for the top PiP activity.
     */
    private void setActiveMediaController(MediaController controller) {
        if (controller != mMediaController) {
            if (mMediaController != null) {
                mMediaController.unregisterCallback(mPlaybackChangedListener);
            }
            mMediaController = controller;
            if (controller != null) {
                controller.registerCallback(mPlaybackChangedListener);
            }
            if (!mListeners.isEmpty()) {
                notifyActionsChanged(getMediaActions());
            }

            // TODO(winsonc): Consider if we want to close the PIP after a timeout (like on TV)
        }
    }

    /**
     * Notifies all listeners that the actions have changed.
     */
    private void notifyActionsChanged(List<RemoteAction> actions) {
        if (!mListeners.isEmpty()) {
            mListeners.forEach(l -> l.onMediaActionsChanged(actions));
        }
    }
}
