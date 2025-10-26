package me.abdelaziz;

import io.undertow.Undertow;
import io.undertow.util.Headers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class Main {

    private static final Path CONFIG_PATH = Paths.get("config.properties");

    private static final AtomicReference<Path> WHITELIST_PATH = new AtomicReference<>(Paths.get("whitelist.txt"));
    private static final AtomicReference<Path> BLOCKED_FILES_PATH = new AtomicReference<>(Paths.get("blocked_files.txt"));
    private static final AtomicReference<DateTimeFormatter> FORMATTER = new AtomicReference<>(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private static final AtomicReference<Long> CACHE_REFRESH_MS = new AtomicReference<>(30_000L);
    private static final AtomicReference<Long> MAX_FILE_SIZE = new AtomicReference<>(100 * 1024 * 1024L);

    private static final Set<String> WHITELIST = ConcurrentHashMap.newKeySet();
    private static final Set<String> BLOCKED_FILES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean WHITELIST_DIRTY = new AtomicBoolean(false);
    private static final Map<String, CachedFile> FILE_CACHE = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        final Thread t = new Thread(r, "Scheduler");
        t.setDaemon(true);
        return t;
    });

    static {
        try {
            loadConfig();
            loadWhitelist();
            loadBlockedFiles();
        } catch (final IOException e) {
            throw new ExceptionInInitializerError(e);
        }

        SCHEDULER.scheduleAtFixedRate(() -> {
            if (WHITELIST_DIRTY.compareAndSet(true, false)) saveWhitelist();
        }, 5, 5, TimeUnit.SECONDS);

        SCHEDULER.scheduleAtFixedRate(() -> {
            final long now = System.currentTimeMillis();
            final long cacheRefresh = CACHE_REFRESH_MS.get();
            FILE_CACHE.entrySet().removeIf(entry -> {
                final CachedFile cf = entry.getValue();
                if (now - cf.lastAccess > cacheRefresh) {
                    try {
                        final long currentModified = Files.getLastModifiedTime(Paths.get(entry.getKey())).toMillis();
                        if (currentModified != cf.lastModified) {
                            return true;
                        }
                    } catch (final IOException e) {
                        return true;
                    }
                }
                return false;
            });
        }, 10, 10, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (WHITELIST_DIRTY.get()) saveWhitelist();
            SCHEDULER.shutdown();
        }, "ShutdownHook"));
    }

    public static void main(final String[] args) {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (final NumberFormatException ignored) {
            }
        }

        final int cpus = Runtime.getRuntime().availableProcessors();
        final Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setIoThreads(Math.max(2, cpus))
                .setWorkerThreads(Math.max(4, cpus << 1))
                .setHandler(exchange -> {
                    if (!exchange.getRequestMethod().equalToString("GET")) {
                        exchange.setStatusCode(405).endExchange();
                        return;
                    }

                    final String ip = exchange.getSourceAddress().getAddress().getHostAddress();
                    if (!WHITELIST.contains(ip)) {
                        System.out.printf("[%s] %s BLOCKED%n", LocalDateTime.now().format(FORMATTER.get()), ip);
                        exchange.setStatusCode(403).endExchange();
                        return;
                    }

                    String requestPath = exchange.getRequestPath();
                    if (requestPath.startsWith("/"))
                        requestPath = requestPath.substring(1);

                    if (requestPath.length() <= 1 || requestPath.contains("..") || requestPath.contains("/")) {
                        exchange.setStatusCode(404).endExchange();
                        return;
                    }

                    if (BLOCKED_FILES.contains(requestPath)) {
                        System.out.printf("[%s] %s requested %s (BLOCKED FILE)%n", LocalDateTime.now().format(FORMATTER.get()), ip, requestPath);
                        exchange.setStatusCode(403).endExchange();
                        return;
                    }

                    final CachedFile cachedFile = getOrLoadFile(requestPath);

                    if (cachedFile == null) {
                        System.out.printf("[%s] %s requested %s (NOT FOUND)%n", LocalDateTime.now().format(FORMATTER.get()), ip, requestPath);
                        exchange.setStatusCode(404).endExchange();
                        return;
                    }

                    cachedFile.lastAccess = System.currentTimeMillis();
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/java-archive");
                    exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION, "attachment; filename=" + requestPath);
                    exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, cachedFile.size);
                    exchange.setStatusCode(200);
                    exchange.getResponseSender().send(cachedFile.buffer.asReadOnlyBuffer());
                    System.out.printf("[%s] %s downloaded %s%n", LocalDateTime.now().format(FORMATTER.get()), ip, requestPath);
                }).build();

        server.start();

        System.out.printf("Server running at http://0.0.0.0:%d%n", port);
        System.out.printf("Whitelist loaded (%d IPs)%n", WHITELIST.size());
        System.out.println("Commands: whitelist add <ip> | whitelist remove <ip> | whitelist list | cache | reload");

        final Thread consoleThread = new Thread(Main::handleConsole, "ConsoleHandler");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private static void loadConfig() throws IOException {
        if (!Files.exists(CONFIG_PATH)) {
            final Properties defaults = new Properties();
            defaults.setProperty("whitelist.path", "whitelist.txt");
            defaults.setProperty("blocked.files.path", "blocked_files.txt");
            defaults.setProperty("date.format", "yyyy-MM-dd HH:mm:ss");
            defaults.setProperty("cache.refresh.ms", "30000");
            defaults.setProperty("max.file.size", "104857600");

            try (final OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                defaults.store(out, "Server Configuration");
            }
        }

        final Properties props = new Properties();
        try (final InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);
        }

        WHITELIST_PATH.set(Paths.get(props.getProperty("whitelist.path", "whitelist.txt")));
        BLOCKED_FILES_PATH.set(Paths.get(props.getProperty("blocked.files.path", "blocked_files.txt")));
        FORMATTER.set(DateTimeFormatter.ofPattern(props.getProperty("date.format", "yyyy-MM-dd HH:mm:ss")));
        CACHE_REFRESH_MS.set(Long.parseLong(props.getProperty("cache.refresh.ms", "30000")));
        MAX_FILE_SIZE.set(Long.parseLong(props.getProperty("max.file.size", "104857600")));
    }

    private static void loadWhitelist() throws IOException {
        loadOrCreate(WHITELIST_PATH, WHITELIST);
    }

    private static void loadBlockedFiles() throws IOException {
        loadOrCreate(BLOCKED_FILES_PATH, BLOCKED_FILES);
    }

    private static void loadOrCreate(final AtomicReference<Path> atomicPath, final Set<String> set) throws IOException {
        final Path path = atomicPath.get();

        if (Files.exists(path)) {
            try (final Stream<String> lines = Files.lines(path)) {
                lines.map(String::trim).filter(s -> !s.isEmpty()).forEach(set::add);
            }
        } else {
            Files.createFile(path);
        }
    }

    private static CachedFile getOrLoadFile(final String filename) {
        CachedFile cached = FILE_CACHE.get(filename);
        if (cached != null) {
            return cached;
        }

        final Path filePath = Paths.get(filename);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return null;
        }

        try {
            final long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE.get()) {
                System.err.printf("File %s exceeds max size (%d bytes)%n", filename, fileSize);
                return null;
            }

            final long lastModified = Files.getLastModifiedTime(filePath).toMillis();
            try (final FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)) {
                final ByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
                cached = new CachedFile(buffer, fileSize, lastModified);
                FILE_CACHE.put(filename, cached);
                System.out.printf("Cached %s (%d bytes)%n", filename, fileSize);
                return cached;
            }
        } catch (final IOException e) {
            System.err.printf("Error loading %s: %s%n", filename, e.getMessage());
            return null;
        }
    }

    private static void handleConsole() {
        final Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            final String line = sc.nextLine().trim();
            if (!line.isEmpty()) processCommand(line);
        }
    }

    private static void processCommand(final String cmd) {
        final String[] parts = cmd.split("\\s+", 3);

        if (parts.length == 1 && "reload".equalsIgnoreCase(parts[0])) {
            try {
                final Path oldWhitelistPath = WHITELIST_PATH.get();
                final Path oldBlockedPath = BLOCKED_FILES_PATH.get();
                loadConfig();
                final Path newWhitelistPath = WHITELIST_PATH.get();
                final Path newBlockedPath = BLOCKED_FILES_PATH.get();

                if (!oldWhitelistPath.equals(newWhitelistPath))
                    System.out.println("Whitelist reloaded from new path: " + newWhitelistPath);

                WHITELIST.clear();
                loadWhitelist();

                if (!oldBlockedPath.equals(newBlockedPath))
                    System.out.println("Blocked files reloaded from new path: " + newBlockedPath);

                BLOCKED_FILES.clear();
                loadBlockedFiles();

                FILE_CACHE.clear();
                System.out.println("Configuration reloaded");
                System.out.printf("  Whitelist path: %s%n", WHITELIST_PATH.get());
                System.out.printf("  Blocked files path: %s%n", BLOCKED_FILES_PATH.get());
                System.out.printf("  Date format: %s%n", FORMATTER.get());
                System.out.printf("  Cache refresh: %d ms%n", CACHE_REFRESH_MS.get());
                System.out.printf("  Max file size: %d bytes%n", MAX_FILE_SIZE.get());
            } catch (final Exception e) {
                System.err.println("Failed to reload config: " + e.getMessage());
            }
            return;
        }

        if (parts.length == 1 && "cache".equalsIgnoreCase(parts[0])) {
            if (FILE_CACHE.isEmpty()) {
                System.out.println("Cache is empty");
            } else {
                System.out.println("Cached files (" + FILE_CACHE.size() + "):");
                FILE_CACHE.forEach((name, cf) -> System.out.printf("  %s (%d bytes)%n", name, cf.size));
            }
            return;
        }

        if (parts.length < 2 || !"whitelist".equalsIgnoreCase(parts[0])) {
            System.out.println("Invalid command. Usage: whitelist add/remove/list <ip> | cache | reload");
            return;
        }

        switch (parts[1].toLowerCase()) {
            case "add":
                if (parts.length < 3) {
                    System.out.println("Missing IP address");
                    return;
                }
                if (WHITELIST.add(parts[2])) {
                    WHITELIST_DIRTY.set(true);
                    System.out.println("Added " + parts[2]);
                } else {
                    System.out.println(parts[2] + " already exists");
                }
                break;

            case "remove":
                if (parts.length < 3) {
                    System.out.println("Missing IP address");
                    return;
                }
                if (WHITELIST.remove(parts[2])) {
                    WHITELIST_DIRTY.set(true);
                    System.out.println("Removed " + parts[2]);
                } else {
                    System.out.println(parts[2] + " not found");
                }
                break;

            case "list":
                if (WHITELIST.isEmpty()) {
                    System.out.println("Whitelist is empty");
                } else {
                    System.out.println("Whitelisted IPs (" + WHITELIST.size() + "):");
                    WHITELIST.forEach(ip -> System.out.println("  " + ip));
                }
                break;

            default:
                System.out.println("Invalid action. Use add/remove/list");
        }
    }

    private static void saveWhitelist() {
        try {
            Files.write(WHITELIST_PATH.get(), WHITELIST, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException e) {
            System.err.println("Failed to save whitelist: " + e.getMessage());
        }
    }

    private static final class CachedFile {
        final ByteBuffer buffer;
        final long size;
        final long lastModified;
        volatile long lastAccess;

        CachedFile(final ByteBuffer buffer, final long size, final long lastModified) {
            this.buffer = buffer;
            this.size = size;
            this.lastModified = lastModified;
            this.lastAccess = System.currentTimeMillis();
        }
    }
}