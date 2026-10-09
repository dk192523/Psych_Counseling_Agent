package com.dk.psychclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DesktopPageBridgeTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"http://example.org/article?q=1#source", "https://example.org/article", "HTTPS://example.org/article"})
    void opensOnlyValidatedHttpLinks(String address) {
        AtomicReference<URI> opened = new AtomicReference<>();
        DesktopPageBridge bridge = new DesktopPageBridge(() -> true, opened::set, name -> null);
        assertTrue(bridge.openExternal(address));
        assertEquals(URI.create(address), opened.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "//example.org/source", "/source", "file:///C:/secret.txt", "javascript:alert(1)",
            "data:text/html,hello", "mailto:test@example.org", "ftp://example.org", "https://user:pass@example.org",
            "https://example.org:0", "https://example.org:65536", "https://example.org:abc", "https://example.org\\evil",
            "https://example.org/\n", "https://"})
    void refusesUnsupportedOrMalformedLinksWithoutInvokingTheBrowser(String address) {
        DesktopPageBridge bridge = new DesktopPageBridge(() -> true,
                uri -> fail("Invalid link reached the browser"), name -> null);
        assertFalse(bridge.openExternal(address));
    }

    @Test
    void staleOrForeignPageCannotOpenBrowserOrChooseFiles() {
        DesktopPageBridge bridge = new DesktopPageBridge(() -> false,
                uri -> fail("Untrusted page opened browser"), name -> fail("Untrusted page opened save dialog"));
        assertFalse(bridge.openExternal("https://example.org"));
        assertEquals("failed", bridge.saveMarkdown("会话.md", "私人内容"));
    }

    @Test
    void cancellationDoesNotCreateOrReplaceFiles() throws Exception {
        Path existing = temporary.resolve("会话.md");
        Files.writeString(existing, "原始会话", StandardCharsets.UTF_8);
        DesktopPageBridge bridge = new DesktopPageBridge(() -> true, uri -> {}, name -> null);
        assertEquals("cancelled", bridge.saveMarkdown("会话.md", "替换内容"));
        assertEquals("原始会话", Files.readString(existing, StandardCharsets.UTF_8));
    }

    @Test
    void savesMarkdownInUtf8AtOnlyTheDialogSelectedDestination() throws Exception {
        Path chosen = temporary.resolve("用户选择.md");
        AtomicReference<String> suggestion = new AtomicReference<>();
        DesktopPageBridge bridge = new DesktopPageBridge(() -> true, uri -> {}, name -> {
            suggestion.set(name);
            return chosen;
        });
        assertEquals("saved", bridge.saveMarkdown("../非法:标题?.md", "# 中文会话\n\n私人内容 📝\n"));
        assertEquals("# 中文会话\n\n私人内容 📝\n", Files.readString(chosen, StandardCharsets.UTF_8));
        assertFalse(suggestion.get().contains("/"));
        assertFalse(suggestion.get().contains(":"));
        assertTrue(suggestion.get().endsWith(".md"));
    }

    @Test
    void originIsCheckedAgainAfterTheNativeDialogReturns() {
        AtomicBoolean trusted = new AtomicBoolean(true);
        Path chosen = temporary.resolve("not-written.md");
        DesktopPageBridge bridge = new DesktopPageBridge(trusted::get, uri -> {}, name -> {
            trusted.set(false);
            return chosen;
        });
        assertEquals("failed", bridge.saveMarkdown("会话.md", "私人内容"));
        assertFalse(Files.exists(chosen));
    }

    @Test
    void saveFailureReturnsControlledResultAndCanBeRetried() throws Exception {
        AtomicReference<Path> chosen = new AtomicReference<>(temporary.resolve("missing/会话.md"));
        DesktopPageBridge bridge = new DesktopPageBridge(() -> true, uri -> {}, name -> chosen.get());
        assertEquals("failed", bridge.saveMarkdown("会话.md", "内容"));
        chosen.set(temporary.resolve("会话.md"));
        assertEquals("saved", bridge.saveMarkdown("会话.md", "内容"));
        assertEquals("内容", Files.readString(chosen.get(), StandardCharsets.UTF_8));
    }

    @Test
    void oversizedUtf8ExportIsRejectedBeforeShowingTheSaveDialog() {
        DesktopPageBridge bridge = new DesktopPageBridge(() -> true, uri -> {},
                name -> fail("Oversized export opened save dialog"));
        assertEquals("failed", bridge.saveMarkdown("会话.md", "中".repeat(4 * 1024 * 1024)));
    }

    @Test
    void nativeBrowserFailureDoesNotEscapeIntoJavaScript() {
        DesktopPageBridge bridge = new DesktopPageBridge(() -> true,
                uri -> { throw new UnsupportedOperationException("No browser"); }, name -> null);
        assertFalse(bridge.openExternal("https://example.org"));
    }

    @Test
    void defaultFilenameAvoidsWindowsReservedNames() {
        assertEquals("_CON.md", DesktopPageBridge.safeFilename("CON.md"));
        assertEquals("会话.md", DesktopPageBridge.safeFilename("..."));
        assertEquals("会话.md", DesktopPageBridge.safeFilename(null));
    }
}
