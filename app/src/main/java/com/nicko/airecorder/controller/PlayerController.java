package com.nicko.airecorder.controller;

import android.media.MediaPlayer;

import com.nicko.airecorder.utils.PlaybackManager;

public class PlayerController {

    private final PlaybackManager playbackManager;

    public PlayerController() {

        playbackManager = PlaybackManager.getInstance();

    }

    public void prepare(
            String filePath,
            MediaPlayer.OnPreparedListener preparedListener,
            MediaPlayer.OnCompletionListener completionListener,
            MediaPlayer.OnErrorListener errorListener
    ) throws Exception {

        playbackManager.prepare(
                filePath,
                preparedListener,
                completionListener,
                errorListener
        );
    }

    public void play() {

        playbackManager.start();

    }

    public void pause() {

        playbackManager.pause();

    }

    public void seekTo(int position) {

        playbackManager.seekTo(position);

    }

    public boolean isPlaying() {

        return playbackManager.isPlaying();

    }

    public int getCurrentPosition() {

        return playbackManager.getCurrentPosition();

    }

    public int getDuration() {

        return playbackManager.getDuration();

    }

    public MediaPlayer getPlayer() {

        return playbackManager.getPlayer();

    }

    public void release() {

        playbackManager.release();

    }

}