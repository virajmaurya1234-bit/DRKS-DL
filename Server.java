import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.*;
import static java.nio.charset.StandardCharsets.*;

/** DRKS - Télécharger: upload any file, store it on disk, download it later. Plain Java 17+, no libraries. */
public class Server {
  static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
  static final int PORT = Integer.parseInt(env("PORT", "3000"));
  static final Path DATA = Paths.get(env("DATA_DIR", "data")).toAbsolutePath(), FILES = DATA.resolve("files"), META = DATA.resolve("files.tsv");
  static final Path WEB = Paths.get(env("PUBLIC_DIR", "public")).toAbsolutePath().normalize();
  static final long MAX_FILE = Long.parseLong(env("PUBLIC_MAX_FILE_SIZE", env("MAX_FILE_SIZE", "5368709120")));
  static final long MAX_TOTAL = Long.parseLong(env("PUBLIC_MAX_TOTAL", "0")); // 0 = no total limit
  static final boolean PROXY = !env("TRUST_PROXY", "").isEmpty();
  static final Pattern PREV = Pattern.compile("^(image/(jpeg|png|gif|webp)|video/(mp4|webm|quicktime))$"), FN = Pattern.compile("filename=\"([^\"]*)\"");
  static final SecureRandom RND = new SecureRandom();

  static class Rec { long id, size; int downloads; String pid, name, key, mime, del, at; }
  static class TooLarge extends IOException {}
  static final TreeMap<Long, Rec> DB = new TreeMap<>();
  static final Map<String, Rec> BY = new HashMap<>();
  static long seq = 0, used = 0;

  public static void main(String[] a) throws Exception {
    Files.createDirectories(FILES); load();
    HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
    s.createContext("/", Server::route); s.setExecutor(Executors.newCachedThreadPool()); s.start();
    new PrintStream(System.out, true, UTF_8).println("DRKS - Télécharger running at http://localhost:" + PORT);
  }

  // ---------- storage of file details (tab-separated file, rewritten atomically) ----------
  static synchronized void add(Rec r) { DB.put(r.id, r); BY.put(r.pid, r); used += r.size; seq = Math.max(seq, r.id); }
  static synchronized void drop(Rec r) { DB.remove(r.id); BY.remove(r.pid); used -= r.size; try { Files.deleteIfExists(FILES.resolve(r.key)); } catch (IOException e) {} }
  static synchronized void load() throws IOException {
    if (!Files.exists(META)) return;
    for (String l : Files.readAllLines(META)) {
      String[] p = l.split("\t"); if (p.length < 9) continue;
      Rec r = new Rec(); r.id = Long.parseLong(p[0]); r.pid = p[1]; r.name = URLDecoder.decode(p[2], UTF_8); r.key = p[3]; r.mime = p[4];
      r.size = Long.parseLong(p[5]); r.del = p[6]; r.downloads = Integer.parseInt(p[7]); r.at = p[8]; add(r);
    }
  }
  static synchronized void save() {
    try {
      List<String> o = new ArrayList<>();
      for (Rec r : DB.values()) o.add(String.join("\t", "" + r.id, r.pid, URLEncoder.encode(r.name, UTF_8), r.key, r.mime, "" + r.size, r.del, "" + r.downloads, r.at));
      Path t = META.resolveSibling("files.tsv.tmp"); Files.write(t, o);
      Files.move(t, META, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) { e.printStackTrace(); }
  }

  // ---------- routing ----------
  static void route(HttpExchange x) {
    try {
      String p = x.getRequestURI().getPath(), m = x.getRequestMethod();
      x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      if (p.equals("/api/public/upload") && m.equals("POST")) upload(x);
      else if (p.equals("/api/public/files") && m.equals("GET")) list(x);
      else if (p.startsWith("/api/public/files/")) one(x, p.substring(18).split("/"), m);
      else if (m.equals("GET")) stat(x, p);
      else json(x, 404, "{\"error\":\"Not found.\"}");
    } catch (Throwable e) {
      if (!(e instanceof IOException)) e.printStackTrace();
      try { json(x, 500, "{\"error\":\"Something went wrong. Please try again.\"}"); } catch (Throwable i) {}
    } finally { x.close(); }
  }
  static void json(HttpExchange x, int code, String body) throws IOException {
    byte[] b = body.getBytes(UTF_8); x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b);
  }
  static String esc(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) { if (c == '"' || c == '\\') b.append('\\').append(c); else if (c < 32) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
    return b.toString();
  }
  static void stat(HttpExchange x, String p) throws IOException {
    if (p.equals("/")) p = "/index.html";
    Path f = WEB.resolve("." + URLDecoder.decode(p, UTF_8)).normalize();
    if (!f.startsWith(WEB) || !Files.isRegularFile(f)) { json(x, 404, "{\"error\":\"Not found.\"}"); return; }
    String n = f.getFileName().toString();
    String t = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".jpg") ? "image/jpeg" : n.endsWith(".css") ? "text/css" : n.endsWith(".js") ? "text/javascript" : "application/octet-stream";
    byte[] b = Files.readAllBytes(f); x.getResponseHeaders().set("Content-Type", t); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b);
  }
  static Map<String, String> query(HttpExchange x) {
    Map<String, String> m = new HashMap<>(); String q = x.getRequestURI().getRawQuery();
    if (q != null) for (String kv : q.split("&")) { int i = kv.indexOf('='); if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), UTF_8), URLDecoder.decode(kv.substring(i + 1), UTF_8)); }
    return m;
  }
  static String sha(String t) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(UTF_8))); } catch (Exception e) { throw new RuntimeException(e); } }

  // ---------- list / download / view / delete ----------
  static String card(Rec r) {
    return "{\"pid\":\"" + r.pid + "\",\"filename\":\"" + esc(r.name) + "\",\"mime\":\"" + r.mime + "\",\"size\":" + r.size + ",\"downloads\":" + r.downloads + ",\"created_at\":\"" + r.at + "\",\"preview\":" + PREV.matcher(r.mime).matches() + "}";
  }
  static void list(HttpExchange x) throws IOException {
    Map<String, String> q = query(x); long before = Long.MAX_VALUE;
    try { if (q.containsKey("before")) before = Long.parseLong(q.get("before")); } catch (NumberFormatException e) {}
    String term = q.getOrDefault("q", "").toLowerCase(); StringBuilder sb = new StringBuilder("{\"files\":["); String next = "null";
    synchronized (Server.class) {
      int c = 0; long last = 0;
      for (Rec r : DB.headMap(before, false).descendingMap().values()) {
        if (!r.name.toLowerCase().contains(term)) continue;
        if (c++ > 0) sb.append(','); sb.append(card(r)); last = r.id; if (c == 60) { next = "" + last; break; }
      }
      sb.append("],\"next\":").append(next).append(",\"total\":{\"c\":").append(DB.size()).append(",\"n\":").append(used).append("}}");
    }
    json(x, 200, sb.toString());
  }
  static void one(HttpExchange x, String[] p, String m) throws IOException {
    Rec r; synchronized (Server.class) { r = BY.get(p[0]); }
    if (m.equals("DELETE") && p.length == 1) {
      String t = Optional.ofNullable(x.getRequestHeaders().getFirst("x-delete-token")).orElse("");
      if (r == null || !MessageDigest.isEqual(sha(t).getBytes(UTF_8), r.del.getBytes(UTF_8))) { json(x, 403, "{\"error\":\"Only the person who uploaded this file can delete it.\"}"); return; }
      drop(r); save(); json(x, 200, "{\"ok\":true}"); return;
    }
    if (!m.equals("GET") || p.length != 2) { json(x, 404, "{\"error\":\"Not found.\"}"); return; }
    if (r == null) { json(x, 404, "{\"error\":\"File not found.\"}"); return; }
    boolean view = p[1].equals("view");
    if (view && !PREV.matcher(r.mime).matches()) { json(x, 415, "{\"error\":\"No preview for this file type.\"}"); return; }
    if (!view && !p[1].equals("download")) { json(x, 404, "{\"error\":\"Not found.\"}"); return; }
    if (!view && x.getRequestHeaders().getFirst("Range") == null) synchronized (Server.class) { r.downloads++; }
    serve(x, FILES.resolve(r.key), view ? r.mime : "application/octet-stream", (view ? "inline" : "attachment") + "; filename*=UTF-8''" + URLEncoder.encode(r.name, UTF_8).replace("+", "%20"));
  }
  /** Streams a file from disk in small chunks and supports Range requests so videos can seek. */
  static void serve(HttpExchange x, Path f, String type, String disp) throws IOException {
    long len = Files.size(f), a = 0, b = len - 1; int code = 200; Headers h = x.getResponseHeaders();
    String rg = x.getRequestHeaders().getFirst("Range");
    if (rg != null) {
      Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(rg);
      if (m.matches()) {
        if (!m.group(1).isEmpty()) { a = Long.parseLong(m.group(1)); if (!m.group(2).isEmpty()) b = Math.min(b, Long.parseLong(m.group(2))); }
        else if (!m.group(2).isEmpty()) a = Math.max(0, len - Long.parseLong(m.group(2)));
        if (a > b || a >= len) { h.set("Content-Range", "bytes */" + len); x.sendResponseHeaders(416, -1); return; }
        code = 206; h.set("Content-Range", "bytes " + a + "-" + b + "/" + len);
      }
    }
    h.set("Content-Type", type); h.set("Content-Disposition", disp); h.set("Accept-Ranges", "bytes"); h.set("Content-Security-Policy", "sandbox");
    long n = b - a + 1; x.sendResponseHeaders(code, n > 0 ? n : -1);
    if (n <= 0) return;
    try (RandomAccessFile r = new RandomAccessFile(f.toFile(), "r"); OutputStream o = x.getResponseBody()) {
      r.seek(a); byte[] buf = new byte[65536]; long left = n;
      while (left > 0) { int k = r.read(buf, 0, (int) Math.min(buf.length, left)); if (k < 0) break; o.write(buf, 0, k); left -= k; }
    } catch (IOException e) { /* client closed the connection */ }
  }

  // ---------- upload: streaming multipart parser, files go straight to disk ----------
  static final Map<String, ArrayDeque<Long>> HITS = new HashMap<>();
  static synchronized boolean limited(String ip) {
    long t = System.currentTimeMillis(); ArrayDeque<Long> d = HITS.computeIfAbsent(ip, k -> new ArrayDeque<>());
    while (!d.isEmpty() && t - d.peekFirst() > 600_000) d.pollFirst(); d.addLast(t); return d.size() > 60;
  }
  static void upload(HttpExchange x) throws IOException {
    String ip = x.getRemoteAddress().getAddress().getHostAddress(), xf = x.getRequestHeaders().getFirst("X-Forwarded-For");
    if (PROXY && xf != null) ip = xf.split(",")[0].trim();
    if (limited(ip)) { json(x, 429, "{\"error\":\"Too many uploads. Please wait a few minutes.\"}"); return; }
    synchronized (Server.class) { if (MAX_TOTAL > 0 && used >= MAX_TOTAL) { json(x, 507, "{\"error\":\"Storage is full.\"}"); return; } }
    Matcher bm = Pattern.compile("boundary=\"?([^\";]+)").matcher(String.valueOf(x.getRequestHeaders().getFirst("Content-Type")));
    if (!bm.find()) { json(x, 400, "{\"error\":\"Upload failed. Please try again.\"}"); return; }
    byte[] delim = ("\r\n--" + bm.group(1)).getBytes(ISO_8859_1);
    PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(x.getRequestBody(), 1 << 16), 1 << 17);
    List<Rec> got = new ArrayList<>(); List<String> toks = new ArrayList<>(); Path tmp = null;
    try {
      line(in);
      while (true) {
        String name = null, h;
        while (!(h = line(in)).isEmpty()) { Matcher fm = FN.matcher(h); if (h.toLowerCase().startsWith("content-disposition") && fm.find()) name = new String(fm.group(1).getBytes(ISO_8859_1), UTF_8); }
        if (name != null && !name.isEmpty() && got.size() < 20) {
          String key = UUID.randomUUID().toString(); tmp = FILES.resolve(key); long n;
          try (OutputStream o = Files.newOutputStream(tmp)) { n = copy(in, o, delim, MAX_FILE); }
          Rec r = new Rec(); String mime = sniff(tmp); byte[] t = new byte[16]; RND.nextBytes(t); String tok = HexFormat.of().formatHex(t);
          byte[] id = new byte[12]; RND.nextBytes(id);
          r.pid = HexFormat.of().formatHex(id); r.name = clean(name); r.key = key; r.mime = mime; r.size = n; r.del = sha(tok); r.at = Instant.now().toString();
          got.add(r); toks.add(tok); tmp = null;
        } else copy(in, OutputStream.nullOutputStream(), delim, Long.MAX_VALUE);
        if (line(in).startsWith("--")) break;
      }
    } catch (IOException e) {
      if (tmp != null) Files.deleteIfExists(tmp);
      for (Rec r : got) Files.deleteIfExists(FILES.resolve(r.key));
      if (e instanceof TooLarge) json(x, 413, "{\"error\":\"File is too large.\"}"); else json(x, 400, "{\"error\":\"Upload failed. Please try again.\"}");
      return;
    }
    StringBuilder sb = new StringBuilder("{\"files\":[");
    synchronized (Server.class) { for (int i = 0; i < got.size(); i++) { Rec r = got.get(i); r.id = ++seq; add(r); if (i > 0) sb.append(','); sb.append("{\"pid\":\"" + r.pid + "\",\"filename\":\"" + esc(r.name) + "\",\"size\":" + r.size + ",\"token\":\"" + toks.get(i) + "\"}"); } }
    save(); json(x, 200, sb.append("]}").toString());
  }
  static String clean(String n) {
    n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1).replaceAll("[\\x00-\\x1f<>:\"|?*]", "_");
    return n.isBlank() ? "file" : n.length() > 200 ? n.substring(0, 200) : n;
  }
  static String line(PushbackInputStream in) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream(); int c;
    while ((c = in.read()) != -1 && c != '\n') { if (c != '\r') o.write(c); if (o.size() > 8192) throw new IOException("line too long"); }
    if (c == -1 && o.size() == 0) throw new EOFException();
    return o.toString(ISO_8859_1);
  }
  /** Copies bytes to out until the delimiter appears; the delimiter is consumed, anything after it is pushed back. */
  static long copy(PushbackInputStream in, OutputStream out, byte[] d, long max) throws IOException {
    byte[] w = new byte[65536 + d.length]; int len = 0; long total = 0;
    while (true) {
      int r = in.read(w, len, w.length - len); if (r < 0) throw new EOFException(); len += r;
      int i = idx(w, len, d);
      if (i >= 0) { out.write(w, 0, i); total += i; if (total > max) throw new TooLarge(); in.unread(w, i + d.length, len - i - d.length); return total; }
      int keep = d.length - 1, k = len - keep;
      if (k > 0) { out.write(w, 0, k); total += k; System.arraycopy(w, k, w, 0, keep); len = keep; if (total > max) throw new TooLarge(); }
    }
  }
  static int idx(byte[] w, int len, byte[] d) {
    outer: for (int i = 0; i <= len - d.length; i++) { if (w[i] != d[0]) continue; for (int j = 1; j < d.length; j++) if (w[i + j] != d[j]) continue outer; return i; }
    return -1;
  }
  /** Decides the file type from its first bytes, not from its name. */
  static String sniff(Path f) throws IOException {
    byte[] b = new byte[16]; try (InputStream i = Files.newInputStream(f)) { i.readNBytes(b, 0, 16); }
    String a = new String(b, ISO_8859_1);
    if (a.startsWith("\u00ff\u00d8\u00ff")) return "image/jpeg"; if (a.startsWith("\u0089PNG")) return "image/png"; if (a.startsWith("GIF8")) return "image/gif";
    if (a.startsWith("RIFF") && a.startsWith("WEBP", 8)) return "image/webp"; if (a.startsWith("RIFF") && a.startsWith("AVI", 8)) return "video/x-msvideo";
    if (a.startsWith("\u001a\u0045\u00df\u00a3")) return "video/webm";
    if (a.startsWith("ftyp", 4)) { String br = a.substring(8, 12); return br.matches("(heic|heix|mif1|msf1)") ? "image/heic" : br.startsWith("qt") ? "video/quicktime" : "video/mp4"; }
    return "application/octet-stream";
  }
}
