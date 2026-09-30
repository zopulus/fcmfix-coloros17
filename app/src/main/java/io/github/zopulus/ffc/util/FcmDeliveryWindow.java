package io.github.zopulus.ffc.util;

import java.util.concurrent.ConcurrentHashMap;

public final class FcmDeliveryWindow {
    private final ConcurrentHashMap<Integer, Long> deadlines = new ConcurrentHashMap<>();

    public void begin(int uid, long now) {
        if (uid >= 0) deadlines.merge(uid, now + 20_000L, Math::max);
    }

    public boolean contains(int uid, long now) {
        // Expiry and renewal for one UID are serialized; never delete a renewed window.
        return deadlines.computeIfPresent(uid, (key, deadline) -> now < deadline ? deadline : null) != null;
    }
}
