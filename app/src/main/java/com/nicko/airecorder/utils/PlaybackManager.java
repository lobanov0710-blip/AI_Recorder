package com.nicko.airecorder.utils;

import android.media.MediaPlayer;

import java.io.IOException;

public class PlaybackManager {

    private static PlaybackManager instance;

    private MediaPlayer mediaPlayer;

    private boolean prepared = false;

    private PlaybackManager() {
    }

    public static synchronized PlaybackManager getInstance() {

        if (instance == null) {

            instance =
                    new PlaybackManager();

        }

        return instance;

    }

    public void prepare(
            String filePath,
            MediaPlayer.OnPreparedListener preparedListener,
            MediaPlayer.OnCompletionListener completionListener,
            MediaPlayer.OnErrorListener errorListener
    ) throws IOException {

        release();

        prepared = false;

        mediaPlayer =
                new MediaPlayer();

        mediaPlayer.setDataSource(
                filePath
        );

        mediaPlayer.setOnPreparedListener(mp -> {

            prepared = true;

            if (preparedListener != null) {

                preparedListener.onPrepared(mp);

            }

        });

        mediaPlayer.setOnCompletionListener(mp -> {

            if (completionListener != null) {

                completionListener.onCompletion(mp);

            }

        });

        mediaPlayer.setOnErrorListener(
                (mp, what, extra) -> {

                    prepared = false;

                    if (errorListener != null) {

                        return errorListener.onError(
                                mp,
                                what,
                                extra
                        );

                    }

                    return true;

                }
        );

        mediaPlayer.prepareAsync();

    }

    public MediaPlayer getPlayer() {

        return mediaPlayer;

    }

    public void start() {

        if (!prepared
                || mediaPlayer == null) {

            return;

        }

        try {

            if (!mediaPlayer.isPlaying()) {

                mediaPlayer.start();

            }

        } catch (IllegalStateException ignored) {

        }

    }

    public void pause() {

        if (!prepared
                || mediaPlayer == null) {

            return;

        }

        try {

            if (mediaPlayer.isPlaying()) {

                mediaPlayer.pause();

            }

        } catch (IllegalStateException ignored) {

        }

    }

    public void stop() {

        if (!prepared
                || mediaPlayer == null) {

            return;

        }

        try {

            mediaPlayer.stop();

            prepared = false;

        } catch (IllegalStateException ignored) {

            prepared = false;

        }

    }

    public void seekTo(int position) {

        if (!prepared
                || mediaPlayer == null) {

            return;

        }

        try {

            mediaPlayer.seekTo(position);

        } catch (IllegalStateException ignored) {

        }

    }

    public boolean isPlaying() {

        if (!prepared
                || mediaPlayer == null) {

            return false;

        }

        try {

            return mediaPlayer.isPlaying();

        } catch (IllegalStateException ignored) {

            return false;

        }

    }

    public int getCurrentPosition() {

        if (!prepared
                || mediaPlayer == null) {

            return 0;

        }

        try {

            return mediaPlayer.getCurrentPosition();

        } catch (IllegalStateException ignored) {

            return 0;

        }

    }

    public int getDuration() {

        if (!prepared
                || mediaPlayer == null) {

            return 0;

        }

        try {

            return mediaPlayer.getDuration();

        } catch (IllegalStateException ignored) {

            return 0;

        }

    }

    public void release() {

        prepared = false;

        if (mediaPlayer == null) {
            return;
        }

        try {

            mediaPlayer.release();

        } catch (Exception ignored) {

        }

        mediaPlayer = null;

    }

}