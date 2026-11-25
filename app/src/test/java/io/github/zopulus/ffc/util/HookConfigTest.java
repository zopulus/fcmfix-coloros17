package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class HookConfigTest {
    private JSONObject config(long revision, String... names) throws Exception {
        return new JSONObject().put("revision", revision).put("allowList", new JSONArray(names));
    }

    @Test public void closedManagerMergesBothSourcesAndHonorsRevocationsWithoutWriting() throws Exception {
        JSONObject local = config(100, "added.offline", "removed.remotely")
                .put("pendingRemoteBase", config(1, "removed.locally", "removed.remotely"));
        JSONObject remote = config(7, "added.remotely", "removed.locally")
                .put(ConfigSchema.KEY_INCLUDE_ICEBOX_DISABLED_APP, true);
        String originalLocal = local.toString(), originalRemote = remote.toString();
        HookConfig hook = new HookConfig();
        ConfigSnapshot first = hook.resolve(local, remote);
        assertEquals(Set.of("added.offline", "added.remotely"), first.allowList);
        assertTrue(first.permitsIceBox("added.remotely", remote.getLong("revision")));
        assertEquals(first.revision, hook.resolve(local, remote).revision);
        assertEquals(originalLocal, local.toString());
        assertEquals(originalRemote, remote.toString());
    }

    @Test public void unavailableOrOlderSourcePreservesTheMergedViewUntilNewInputArrives() throws Exception {
        JSONObject local = config(10, "local").put("pendingRemoteBase", config(1));
        JSONObject remote = config(4, "remote");
        HookConfig hook = new HookConfig();
        hook.resolve(local, remote);
        assertEquals(Set.of("local", "remote"), hook.resolve(local, null).allowList);
        assertEquals(Set.of("local", "remote"), hook.resolve(null, config(2, "stale")).allowList);
        assertEquals(Set.of("local"), hook.resolve(null, config(5)).allowList);
        assertThrows(IllegalStateException.class, () -> hook.resolve(null, null));
    }

    @Test public void legacyBootstrapAndSingleSourceStartupStillWork() throws Exception {
        JSONObject defaults = config(0).put("bootstrapDefaults", true);
        JSONObject remote = config(0, "legacy");
        assertEquals(Set.of("legacy"), new HookConfig().resolve(defaults, remote).allowList);
        assertEquals(Set.of("legacy"), new HookConfig().resolve(null, remote).allowList);
        assertEquals(Set.of("local"), new HookConfig().resolve(config(0, "local"), null).allowList);
    }
}
