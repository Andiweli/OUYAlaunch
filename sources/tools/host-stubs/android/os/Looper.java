package android.os;
/** HOST TEST DOUBLE, not Android. Never include tools/host-stubs in app source sets. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
}
