package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
/** Host test double: explicit draining models delivery on a single UI test thread. */
public final class Handler {
    private static final ConcurrentLinkedQueue<Task> TASKS = new ConcurrentLinkedQueue<>();
    private static final class Task {
        final Handler owner; final Runnable runnable;
        Task(Handler owner, Runnable runnable) { this.owner = owner; this.runnable = runnable; }
    }
    public Handler(Looper looper) {}
    public boolean post(Runnable runnable) { TASKS.add(new Task(this, runnable)); return true; }
    public void removeCallbacksAndMessages(Object token) { TASKS.removeIf(task -> task.owner == this); }
    public static void drainMainQueue() {
        Task task;
        while ((task = TASKS.poll()) != null) task.runnable.run();
    }
}
