package com.dk.dkaiagent.desktop;

import javafx.application.Application;

/** Plain main avoids the Java launcher's special handling of Application subclasses. */
public final class DesktopLauncher {
    private DesktopLauncher() { }

    public static void main(String[] args) {
        Application.launch(PsychCounselorDesktop.class, args);
    }
}
