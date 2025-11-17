package com.example.androidbuttons;

import android.app.Application;

import com.example.androidbuttons.core.AppGraph;
import com.example.androidbuttons.core.CrashLogger;
import com.example.androidbuttons.core.ConsoleLogRepository;

/**
 * Application-level entry point that initializes the dependency graph exactly once.
 */
public class AndroidButtonsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppGraph.initialize(this);
        ConsoleLogRepository consoleLogRepository = AppGraph.get().consoleLog();
        CrashLogger.install(consoleLogRepository);
    }
}
