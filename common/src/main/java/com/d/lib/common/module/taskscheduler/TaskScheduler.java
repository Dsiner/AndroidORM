package com.d.lib.common.module.taskscheduler;

import com.d.lib.common.module.taskscheduler.callback.Function;
import com.d.lib.common.module.taskscheduler.callback.Observer;
import com.d.lib.common.module.taskscheduler.callback.Task;
import com.d.lib.common.module.taskscheduler.schedule.FunctionEmitter;
import com.d.lib.common.module.taskscheduler.schedule.Schedulers;
import com.d.lib.common.module.taskscheduler.schedule.TaskEmitter;

import java.util.ArrayList;
import java.util.List;

public class TaskScheduler<T> {
    private Task task;
    private Schedulers subscribeScheduler = Schedulers.DEFAULT_THREAD;

    private TaskScheduler() {
    }

    public static <T> TaskScheduler<T> create(final Task<T> task) {
        TaskScheduler<T> schedulers = new TaskScheduler<T>();
        schedulers.task = task;
        return schedulers;
    }

    public TaskObserve<T> subscribeOn(Schedulers scheduler) {
        this.subscribeScheduler = scheduler;
        return new TaskObserve<T>(new TaskEmitter<T>(task, subscribeScheduler));
    }

    public static class TaskObserve<T> {
        private TaskEmitter<T> taskEmitter;
        private List<FunctionEmitter> emitters;
        private Schedulers observeOnScheduler = Schedulers.DEFAULT_THREAD;

        private TaskObserve() {
        }

        TaskObserve(TaskEmitter<T> taskEmitter) {
            this.taskEmitter = taskEmitter;
            this.emitters = new ArrayList<>();
        }

        TaskObserve(TaskObserve middle) {
            this.taskEmitter = middle.taskEmitter;
            this.observeOnScheduler = middle.observeOnScheduler;
            this.emitters = middle.emitters;
        }

        public TaskObserve<T> observeOn(Schedulers scheduler) {
            this.observeOnScheduler = scheduler;
            return this;
        }

        public <R> TaskObserve<R> map(Function<? super T, ? extends R> f) {
            this.emitters.add(new FunctionEmitter<T, R>(f, observeOnScheduler));
            return new TaskObserve<R>(this);
        }

        public void subscribe() {
            subscribe(null);
        }

        public void subscribe(final Observer<T> callback) {
            Schedulers.switchThread(taskEmitter.scheduler, new Runnable() {
                @Override
                public void run() {
                    try {
                        T t = taskEmitter.task.run();
                        if (emitters.size() > 0) {
                            apply(t, emitters, callback);
                            return;
                        }
                        submit(observeOnScheduler, t, callback);
                    } catch (Throwable e) {
                        if (callback != null) {
                            callback.onError(e);
                        }
                    }
                }
            });
        }

        private static <T> void submit(final Schedulers scheduler, final T result, final Observer<T> callback) {
            Schedulers.switchThread(scheduler, new Runnable() {
                @Override
                public void run() {
                    try {
                        if (callback != null) {
                            callback.onNext(result);
                        }
                    } catch (Throwable e) {
                        if (callback != null) {
                            callback.onError(e);
                        }
                    }
                }
            });
        }

        private <E, F> void apply(final E o, final List<FunctionEmitter> emitters, final Observer<F> callback) {
            final FunctionEmitter<E, F> f = emitters.get(0);
            emitters.remove(f);
            Schedulers.switchThread(f.scheduler, new Runnable() {
                @Override
                public void run() {
                    try {
                        F emitter = f.function.apply(o);
                        if (assertInterrupt(emitter)) {
                            submit(observeOnScheduler, emitter, callback);
                            return;
                        }
                        apply(emitter, emitters, callback);
                    } catch (Throwable e) {
                        if (callback != null) {
                            callback.onError(e);
                        }
                    }
                }

                private boolean assertInterrupt(F emitter) throws Exception {
                    if (emitter == null) {
                        throw new RuntimeException("apply output must not be null!");
                    }
                    return emitters.size() <= 0;
                }
            });
        }
    }
}
