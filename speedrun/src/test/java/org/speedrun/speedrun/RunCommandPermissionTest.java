package org.speedrun.speedrun;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandPermissionTest {
    @Test
    void rejectsUnauthorizedRemovalBeforeTouchingPluginState() {
        for (String[] arguments : List.of(
                new String[]{"remove", "village"},
                new String[]{"REMOVE", "nether", "portal"},
                new String[]{"remove"})) {
            List<String> permissionChecks = new ArrayList<>();
            List<String> messageKeys = new ArrayList<>();
            Player player = playerWithAdminPermission(false, permissionChecks);
            // A null plugin makes any manager access fail, including the original unguarded removal.
            RunCommand command = new RunCommand(null, (key, replacements) -> {
                messageKeys.add(key);
                return key;
            });

            assertTrue(command.onCommand(player, null, "run", arguments));

            assertEquals(List.of("speedrun.admin"), permissionChecks);
            assertEquals(List.of("commands.no-permission"), messageKeys);
        }
    }

    @Test
    void allowsAdministratorsToReachRemovalArgumentValidation() {
        List<String> permissionChecks = new ArrayList<>();
        List<String> messageKeys = new ArrayList<>();
        RunCommand command = new RunCommand(null, (key, replacements) -> {
            messageKeys.add(key);
            return key;
        });

        assertTrue(command.onCommand(playerWithAdminPermission(true, permissionChecks), null,
                "run", new String[]{"remove"}));

        assertEquals(List.of("speedrun.admin"), permissionChecks);
        assertEquals(List.of("commands.usage-remove"), messageKeys);
    }

    private static Player playerWithAdminPermission(boolean allowed, List<String> permissionChecks) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasPermission" -> {
                        permissionChecks.add((String) arguments[0]);
                        yield allowed;
                    }
                    case "sendMessage" -> null;
                    case "toString" -> "Permission test player";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("Unexpected player access: " + method.getName());
                });
    }
}
