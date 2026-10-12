package com.fongmi.android.tv.following;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.Assert.*;

public class FollowingWorkStartupSourceTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String TOOLS = "http://schemas.android.com/tools";

    @Test
    public void onlyWorkManagerDefaultInitializerIsReplaced() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList providers = factory.newDocumentBuilder().parse(
                root().resolve("app/src/main/AndroidManifest.xml").toFile()).getElementsByTagName("provider");
        Element startup = null;
        for (int i = 0; i < providers.getLength(); i++) {
            Element provider = (Element) providers.item(i);
            if ("androidx.startup.InitializationProvider".equals(provider.getAttributeNS(ANDROID, "name"))) startup = provider;
        }
        assertNotNull(startup);
        assertEquals("merge", startup.getAttributeNS(TOOLS, "node"));
        boolean removed = false, replacement = false, appStartup = false;
        NodeList metadata = startup.getElementsByTagName("meta-data");
        for (int i = 0; i < metadata.getLength(); i++) {
            Element item = (Element) metadata.item(i);
            String name = item.getAttributeNS(ANDROID, "name");
            if ("androidx.work.WorkManagerInitializer".equals(name)) removed = "remove".equals(item.getAttributeNS(TOOLS, "node"));
            if ("com.fongmi.android.tv.following.FollowingWorkInitializer".equals(name)) replacement = "androidx.startup".equals(item.getAttributeNS(ANDROID, "value"));
            if ("com.fongmi.android.tv.Startup".equals(name)) appStartup = true;
        }
        assertTrue("Default WorkManager initializer must not crash the process on DE-storage TV firmware", removed);
        assertTrue("Keep automatic recovery on process/service restart via a safe initializer", replacement);
        assertTrue("Do not remove unrelated app initialization", appStartup);
    }

    @Test
    public void workManagerUsesCredentialStorageWithoutChangingAppStorage() throws Exception {
        String source = Files.readString(root().resolve("app/src/main/java/com/fongmi/android/tv/following/FollowingWorkManager.java"));
        assertTrue(source.contains("CONTEXT_CREDENTIAL_PROTECTED_STORAGE = 0x00000010"));
        assertTrue(source.contains("createPackageContext("));
        assertTrue(source.contains("getApplicationContext()"));
        assertTrue(source.contains("WorkManager.initialize(workContext"));
        assertTrue(source.contains("isUserUnlocked"));
    }
    @Test
    public void allFollowingSchedulingAndCancellationUsesTheStorageGate() throws Exception {
        String source = Files.readString(root().resolve("app/src/main/java/com/fongmi/android/tv/following/FollowingScheduler.java"));
        assertFalse("A direct getter can reintroduce the crash after removing the default initializer",
                source.contains("WorkManager.getInstance("));
        assertEquals(5, source.split("FollowingWorkManager.withManager\\(", -1).length - 1);
    }

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("app/src/main/AndroidManifest.xml"))) path = path.getParent();
        if (path == null) throw new AssertionError("repository root not found");
        return path;
    }
}
