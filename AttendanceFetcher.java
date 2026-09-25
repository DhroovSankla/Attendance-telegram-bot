import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AttendanceFetcher {

    private static final String LOGIN_URL = "https://login.cgc.ac.in/";
    private static final String ATTENDANCE_PAGE_URL = "https://student.cgc.ac.in/Attendance.aspx";
    private static final File SESSION_FILE = new File(".cgc_session.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Reliable HTTP/1.1 client with persistent connection pool (compatible with IIS / ASP.NET)
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private final HttpClient client;
    private final CookieManager cookieManager;

    public static class Lecture {
        public String subject;
        public String time;
        public String status; // "Present", "Absent", etc.

        public Lecture(String subject, String time, String status) {
            this.subject = subject;
            this.time = time;
            this.status = status;
        }
    }

    public static class AttendanceReport {
        public boolean success;
        public String errorMessage;
        public String overallPct;
        public List<Lecture> lectures = new ArrayList<>();
        public List<HttpCookie> sessionCookies = new ArrayList<>();

        public String toTelegramMarkdown() {
            if (!success) {
                return "❌ *" + (errorMessage != null ? errorMessage : "Failed to fetch attendance.") + "*";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📊 *CGC Attendance Report*\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📈 *Overall Attendance:* `").append(overallPct).append("`\n\n");
            sb.append("📅 *Today's Schedule:*\n");

            if (lectures.isEmpty()) {
                sb.append("ℹ️ _No classes scheduled for today._\n");
            } else {
                for (Lecture l : lectures) {
                    String statusEmoji = l.status.equalsIgnoreCase("Present") ? "✅ Present" : "❌ Absent";
                    sb.append("▪️ *").append(escapeMarkdown(l.subject)).append("*\n");
                    sb.append("   ⏰ `").append(l.time).append("`\n");
                    sb.append("   📌 Status: *").append(statusEmoji).append("*\n\n");
                }
            }
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("⚡ _Updated in real-time_");
            return sb.toString();
        }

        private String escapeMarkdown(String text) {
            return text.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
        }
    }

    public AttendanceFetcher() {
        this.cookieManager = new CookieManager();
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(this.cookieManager)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public CookieManager getCookieManager() {
        return cookieManager;
    }

    public void setCookies(List<HttpCookie> cookies) {
        for (HttpCookie c : cookies) {
            URI uri = URI.create("https://student.cgc.ac.in");
            cookieManager.getCookieStore().add(uri, c);
        }
    }

    /**
     * Ultra-fast direct fetch using cached cookies via Shared Client
     */
    public static AttendanceReport fastFetchWithCookies(List<HttpCookie> cookies) {
        AttendanceReport report = new AttendanceReport();
        try {
            String cookieHeader = cookies.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ATTENDANCE_PAGE_URL))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Cookie", cookieHeader)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> res = SHARED_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String body = res.body();

            if (res.statusCode() == 200 && isDashboardPage(body)) {
                parseDashboardStatic(body, report);
                report.success = true;
                report.sessionCookies = cookies;
                return report;
            }
        } catch (Exception e) {
            report.errorMessage = "Fast fetch error: " + e.getMessage();
        }
        report.success = false;
        return report;
    }

    public AttendanceReport fetchWithCurrentSession() {
        AttendanceReport report = new AttendanceReport();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ATTENDANCE_PAGE_URL))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = res.body();

            if (res.statusCode() == 200 && isDashboardPage(body)) {
                parseDashboard(body, report);
                report.success = true;
                report.sessionCookies = cookieManager.getCookieStore().getCookies();
                return report;
            }
        } catch (Exception e) {
            report.errorMessage = "Network error: " + e.getMessage();
        }
        report.success = false;
        return report;
    }

    public AttendanceReport loginAndFetch(String username, String password) {
        AttendanceReport report = new AttendanceReport();
        try {
            // 1. Fetch ASP.NET Tokens
            HttpRequest getLogin = HttpRequest.newBuilder()
                    .uri(URI.create(LOGIN_URL))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(25))
                    .GET()
                    .build();

            HttpResponse<String> getRes = client.send(getLogin, HttpResponse.BodyHandlers.ofString());
            Document loginDoc = Jsoup.parse(getRes.body());

            Map<String, String> formData = new HashMap<>();
            formData.put("__VIEWSTATE", extractValue(loginDoc, "__VIEWSTATE"));
            formData.put("__VIEWSTATEGENERATOR", extractValue(loginDoc, "__VIEWSTATEGENERATOR"));
            formData.put("__EVENTVALIDATION", extractValue(loginDoc, "__EVENTVALIDATION"));
            formData.put("__EVENTTARGET", "");
            formData.put("__EVENTARGUMENT", "");

            formData.put("txtuser", username);
            formData.put("txtpass", password);
            formData.put("btnLogin", "Login");

            String formBody = formData.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                              URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));

            // 2. Submit Login
            HttpRequest postLogin = HttpRequest.newBuilder()
                    .uri(URI.create(LOGIN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            client.send(postLogin, HttpResponse.BodyHandlers.ofString());

            // 3. Fetch dedicated Attendance Page (contains the full daily timetable)
            HttpRequest getAttendancePage = HttpRequest.newBuilder()
                    .uri(URI.create(ATTENDANCE_PAGE_URL))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(25))
                    .GET()
                    .build();

            HttpResponse<String> attPageRes = client.send(getAttendancePage, HttpResponse.BodyHandlers.ofString());

            if (attPageRes.statusCode() == 200 && isDashboardPage(attPageRes.body())) {
                parseDashboard(attPageRes.body(), report);
                report.success = true;
                report.sessionCookies = cookieManager.getCookieStore().getCookies();
                return report;
            } else {
                report.success = false;
                report.errorMessage = "Login failed. Please check your Roll Number and Password.";
                return report;
            }
        } catch (Exception e) {
            report.success = false;
            report.errorMessage = "Server error: " + e.getMessage();
            return report;
        }
    }

    private static boolean isDashboardPage(String html) {
        return html.contains("Attendance") && (html.contains("Overall") || html.contains("%") || html.contains("Subject"));
    }

    private void parseDashboard(String html, AttendanceReport report) {
        parseDashboardStatic(html, report);
    }

    private static void parseDashboardStatic(String html, AttendanceReport report) {
        Document doc = Jsoup.parse(html);

        // 1. Overall Attendance
        String overallPct = "N/A";
        Pattern pctPattern = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,2}\\s*%)");
        Matcher pctMatcher = pctPattern.matcher(doc.text());
        if (pctMatcher.find()) {
            overallPct = pctMatcher.group(1);
        }
        report.overallPct = overallPct;

        // 2. Today's Lectures
        Element todayContainer = null;
        for (Element el : doc.select("div, col, section, td, th")) {
            String text = el.ownText().trim();
            if (text.equalsIgnoreCase("Today") || (el.text().startsWith("Today") && el.text().length() < 30)) {
                todayContainer = el.parent();
                break;
            }
        }

        Elements searchScope = (todayContainer != null) ? todayContainer.getAllElements() : doc.getAllElements();
        Set<String> seenLectures = new LinkedHashSet<>();

        for (Element el : searchScope) {
            String fullText = el.text().trim();

            Matcher timeMatcher = Pattern.compile("(\\d{1,2}:\\d{2}\\s*[AP]M\\s*to\\s*\\d{1,2}:\\d{2}\\s*[AP]M)", Pattern.CASE_INSENSITIVE).matcher(fullText);
            if (timeMatcher.find()) {
                if (el.children().select(":has(*:contains(to))").size() > 0) continue;

                String time = timeMatcher.group(1).trim();

                Element card = el;
                while (card != null && card != todayContainer) {
                    String ct = card.text();
                    if ((ct.contains("Present") || ct.contains("Absent")) && 
                        (ct.contains("(T)") || ct.contains("(L)") || ct.contains("(W)") || ct.contains("LAB"))) {
                        break;
                    }
                    card = card.parent();
                }
                if (card == null || card == todayContainer) {
                    card = el.parent() != null ? el.parent() : el;
                }

                String cardText = card.text();

                String status = "Absent";
                if (cardText.contains("Present") || 
                    card.select("span:contains(Present), div:contains(Present), .badge-success, [style*='green']").size() > 0) {
                    status = "Present";
                } else if (cardText.contains("Absent")) {
                    status = "Absent";
                }

                String subject = "N/A";
                Matcher subMatcher = Pattern.compile("([A-Za-z0-9\\-\\s&]{2,30}\\s*\\([TLW0-9]+\\))").matcher(cardText);
                if (subMatcher.find()) {
                    subject = subMatcher.group(1).trim();
                } else {
                    Element subjEl = card.selectFirst("h1, h2, h3, h4, h5, h6, strong, b, .subject, .title");
                    if (subjEl != null && !subjEl.text().trim().isEmpty()) {
                        subject = subjEl.text().trim();
                    } else {
                        subject = cardText.replaceAll("(?i)(Present|Absent|\\d{1,2}:\\d{2}.*)", "").trim();
                    }
                }

                String uniqueKey = subject + "@" + time;
                if (!subject.isEmpty() && !time.equals("N/A") && !seenLectures.contains(uniqueKey)) {
                    seenLectures.add(uniqueKey);
                    report.lectures.add(new Lecture(subject, time, status));
                }
            }
        }
    }

    private String extractValue(Document doc, String elementId) {
        Element el = doc.getElementById(elementId);
        return el != null ? el.val() : "";
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max - 3) + "..." : text;
    }

    // --- CLI Execution Support ---
    public static void main(String[] args) {
        AttendanceFetcher fetcher = new AttendanceFetcher();

        // 1. Try cached session file
        if (SESSION_FILE.exists()) {
            try {
                JsonNode root = MAPPER.readTree(SESSION_FILE);
                List<HttpCookie> cookies = new ArrayList<>();
                if (root.has("cookies")) {
                    for (JsonNode cNode : root.get("cookies")) {
                        HttpCookie c = new HttpCookie(cNode.path("name").asText(), cNode.path("value").asText());
                        c.setDomain(cNode.path("domain").asText("student.cgc.ac.in"));
                        c.setPath(cNode.path("path").asText("/"));
                        cookies.add(c);
                    }
                    AttendanceReport cachedReport = fastFetchWithCookies(cookies);
                    if (cachedReport.success) {
                        System.out.println("⚡ Logged in instantly using cached session!");
                        printCliReport(cachedReport);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Prompt Credentials
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter Roll Number: ");
        String roll = scanner.nextLine().trim();

        String pass = "";
        java.io.Console console = System.console();
        if (console != null) {
            char[] passArray = console.readPassword("Enter Password: ");
            pass = new String(passArray);
        } else {
            System.out.print("Enter Password: ");
            pass = scanner.nextLine().trim();
        }

        System.out.println("Logging in and fetching dashboard...");
        AttendanceReport report = fetcher.loginAndFetch(roll, pass);

        if (report.success) {
            saveSessionLocally(roll, pass, report.sessionCookies);
            printCliReport(report);
        } else {
            System.err.println(report.errorMessage);
        }
    }

    private static void printCliReport(AttendanceReport report) {
        System.out.println("\n========================================================");
        System.out.println("                 CGC ATTENDANCE CLI                     ");
        System.out.println("========================================================");
        System.out.printf(" Overall Attendance (Till Yesterday) : \033[1;32m%s\033[0m\n", report.overallPct);
        System.out.println("--------------------------------------------------------\n");

        System.out.println("--- TODAY'S SCHEDULE ---");
        System.out.println("+----+-------------------------+-----------------------+----------+");
        System.out.println("| #  | Subject                 | Time                  | Status   |");
        System.out.println("+----+-------------------------+-----------------------+----------+");

        if (report.lectures.isEmpty()) {
            System.out.println("| -- | No class records parsed for today.                      |");
        } else {
            int count = 1;
            for (Lecture l : report.lectures) {
                String status = l.status.equalsIgnoreCase("Present") ? "\033[32mPresent\033[0m" : "\033[31mAbsent \033[0m";
                System.out.printf("| %-2d | %-23s | %-21s | %-8s |\n", count++, truncate(l.subject, 23), l.time, status);
            }
        }
        System.out.println("+----+-------------------------+-----------------------+----------+\n");
    }

    private static void saveSessionLocally(String username, String password, List<HttpCookie> cookies) {
        try {
            Map<String, Object> sessionData = new HashMap<>();
            sessionData.put("username", username);
            sessionData.put("auth", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));

            List<Map<String, String>> cookieList = new ArrayList<>();
            for (HttpCookie cookie : cookies) {
                Map<String, String> c = new HashMap<>();
                c.put("name", cookie.getName());
                c.put("value", cookie.getValue());
                c.put("domain", cookie.getDomain() != null ? cookie.getDomain() : "student.cgc.ac.in");
                c.put("path", cookie.getPath() != null ? cookie.getPath() : "/");
                cookieList.add(c);
            }
            sessionData.put("cookies", cookieList);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(SESSION_FILE, sessionData);
        } catch (Exception ignored) {}
    }
}