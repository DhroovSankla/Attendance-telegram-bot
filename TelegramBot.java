import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import com.sun.net.httpserver.HttpServer;

public class TelegramBot {

    // Token is loaded strictly from environment variable BOT_TOKEN or local .env file
    private static final String HARDCODED_BOT_TOKEN = "";

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";
    private static final File BOT_USERS_FILE = new File("bot_users.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Multi-threaded worker pool to handle multiple students concurrently in parallel
    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(16);

    private final String botToken;
    private final HttpClient httpClient;
    private final Map<Long, UserSession> userSessions = new ConcurrentHashMap<>();

    public static class UserSession {
        public String username;
        public String auth; // Base64 encoded password
        public List<Map<String, String>> cookies = new ArrayList<>();

        public UserSession() {}

        public UserSession(String username, String password, List<HttpCookie> httpCookies) {
            this.username = username;
            this.auth = Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
            setCookies(httpCookies);
        }

        public String getPassword() {
            if (auth == null) return null;
            return new String(Base64.getDecoder().decode(auth), StandardCharsets.UTF_8);
        }

        public void setCookies(List<HttpCookie> httpCookies) {
            this.cookies.clear();
            for (HttpCookie c : httpCookies) {
                Map<String, String> map = new HashMap<>();
                map.put("name", c.getName());
                map.put("value", c.getValue());
                map.put("domain", c.getDomain() != null ? c.getDomain() : "student.cgc.ac.in");
                map.put("path", c.getPath() != null ? c.getPath() : "/");
                this.cookies.add(map);
            }
        }

        public List<HttpCookie> getHttpCookies() {
            List<HttpCookie> list = new ArrayList<>();
            for (Map<String, String> m : cookies) {
                HttpCookie c = new HttpCookie(m.get("name"), m.get("value"));
                c.setDomain(m.getOrDefault("domain", "student.cgc.ac.in"));
                c.setPath(m.getOrDefault("path", "/"));
                list.add(c);
            }
            return list;
        }
    }

    public TelegramBot(String token) {
        this.botToken = token;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        loadUserSessions();
    }

    public void start() {
        System.out.println("🤖 CGC Attendance Telegram Bot is running...");
        long offset = 0;

        while (true) {
            try {
                String url = TELEGRAM_API_BASE + botToken + "/getUpdates?offset=" + offset + "&timeout=25";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(35))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = MAPPER.readTree(response.body());
                    if (root.path("ok").asBoolean()) {
                        JsonNode updates = root.path("result");
                        for (JsonNode update : updates) {
                            long updateId = update.path("update_id").asLong();
                            offset = updateId + 1;
                            // Asynchronously process each incoming message concurrently
                            WORKER_POOL.submit(() -> handleUpdate(update));
                        }
                    }
                } else if (response.statusCode() == 401) {
                    System.err.println("❌ Invalid Telegram Bot Token. Please check your BOT_TOKEN variable.");
                    break;
                }
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleUpdate(JsonNode update) {
        if (!update.has("message")) return;
        JsonNode message = update.get("message");
        if (!message.has("text")) return;

        long chatId = message.path("chat").path("id").asLong();
        int messageId = message.path("message_id").asInt();
        String text = message.path("text").asText().trim();

        if (text.startsWith("/start") || text.equalsIgnoreCase("❓ Help") || text.equalsIgnoreCase("/help")) {
            sendWelcome(chatId);
        } else if (text.startsWith("/login")) {
            handleLogin(chatId, messageId, text);
        } else if (text.equalsIgnoreCase("/att") || text.equalsIgnoreCase("📊 Check Attendance")) {
            sendChatAction(chatId, "typing");
            handleAttendance(chatId);
        } else if (text.equalsIgnoreCase("/logout") || text.equalsIgnoreCase("🚪 Logout")) {
            handleLogout(chatId);
        } else if (text.equalsIgnoreCase("🔐 Login")) {
            sendMessage(chatId, "🔐 *How to Login:*\n\nSend your credentials in this format:\n`/login <RollNumber> <Password>`\n\n_Example:_\n`/login 2300000000 Pass@123`\n\n🔒 _Your password message will be auto-deleted immediately from chat for security!_");
        } else {
            sendMessage(chatId, "❓ *Unrecognized command.* Use the buttons below or send `/att` to check your attendance.");
        }
    }

    private void sendWelcome(long chatId) {
        String msg = """
                👋 *Welcome to CGC Attendance Bot!*
                ━━━━━━━━━━━━━━━━━━━━━
                Check your real-time attendance and daily timetable in *sub-second speed*!

                📌 *Quick Commands:*
                • `/login <Roll> <Password>` — Log in once to save session
                • `/att` — Check today's attendance & timetable
                • `/logout` — Clear saved login
                ━━━━━━━━━━━━━━━━━━━━━
                👇 *Tap a button below to get started:*
                """;
        sendMessage(chatId, msg);
    }

    private void handleLogin(long chatId, int messageId, String text) {
        deleteMessage(chatId, messageId);

        String[] parts = text.split("\\s+");
        if (parts.length < 3) {
            sendMessage(chatId, "⚠️ *Invalid Format!*\nUsage: `/login <RollNumber> <Password>`\nExample: `/login 2300000000 Pass@123`");
            return;
        }

        String roll = parts[1];
        String pass = parts[2];

        sendChatAction(chatId, "typing");

        AttendanceFetcher fetcher = new AttendanceFetcher();
        AttendanceFetcher.AttendanceReport report = fetcher.loginAndFetch(roll, pass);

        if (report.success) {
            UserSession session = new UserSession(roll, pass, report.sessionCookies);
            userSessions.put(chatId, session);
            saveUserSessions();

            sendMessage(chatId, "✅ *Login Successful!*\nYour session has been saved. You can now tap *[ 📊 Check Attendance ]* anytime for instant results.\n\n" + report.toTelegramMarkdown());
        } else {
            sendMessage(chatId, "❌ *Login Failed!*\n" + report.errorMessage + "\n\nPlease verify your credentials and try again.");
        }
    }

    private void handleAttendance(long chatId) {
        UserSession session = userSessions.get(chatId);
        if (session == null) {
            sendMessage(chatId, "⚠️ *You are not logged in yet!*\n\nPlease log in first using:\n`/login <RollNumber> <Password>`");
            return;
        }

        // 1. Ultra-fast direct fetch using cached cookies via Shared Client (< 300ms)
        AttendanceFetcher.AttendanceReport report = AttendanceFetcher.fastFetchWithCookies(session.getHttpCookies());

        // 2. If session expired, auto-relogin in background
        if (!report.success && session.getPassword() != null) {
            AttendanceFetcher fetcher = new AttendanceFetcher();
            report = fetcher.loginAndFetch(session.username, session.getPassword());
            if (report.success) {
                session.setCookies(report.sessionCookies);
                saveUserSessions();
            }
        }

        if (report.success) {
            sendMessage(chatId, report.toTelegramMarkdown());
        } else {
            sendMessage(chatId, "⚠️ *Could not fetch attendance.*\n" + report.errorMessage + "\n\nTry re-logging in with `/login`.");
        }
    }

    private void handleLogout(long chatId) {
        if (userSessions.remove(chatId) != null) {
            saveUserSessions();
            sendMessage(chatId, "🚪 *Logged out successfully.* Your session and credentials have been deleted.");
        } else {
            sendMessage(chatId, "ℹ️ You are not logged in.");
        }
    }

    private void sendChatAction(long chatId, String action) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API_BASE + botToken + "/sendChatAction?chat_id=" + chatId + "&action=" + action))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    private void sendMessage(long chatId, String text) {
        try {
            String keyboardJson = """
                    {
                        "keyboard": [
                            [{"text": "📊 Check Attendance"}],
                            [{"text": "🔐 Login"}, {"text": "❓ Help"}, {"text": "🚪 Logout"}]
                        ],
                        "resize_keyboard": true
                    }
                    """;

            Map<String, String> params = new HashMap<>();
            params.put("chat_id", String.valueOf(chatId));
            params.put("text", text);
            params.put("parse_mode", "Markdown");
            params.put("reply_markup", keyboardJson);

            String formBody = params.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                              URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API_BASE + botToken + "/sendMessage"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void deleteMessage(long chatId, int messageId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API_BASE + botToken + "/deleteMessage?chat_id=" + chatId + "&message_id=" + messageId))
                    .GET()
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    private void loadUserSessions() {
        if (!BOT_USERS_FILE.exists()) return;
        try {
            JsonNode root = MAPPER.readTree(BOT_USERS_FILE);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                long cid = Long.parseLong(entry.getKey());
                UserSession sess = MAPPER.treeToValue(entry.getValue(), UserSession.class);
                userSessions.put(cid, sess);
            }
        } catch (Exception e) {
            System.err.println("Note: Starting with clean user session database.");
        }
    }

    private void saveUserSessions() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(BOT_USERS_FILE, userSessions);
        } catch (Exception ignored) {}
    }

    public static String resolveBotToken() {
        if (HARDCODED_BOT_TOKEN != null && !HARDCODED_BOT_TOKEN.trim().isEmpty()) {
            return HARDCODED_BOT_TOKEN.trim();
        }

        String envToken = System.getenv("BOT_TOKEN");
        if (envToken != null && !envToken.trim().isEmpty()) {
            return envToken.trim();
        }

        File envFile = new File(".env");
        if (envFile.exists()) {
            try (Scanner scanner = new Scanner(envFile)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.startsWith("BOT_TOKEN=")) {
                        String val = line.substring("BOT_TOKEN=".length()).trim();
                        if (!val.equals("YOUR_TELEGRAM_BOT_TOKEN_HERE") && !val.isEmpty()) {
                            return val;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static void startHealthServer() {
        try {
            String portStr = System.getenv("PORT");
            int port = (portStr != null && !portStr.isEmpty()) ? Integer.parseInt(portStr) : 10000;
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                String response = "CGC Attendance Bot is running healthy!";
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            server.setExecutor(null);
            server.start();
            System.out.println("🌐 Health check server listening on port " + port);
        } catch (Exception e) {
            System.err.println("Note on health server: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        startHealthServer();

        String token = resolveBotToken();
        if (token == null) {
            System.err.println("❌ ERROR: Telegram Bot Token not found!");
            System.err.println("👉 Please set the BOT_TOKEN environment variable in Render / .env file.");
            return;
        }

        TelegramBot bot = new TelegramBot(token);
        bot.start();
    }
}
