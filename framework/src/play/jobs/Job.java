package play.jobs;

import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import play.Invoker;
import play.Invoker.InvocationContext;
import play.Logger;
import play.Play;
import play.exceptions.JavaExecutionException;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.libs.F.Promise;
import play.libs.Time;
import play.mvc.Http;

import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;

/**
 * A job is an asynchronously executed unit of work
 * @param <V> The job result type (if any)
 */
public class Job<V> extends Invoker.Invocation implements Callable<V> {

    public static final String invocationType = "Job";

    protected ExecutorService executor;
    protected long lastRun = 0;
    protected boolean wasError = false;
    protected Throwable lastException = null;

    Date nextPlannedExecution = null;

    @Override
    public InvocationContext getInvocationContext() {
        return new InvocationContext(invocationType, this.getClass().getAnnotations());
    }

    /**
     * Here you do the job
     */
    public void doJob() throws Exception {
    }

    /**
     * Here you do the job and return a result
     */
    public V doJobWithResult() throws Exception {
        doJob();
        return null;
    }

    @Override
    public void execute() throws Exception {
    }

    /**
     * Start this job now (well ASAP)
     * @return the job completion
     */
    public Promise<V> now() {
        final Promise<V> smartFuture = new Promise<V>();
    JobsPlugin.executor.submit(getJobCallingCallable(smartFuture));
        return smartFuture;
    }

  /**
   * If is called in a 'HttpRequest' invocation context, waits until request
   * is served and schedules job then.
   *
   * Otherwise is the same as now();
   *
   * If you want to schedule a job to run after some other job completes, wait till a promise redeems
   * of just override first Job's call() to schedule the second one.
   *
   * @return the job completion
   */
  public Promise<V> afterRequest() {
    InvocationContext current = Invoker.InvocationContext.current();
    if(current == null || !Http.invocationType.equals(current.getInvocationType())) {
      return now();
    }

    final Promise<V> smartFuture = new Promise<V>();
    Callable<V> callable = getJobCallingCallable(smartFuture);
    JobsPlugin.addAfterRequestAction(callable);
    return smartFuture;
  }

    /**
     * Start this job in several seconds
     * @return the job completion
     */
    public Promise<V> in(String delay) {
        return in(Time.parseDuration(delay));
    }

    /**
     * Start this job in several seconds
     * @return the job completion
     */
    public Promise<V> in(int seconds) {
        final Promise<V> smartFuture = new Promise<V>();
        JobsPlugin.executor.schedule(getJobCallingCallable(smartFuture), seconds, TimeUnit.SECONDS);
        return smartFuture;
    }

    private Callable<V> getJobCallingCallable(final Promise<V> smartFuture) {
      return new Callable<V>() {
        public V call() throws Exception {
          try {
            V result = Job.this.call();
            if (smartFuture != null) {
              smartFuture.invoke(result);
            }
            return result;
          }
          catch (Exception e) {
            if (smartFuture != null) {
              smartFuture.invokeWithException(e);
              }
            return null;
          }
        }
      };
    }

    /**
     * Run this job every n seconds
     */
    public void every(String delay) {
        every(Time.parseDuration(delay));
    }

    /**
     * Run this job every n seconds
     */
    public void every(int seconds) {
        JobsPlugin.executor.scheduleWithFixedDelay(this, seconds, seconds, TimeUnit.SECONDS);
        JobsPlugin.scheduledJobs.add(this);
    }

    // Customize Invocation
    @Override
    public void onException(Throwable e) {
        wasError = true;
        lastException = e;
        try {
            super.onException(e);
        } catch(Throwable ex) {
            Logger.error(ex, "Error during job execution (%s)", this);
            throw new UnexpectedException(unwrap(e));
        }
    }

    private Throwable unwrap(Throwable e) {
      while((e instanceof UnexpectedException || e instanceof PlayException) && e.getCause() != null) {
        e = e.getCause();
      }
      return e;
    }

    @Override
    public void run() {
        call();
    }

    public V call() {
        Monitor monitor = null;
        try {
            if (init()) {
                before();
                V result = null;

                try {
                    lastException = null;
                    lastRun = System.currentTimeMillis();
                    monitor = MonitorFactory.start(this + ".doJob()");
                    result = doJobWithResult();
                    monitor.stop();
                    monitor = null;
                    wasError = false;
                } catch (PlayException e) {
                    throw e;
                } catch (Exception e) {
                    StackTraceElement element = PlayException.getInterestingStackTraceElement(e);
                    if (element != null) {
                        throw new JavaExecutionException(Play.classes.getApplicationClass(element.getClassName()), element.getLineNumber(), e);
                    }
                    throw e;
                }
                after();
                return result;
            }
        } catch (Throwable e) {
            onException(e);
        } finally {
            if(monitor != null) {
                monitor.stop();
            }
            _finally();
        }
        return null;
    }

    @Override
    public void _finally() {
        super._finally();
        if (executor == JobsPlugin.executor) {
            JobsPlugin.scheduleForCRON(this);
        }
    }

    @Override
    public String toString() {
        return this.getClass().getName();
    }


}
