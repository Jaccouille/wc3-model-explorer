package com.war3.viewer.app;

/**
 * Plain (non-Application) entry point so IntelliJ IDEA can run the app directly
 * without needing --module-path / --add-modules JVM arguments.
 * The Gradle 'run' task and any fat-jar manifest should also point here.
 */
public final class Launcher {
    public static void main(final String[] args) {
        MainApp.main(args);
    }
}
