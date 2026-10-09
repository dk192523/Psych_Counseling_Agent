package com.dk.psychclient;

import javafx.application.Application;

/** Separate executable entry: its Spring lifecycle is independent of the desktop client's process. */
public final class LocalServerLauncher {
    private LocalServerLauncher() { }

    public static void main(String[] args) {
        Application.launch(LocalServerManager.class, args);
    }
}
