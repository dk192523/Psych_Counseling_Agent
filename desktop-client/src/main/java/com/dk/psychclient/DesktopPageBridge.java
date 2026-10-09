package com.dk.psychclient;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Only user-facing browser and Markdown save actions are exposed to the trusted page. */
public final class DesktopPageBridge {
    private static final int MAX_EXPORT_BYTES = 10 * 1024 * 1024;
    private final BooleanSupplier trustedPage;
    private final Consumer<URI> openBrowser;
    private final Function<String, Path> chooseSavePath;
    private boolean saving;

    DesktopPageBridge(BooleanSupplier trustedPage, Consumer<URI> openBrowser,
                      Function<String, Path> chooseSavePath) {
        this.trustedPage = trustedPage;
        this.openBrowser = openBrowser;
        this.chooseSavePath = chooseSavePath;
    }

    public boolean openExternal(String address) {
        if (!trustedPage.getAsBoolean()) return false;
        try {
            openBrowser.accept(externalUri(address));
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static URI externalUri(String address) {
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || address.indexOf('\\') >= 0
                    || address.chars().anyMatch(ch -> Character.isISOControl(ch))) {
                throw new IllegalArgumentException("Only absolute HTTP/HTTPS links without credentials are allowed");
            }
            return uri;
        } catch (URISyntaxException | NullPointerException failure) {
            throw new IllegalArgumentException("Invalid external link", failure);
        }
    }

    /** No paths are accepted from JavaScript: the destination comes only from the system dialog. */
    public String saveMarkdown(String suggestedName, String markdown) {
        if (!trustedPage.getAsBoolean() || saving || markdown == null || markdown.length() > MAX_EXPORT_BYTES) {
            return "failed";
        }
        byte[] content = markdown.getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_EXPORT_BYTES) return "failed";
        saving = true;
        try {
            Path destination = chooseSavePath.apply(safeFilename(suggestedName));
            if (destination == null) return "cancelled";
            // A native dialog runs a nested event loop: the page may have been retired meanwhile.
            if (!trustedPage.getAsBoolean()) return "failed";
            writeMarkdown(destination, content);
            return "saved";
        } catch (IOException | RuntimeException failure) {
            return "failed";
        } finally {
            saving = false;
        }
    }

    static String safeFilename(String input) {
        String name = input == null ? "会话" : input.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        name = name.replaceFirst("(?i)\\.md$", "").replaceFirst("[ .]+$", "");
        if (name.isBlank()) name = "会话";
        if (name.length() > 120) name = name.substring(0, 120);
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) name = "_" + name;
        return name + ".md";
    }

    private static void writeMarkdown(Path destination, byte[] content) throws IOException {
        Path target = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".counseling-export-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
