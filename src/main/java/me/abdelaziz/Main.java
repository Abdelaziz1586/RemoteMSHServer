package me.abdelaziz;

import io.undertow.Undertow;
import io.undertow.util.Headers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class Main {

    private static final String FILENAME = "MSHJava.jar";
    private static final Path FILE_PATH = Paths.get(FILENAME);
    private static final Path WHITELIST_PATH = Paths.get("whitelist.txt");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<String> WHITELIST = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean WHITELIST_DIRTY = new AtomicBoolean(false);
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "WhitelistSaver");
        t.setDaemon(true);
        return t;
    });

    private static final long FILE_LENGTH;
    private static final ByteBuffer FILE_BUFFER;

    static {
        try {
            if (!Files.exists(FILE_PATH)) {
                System.err.println("File not found: " + FILE_PATH.toAbsolutePath());
                System.exit(1);
            }

            try (final FileChannel fc = FileChannel.open(FILE_PATH, StandardOpenOption.READ)) {
                FILE_LENGTH = fc.size();
                FILE_BUFFER = fc.map(FileChannel.MapMode.READ_ONLY, 0, FILE_LENGTH);
            }

            if (Files.exists(WHITELIST_PATH)) {
                try (final Stream<String> lines = Files.lines(WHITELIST_PATH)) {
                    lines.map(String::trim).filter(s -> !s.isEmpty()).forEach(WHITELIST::add);
                }
            } else {
                Files.createFile(WHITELIST_PATH);
            }

        } catch (final IOException e) {
            throw new ExceptionInInitializerError(e);
        }

        SCHEDULER.scheduleAtFixedRate(() -> {
            if (WHITELIST_DIRTY.compareAndSet(true, false)) saveWhitelist();
        }, 5, 5, TimeUnit.SECONDS);

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
                    if (!WHITELIST.isEmpty() && !WHITELIST.contains(ip)) {
                        System.out.printf("[%s] %s BLOCKED%n", LocalDateTime.now().format(FORMATTER), ip);
                        exchange.setStatusCode(403).endExchange();
                        return;
                    }

                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/java-archive");
                    exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION, "attachment; filename=" + FILENAME);
                    exchange.setStatusCode(200);
                    exchange.getResponseSender().send(FILE_BUFFER.asReadOnlyBuffer());
                    System.out.printf("[%s] %s downloaded %s%n", LocalDateTime.now().format(FORMATTER), ip, FILENAME);
                }).build();

        server.start();

        System.out.printf("Server running at http://0.0.0.0:%d%n", port);
        System.out.printf("Serving %s (%d bytes)%n", FILE_PATH.toAbsolutePath(), FILE_LENGTH);
        System.out.printf("Whitelist loaded (%d IPs)%n", WHITELIST.size());
        System.out.println("Commands: whitelist add <ip> | whitelist remove <ip> | whitelist list");

        final Thread consoleThread = new Thread(Main::handleConsole, "ConsoleHandler");
        consoleThread.setDaemon(true);
        consoleThread.start();
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
        if (parts.length < 2 || !"whitelist".equalsIgnoreCase(parts[0])) {
            System.out.println("Invalid command. Usage: whitelist add/remove/list <ip>");
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
            Files.write(WHITELIST_PATH, WHITELIST, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException e) {
            System.err.println("Failed to save whitelist: " + e.getMessage());
        }
    }
}