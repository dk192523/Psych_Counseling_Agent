package com.dk.psychclient;

import javafx.application.Application;

/** Plain launcher avoids the Java launcher treating the main class as a JavaFX module. */
public final class DesktopLauncher {
    private DesktopLauncher() { }

    public static void main(String[] args) {
        Application.launch(PsychCounselorClient.class, args);
    }
}
