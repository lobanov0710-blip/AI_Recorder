package com.nicko.airecorder.utils;

import android.media.MediaPlayer;

import java.io.IOException;

public class PlaybackManager {

    private static PlaybackManager instance;

    private MediaPlayer mediaPlayer;

    private PlaybackManager() {
    }

    public static synchronized PlaybackManager getInstance() {

        if (instance == null) {
            instance = new PlaybackManager();
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

        mediaPlayer = new MediaPlayer();

        mediaPlayer.setDataSource(filePath);

        mediaPlayer.setOnPreparedListener(preparedListener);

        mediaPlayer.setOnCompletionListener(completionListener);

        mediaPlayer.setOnErrorListener(errorListener);

        mediaPlayer.prepareAsync();
    }

    public MediaPlayer getPlayer() {
        return mediaPlayer;
    }

    public void start() {

        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
    }

    public void pause() {

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    public void stop() {

        if (mediaPlayer != null) {

            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public void seekTo(int position) {

        if (mediaPlayer != null) {

            try {
                mediaPlayer.seekTo(position);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isPlaying() {

        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public int getCurrentPosition() {

        if (mediaPlayer == null) {
            return 0;
        }

        try {
            return mediaPlayer.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {

        if (mediaPlayer == null) {
            return 0;
        }

        try {
            return mediaPlayer.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    public void release() {

        if (mediaPlayer != null) {

            try {

                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }

            } catch (Exception ignored) {
            }

            try {
                mediaPlayer.reset();
            } catch (Exception ignored) {
            }

            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }
    }
}