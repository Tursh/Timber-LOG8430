/*
 * Copyright (C) 2012 Andrew Neal
 * Copyright (C) 2014 The CyanogenMod Project
 * Copyright (C) 2015 Naman Dwivedi
 *
 * Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.naman14.timber;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.widget.Toast;

import com.naman14.timber.dataloaders.SongLoader;
import com.naman14.timber.helpers.MusicPlaybackTrack;
import com.naman14.timber.utils.TimberUtils.IdType;
import com.naman14.timber.MusicInfo

import java.util.Arrays;
import java.util.WeakHashMap;

public class MusicController {

    private static final long[] sEmptyList;
    public static ITimberService mService = null;
    private static ContentValues[] mContentValuesCache = null;

    static {
        sEmptyList = new long[0];
    }

    public static void next() {
        try {
            if (mService != null) {
                mService.next();
            }
        } catch (final RemoteException ignored) {
        }
    }

    public static void asyncNext(final Context context) {
        final Intent previous = new Intent(context, MusicService.class);
        previous.setAction(MusicService.NEXT_ACTION);
        context.startService(previous);
    }

    public static void previous(final Context context, final boolean force) {
        final Intent previous = new Intent(context, MusicService.class);
        if (force) {
            previous.setAction(MusicService.PREVIOUS_FORCE_ACTION);
        } else {
            previous.setAction(MusicService.PREVIOUS_ACTION);
        }
        context.startService(previous);
    }

    public static void playOrPause() {
        try {
            if (mService != null) {
                if (mService.isPlaying()) {
                    mService.pause();
                } else {
                    mService.play();
                }
            }
        } catch (final Exception ignored) {
        }
    }

    public static void cycleRepeat() {
        try {
            if (mService != null) {
                switch (mService.getRepeatMode()) {
                    case MusicService.REPEAT_NONE:
                        mService.setRepeatMode(MusicService.REPEAT_ALL);
                        break;
                    case MusicService.REPEAT_ALL:
                        mService.setRepeatMode(MusicService.REPEAT_CURRENT);
                        if (mService.getShuffleMode() != MusicService.SHUFFLE_NONE) {
                            mService.setShuffleMode(MusicService.SHUFFLE_NONE);
                        }
                        break;
                    default:
                        mService.setRepeatMode(MusicService.REPEAT_NONE);
                        break;
                }
            }
        } catch (final RemoteException ignored) {
        }
    }

    public static void cycleShuffle() {
        try {
            if (mService != null) {
                switch (mService.getShuffleMode()) {
                    case MusicService.SHUFFLE_NONE:
                        mService.setShuffleMode(MusicService.SHUFFLE_NORMAL);
                        if (mService.getRepeatMode() == MusicService.REPEAT_CURRENT) {
                            mService.setRepeatMode(MusicService.REPEAT_ALL);
                        }
                        break;
                    case MusicService.SHUFFLE_NORMAL:
                        mService.setShuffleMode(MusicService.SHUFFLE_NONE);
                        break;
                    case MusicService.SHUFFLE_AUTO:
                        mService.setShuffleMode(MusicService.SHUFFLE_NONE);
                        break;
                    default:
                        break;
                }
            }
        } catch (final RemoteException ignored) {
        }
    }

    public static final boolean isPlaying() {
        if (mService != null) {
            try {
                return mService.isPlaying();
            } catch (final RemoteException ignored) {
            }
        }
        return false;
    }

    public static final int getShuffleMode() {
        if (mService != null) {
            try {
                return mService.getShuffleMode();
            } catch (final RemoteException ignored) {
            }
        }
        return 0;
    }

    public static void setShuffleMode(int mode) {
        try {
            if (mService != null) {
                mService.setShuffleMode(mode);
            }
        } catch (RemoteException ignored) {

        }
    }

    public static final int getRepeatMode() {
        if (mService != null) {
            try {
                return mService.getRepeatMode();
            } catch (final RemoteException ignored) {
            }
        }
        return 0;
    }

    public static void playArtist(final Context context, final long artistId, int position, boolean shuffle) {
        final long[] artistList = getSongListForArtist(context, artistId);
        if (artistList != null) {
            playAll(context, artistList, position, artistId, IdType.Artist, shuffle);
        }
    }

    public static void playAlbum(final Context context, final long albumId, int position, boolean shuffle) {
        final long[] albumList = getSongListForAlbum(context, albumId);
        if (albumList != null) {
            playAll(context, albumList, position, albumId, IdType.Album, shuffle);
        }
    }

    public static void playAll(final Context context, final long[] list, int position,
                               final long sourceId, final IdType sourceType,
                               final boolean forceShuffle) {
        if (list == null || list.length == 0 || mService == null) {
            return;
        }
        try {
            if (forceShuffle) {
                mService.setShuffleMode(MusicService.SHUFFLE_NORMAL);
            }
            final long currentId = mService.getAudioId();
            final int currentQueuePosition = getQueuePosition();
            if (position != -1 && currentQueuePosition == position && currentId == list[position]) {
                final long[] playlist = getQueue();
                if (Arrays.equals(list, playlist)) {
                    mService.play();
                    return;
                }
            }
            if (position < 0) {
                position = 0;
            }
            mService.open(list, forceShuffle ? -1 : position, sourceId, sourceType.mId);
            mService.play();
        } catch (final RemoteException ignored) {
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public static void playNext(Context context, final long[] list, final long sourceId, final IdType sourceType) {
        if (mService == null) {
            return;
        }
        try {
            mService.enqueue(list, MusicService.NEXT, sourceId, sourceType.mId);
            final String message = makeLabel(context, R.plurals.NNNtrackstoqueue, list.length);
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (final RemoteException ignored) {
        }
    }

    public static void shuffleAll(final Context context) {
        Cursor cursor = SongLoader.makeSongCursor(context, null, null);
        final long[] trackList = SongLoader.getSongListForCursor(cursor);
        if (trackList.length == 0 || mService == null) {
            return;
        }
        try {
            mService.setShuffleMode(MusicService.SHUFFLE_NORMAL);
            if (getQueuePosition() == 0 && mService.getAudioId() == trackList[0] && Arrays.equals(trackList, getQueue())) {
                    mService.play();
                    return;
            }
            mService.open(trackList, -1, -1, IdType.NA.mId);
            mService.play();
            cursor.close();
        } catch (final RemoteException ignored) {
        }
    }

}
