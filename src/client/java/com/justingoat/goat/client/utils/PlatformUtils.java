package com.justingoat.goat.client.utils;

public class PlatformUtils {
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_MAC = OS_NAME.contains("mac");
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_LINUX = OS_NAME.contains("nix") || OS_NAME.contains("nux") || OS_NAME.contains("aix");

    public static boolean isMac() { return IS_MAC; }
    public static boolean isWindows() { return IS_WINDOWS; }
    public static boolean isLinux() { return IS_LINUX; }
}
