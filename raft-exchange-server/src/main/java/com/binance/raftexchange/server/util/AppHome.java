package com.binance.raftexchange.server.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

import org.apache.commons.lang.StringUtils;

public final class AppHome {

    private AppHome() {}

    public static Path appHome() {
        String cached = System.getProperty("app.home");
        return cached != null ? Path.of(cached) : get();
    }

    public static Path get() {
        String cp = System.getProperty("java.class.path", "");
        if (!cp.contains(File.pathSeparator)) {
            Path jar = Paths.get(cp).toAbsolutePath();
            if (Files.isRegularFile(jar) && jar.getParent() != null) {
                return jar.getParent();
            }
        }
        try {
            CodeSource src = AppHome.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path path = Paths.get(src.getLocation().toURI()).toAbsolutePath();
                if (path.getParent() != null) {
                    return path.getParent();
                }
            }
        } catch (Exception ignored) {
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    public static boolean isMacOs() {
        String OS = System.getProperty("os.name").toLowerCase();
        return StringUtils.containsIgnoreCase(OS, "mac os");
    }

}
