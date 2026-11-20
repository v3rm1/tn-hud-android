package com.tokyonight.hud.live;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** periodic (15 min) sky-alert check; work happens off the main thread */
public class AlertJob extends JobService {
    @Override public boolean onStartJob(final JobParameters p) {
        new Thread(new Runnable() { public void run() {
            try { Alerts.evaluate(getApplicationContext()); } catch (Throwable ignored) { }
            jobFinished(p, false);
        } }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }
}
