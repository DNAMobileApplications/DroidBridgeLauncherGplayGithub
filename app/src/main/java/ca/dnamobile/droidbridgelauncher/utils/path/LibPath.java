/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.utils.path;

import java.io.File;

public final class LibPath {
    private LibPath() {
    }

    private static File COMPONENTS_DIR;
    private static File SUPPORT_COMPONENTS_DIR;
    private static File OTHER_LOGIN_DIR;

    public static File CACIO_8;
    public static File CACIO_17;
    public static File CACIO_17_AGENT;

    public static File FORGE_INSTALLER;
    public static File DROIDBRIDGE_FABRIC_AGENT;

    public static File DROIDBRIDGE_LIB_PATCHER;
    public static File OPTIFINE_RENAMER;

    public static File AUTHLIB_INJECTOR;
    public static File NIDE_8_AUTH;

    public static File JAVA_SANDBOX_POLICY;
    public static File LOG4J_XML_1_7;
    public static File LOG4J_XML_1_12;
    public static File PRO_GRADE;

    public static void refresh() {
        COMPONENTS_DIR = new File(safeDataDir(), "components");

        /*
         * Keep launcher support jars in app-private storage, not in the user-selected
         * Minecraft install location. The selected storage path can be removable or
         * SAF/scoped, and a failed support-component unpack should never block the
         * launcher from starting.
         */
        SUPPORT_COMPONENTS_DIR = safeFilesDir();
        OTHER_LOGIN_DIR = new File(SUPPORT_COMPONENTS_DIR, "other_login");

        CACIO_8 = new File(SUPPORT_COMPONENTS_DIR, "caciocavallo");
        CACIO_17 = new File(SUPPORT_COMPONENTS_DIR, "caciocavallo17");
        CACIO_17_AGENT = new File(CACIO_17, "cacio-agent.jar");

        FORGE_INSTALLER = new File(COMPONENTS_DIR, "forge_installer.jar");
        DROIDBRIDGE_FABRIC_AGENT = new File(COMPONENTS_DIR, "DroidBridgeFabricAgent.jar");

        DROIDBRIDGE_LIB_PATCHER = new File(COMPONENTS_DIR, "DroidBridgeLibPatcher.jar");
        OPTIFINE_RENAMER = new File(COMPONENTS_DIR, "OptiFineRenamer.jar");

        AUTHLIB_INJECTOR = new File(OTHER_LOGIN_DIR, "authlib-injector.jar");
        NIDE_8_AUTH = new File(OTHER_LOGIN_DIR, "nide8auth.jar");

        JAVA_SANDBOX_POLICY = new File(COMPONENTS_DIR, "java_sandbox.policy");
        LOG4J_XML_1_7 = new File(COMPONENTS_DIR, "log4j-rce-patch-1.7.xml");
        LOG4J_XML_1_12 = new File(COMPONENTS_DIR, "log4j-rce-patch-1.12.xml");
        PRO_GRADE = new File(COMPONENTS_DIR, "pro-grade.jar");
    }

    private static File safeFilesDir() {
        if (PathManager.DIR_FILE != null) return PathManager.DIR_FILE;
        if (PathManager.DIR_DATA != null && !PathManager.DIR_DATA.trim().isEmpty()) {
            return new File(PathManager.DIR_DATA, "files");
        }
        return new File("files");
    }

    private static String safeDataDir() {
        return PathManager.DIR_DATA != null && !PathManager.DIR_DATA.trim().isEmpty()
                ? PathManager.DIR_DATA
                : ".";
    }

    static {
        refresh();
    }
}
