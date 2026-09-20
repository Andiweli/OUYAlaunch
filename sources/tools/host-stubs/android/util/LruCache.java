package android.util;
import java.util.LinkedHashMap;
import java.util.Map;
/** Minimal access-ordered host LRU for exercising the real IconLoader's byte budgeting. */
public class LruCache<K, V> {
    private final int maximum;
    private int size;
    private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, .75f, true);
    public LruCache(int maximum) { this.maximum = maximum; }
    protected int sizeOf(K key, V value) { return 1; }
    public synchronized V get(K key) { return values.get(key); }
    public synchronized V put(K key, V value) {
        V old = values.put(key, value);
        if (old != null) size -= sizeOf(key, old);
        size += sizeOf(key, value);
        while (size > maximum && !values.isEmpty()) remove(values.keySet().iterator().next());
        return old;
    }
    public synchronized V remove(K key) {
        V old = values.remove(key);
        if (old != null) size -= sizeOf(key, old);
        return old;
    }
    public synchronized void evictAll() { values.clear(); size = 0; }
    public synchronized int size() { return size; }
    public synchronized Map<K, V> snapshot() { return new LinkedHashMap<>(values); }
}
