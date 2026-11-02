package cn.pocketbanker.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SmsUploadJob extends JobService {
  private AtomicBoolean cancelled;

  @Override
  public boolean onStartJob(JobParameters params) {
    AtomicBoolean stopped = new AtomicBoolean();
    cancelled = stopped;
    new Thread(
            () -> {
              boolean retry = new SmsCollector(this).flush(stopped::get);
              if (!stopped.get()) jobFinished(params, retry);
            },
            "sms-upload")
        .start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    if (cancelled != null) cancelled.set(true);
    return true;
  }
}
