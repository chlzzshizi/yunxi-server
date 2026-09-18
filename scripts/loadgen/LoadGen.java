// 抢券接口压测器 —— JDK 21 虚拟线程，零依赖，单文件直接跑：
//
//   java scripts/loadgen/LoadGen.java <并发数> \
//     <标签> <url> <token文件> [<标签> <url> <token文件> ...]
//
// 例（三轮都在同一个 JVM 里跑完）：
//   java scripts/loadgen/LoadGen.java 500 \
//     warmup  http://localhost:8081/api/coupons/12/grab /tmp/tokens.txt \
//     measure http://localhost:8081/api/coupons/13/grab /tmp/tokens.txt \
//     control http://localhost:8081/api/coupons/14/grab /tmp/one-token.txt
//
// 为什么自己写，而不是用 wrk / hey / ab：
//   抢券压测**每个请求必须是不同顾客**。同一个顾客并发发 N 次，全部会被
//   Redis Set（coupon:grabbed:*）和数据库唯一键（uk_coupon_customer）去重挡下，
//   测到的是"去重生效"，不是"库存竞争" —— 那是个假的高并发测试。
//   而 wrk/hey 只会对同一个 URL 反复发同一个请求头，喂不了 N 个不同 token。
//   虚拟线程让"一个请求一个线程、一个线程一个 token"写起来和同步代码一样直白，
//   不需要回调、不需要线程池调参，也没有 fork 500 个 curl 进程那种开销。
//
// ⚠️ 为什么所有轮次必须挤在一个 JVM 里（2026-09-18 血债）：
//   第一版的用法是 `java LoadGen.java <url> <tokens> [n] [label]`，由
//   bench-coupon.sh 调三次、开三个 JVM。跑出来三轮 TPS 是 279 / 305 / 303，
//   当时当成"服务端吞吐很稳定"写进了报告 —— **是错的**。
//   拿一个瞬时响应的对照服务端（不碰库不碰 Redis）一量才现原形：
//   打对照服务端 N=1 要 116ms，**比打真实应用还慢**。
//   真因是 JVM 冷启动：同一个 JVM 里连跑四轮 N=50，p50 是
//   344ms → 32ms → 23ms → 58ms，**第一轮比第三轮慢 15 倍**。
//   也就是说那个"预热轮"只暖到了服务端（同一个 Spring 进程），
//   客户端三轮全是冷的 —— 三轮数字一样，不是因为稳定，是因为一样冷。
//   所以现在改成：一个 JVM，一串轮次，第一轮才算预热。这是整个脚本的地基。
//
// 输出**全是 ASCII**。本项目的既有教训（见 scripts/verify-stores.sh 文件头）是
// 非 ASCII 经过 MSYS2 / Windows 控制台会被按代码页转码，而压测的输出是要被
// bash 拿去断言的，转码一次断言就成了玄学。所以中文提示一律走
// URL 百分号编码（编码的是 UTF-8 字节），既确定又能被精确比对。
//
// 每轮最后一行 RESULT 是 JSON，给 bench-coupon.sh 用现成的 jqf() 切片断言。

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoadGen {

    // Result 信封是 {"code":200,"message":"...","data":...}，手写扫描不引 JSON 库。
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern MSG = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    public static void main(String[] args) throws Exception {
        // 参数 = <并发数> 后面跟若干组 <标签> <url> <token文件>，一组一轮。
        // 组数不固定是因为"预热要几轮"取决于机器，bash 那边想加一轮就加一组。
        if (args.length < 4 || (args.length - 1) % 3 != 0) {
            // 用法说明**必须 ASCII**：Java 的 stdout 在这台机器上不是 UTF-8，
            // 打中文出来是乱码（实测）。而这段是唯一直接给人看的输出 ——
            // 最容易读到的部分，偏偏最不该是乱码。详见文件头那段编码说明。
            System.out.println("usage: java LoadGen.java <n> <label> <url> <tokensFile> "
                    + "[<label> <url> <tokensFile> ...]");
            System.out.println("  <n>          requests per burst (and tokens consumed per burst)");
            System.out.println("  <label>      name of this burst, shows up in the output");
            System.out.println("  <url>        e.g. http://localhost:8081/api/coupons/12/grab");
            System.out.println("  <tokensFile> one JWT per line; needs at least <n> lines");
            System.out.println("All bursts run in THIS ONE JVM, in order. The first is the");
            System.out.println("warmup -- see the header of this file for why that matters.");
            System.exit(2);
        }
        int n = Integer.parseInt(args[0]);
        if (n <= 0) {
            System.out.println("[FATAL] n must be positive, got " + n);
            System.exit(2);
        }

        System.out.println("[env] java=" + Runtime.version()
                + " cores=" + Runtime.getRuntime().availableProcessors());
        System.out.println("[cfg] n=" + n + " rounds=" + ((args.length - 1) / 3));

        // 一个共享的 client，而且**跨轮次复用**：HttpClient 是线程安全的，
        // 连接池也就能跨轮续上。每轮新建一个会把上一轮刚建好的连接全丢掉，
        // 下一轮又付一遍建连成本 —— 那正是第一版踩的坑的另一半。
        // 显式钉 HTTP/1.1：明文 http:// 下 Java 默认先试 h2c upgrade，
        // Tomcat 不认，会白多一个往返。
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        for (int r = 1; r + 2 < args.length; r += 3) {
            runBurst(client, n, args[r], args[r + 1], Path.of(args[r + 2]));
        }
    }

    /** 跑一轮：n 个虚拟线程同时开火，打同一个 url，各持各的 token。 */
    private static void runBurst(HttpClient client, int n, String label, String url, Path tokenFile)
            throws Exception {
        List<String> tokens = Files.readAllLines(tokenFile, StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (tokens.size() < n) {
            System.out.println("[FATAL] label=" + label + " asked for " + n
                    + " concurrent but only " + tokens.size() + " tokens in " + tokenFile
                    + " -- reuse would be swallowed by the dedup Set, see the header");
            System.exit(2);
        }
        System.out.println("[cfg] label=" + label + " n=" + n + " url=" + url);

        // 请求**全部在主线程里先建好**，一个线程拿到的是一个不可变的 HttpRequest。
        //
        // 别图省事共享一个 HttpRequest.Builder 再让各线程 .header(...).build()：
        // Builder 明确不是线程安全的（内部是个 TreeMap），500 个线程并发改它就是
        // 在并发改一个普通 map。2026-09-18 的第一版正是这么写的，症状是
        // **脚本挂在第一轮、库里一条抢券记录都没有** —— 因为那一行当时还在 try
        // 之外，任何一个线程在那儿抛异常，它就再也走不到 gate.countDown() 和
        // done.countDown()，而主线程正在 done.await() 上等一个永远到不了 0 的计数器。
        // （只测 N=10 是撞不出来的，所以它一路混到了 500 并发才现形。）
        HttpRequest[] reqs = new HttpRequest[n];
        for (int i = 0; i < n; i++) {
            reqs[i] = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + tokens.get(i))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
        }

        // 按下标写，无锁无竞争。t0/t1 分开存是为了算"整轮爆发"的窗口：
        // 墙钟差值会把线程创建、门闸等待都算进去，那不是服务端的耗时。
        long[] t0s = new long[n];
        long[] t1s = new long[n];
        Map<String, LongAdder> codes = new ConcurrentHashMap<>();
        Map<String, LongAdder> msgs = new ConcurrentHashMap<>();

        // 起跑门闸：每个线程先 countDown 再 await，第 n 个把计数打到 0、
        // 全员同时放行。少了这道门，请求就是"陆续到达"，
        // 500 个请求会被摊平到几十毫秒里，根本撞不出库存竞争。
        CountDownLatch gate = new CountDownLatch(n);
        CountDownLatch done = new CountDownLatch(n);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final HttpRequest req = reqs[i];
                pool.submit(() -> {
                    // try 必须从第一行开始：门闸那两个 countDown 一个都不能被跳过，
                    // 否则主线程的 await 就是死等（上面那段注释记的就是这个坑）
                    try {
                        gate.countDown();
                        gate.await();
                        long t0 = System.nanoTime();
                        HttpResponse<String> resp =
                                client.send(req, HttpResponse.BodyHandlers.ofString());
                        long t1 = System.nanoTime();
                        t0s[idx] = t0;
                        t1s[idx] = t1;
                        String body = resp.body();
                        // http* 记的是真实 HTTP 状态码。本项目恒为 200，
                        // 所以它一旦不是 200 就是这个脚本最该先看的一行
                        bump(codes, "http" + resp.statusCode());
                        String code = grab(body, CODE);
                        bump(codes, "code" + code);
                        bump(msgs, "code" + code + " " + enc(grab(body, MSG)));
                    } catch (Throwable e) {
                        // 没拿到响应：连接被拒 / 超时 / 端口耗尽。单独计数，
                        // 不混进延迟百分位 —— 那种"耗时"是超时时长，不是服务端延迟。
                        // 捕 Throwable 而不是 Exception：Error 也得计数，
                        // 否则计数对不上、主线程照样干等
                        bump(codes, "err");
                        bump(msgs, "err " + enc(e.getClass().getSimpleName()));
                    } finally {
                        done.countDown();
                    }
                });
            }
            // 真出现任务卡死时，宁可报一句"只回来了 M/N 个"，
            // 也不要让整个脚本无声无息地挂住（第一版就是挂住，一个字都不输出）
            if (!done.await(120, TimeUnit.SECONDS)) {
                System.out.println("[FATAL] label=" + label + " only "
                        + (n - done.getCount()) + "/" + n
                        + " tasks came back in 120s -- aborting the rest");
                pool.shutdownNow();   // 打断卡住的任务，否则 close() 会接着等下去
            }
        }

        // 只对拿到响应的那些取延迟；爆发窗口也只看它们
        long[] lat = new long[n];
        int got = 0;
        long burstStart = Long.MAX_VALUE, burstEnd = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (t1s[i] > 0) {
                lat[got++] = t1s[i] - t0s[i];
                burstStart = Math.min(burstStart, t0s[i]);
                burstEnd = Math.max(burstEnd, t1s[i]);
            }
        }
        long[] sorted = Arrays.copyOf(lat, got);
        Arrays.sort(sorted);

        long elapsedUs = got == 0 ? 0 : (burstEnd - burstStart) / 1_000L;
        double tps = elapsedUs == 0 ? 0 : (got * 1_000_000.0 / elapsedUs);

        int ok = count(codes, "code200");
        int fail = got - ok;
        int err = count(codes, "err");

        // 任务没跑完整就说出来，别让半个回合的数字混进报告（第一版是连声音都没有）。
        //
        // 判据是 got + err 而不是 got：**连接失败的任务也是跑完了的任务**。
        // 拿 got 当判据会在"目标端口不通"时喊一句假的 FATAL —— 而一个会误报的
        // 警报，代价是以后所有的真警报都会被当成误报（这个假警报是我自己
        // 拿死端口自测时当场撞出来的，所以判据在这儿改对了）
        if (got + err < n) {
            System.out.println("[FATAL] label=" + label + " only " + (got + err) + "/" + n
                    + " tasks finished -- the numbers below are NOT a full round");
        }

        // 单位：nanoTime 是**纳秒**，pct() 原样返回纳秒，所以两处输出都要换算。
        // 第一版忘了除，于是打出了 p50=1579784.8ms 而整轮 elapsed=1790.4ms ——
        // 单条延迟比整轮还长 26 分钟，自相矛盾到一眼可见。坑很典型：
        // **中间量带着单位到处走，换算点却有两个（人读的行、机器读的行），
        // 改了一个漏了另一个。**
        System.out.printf("[result] label=%s n=%d ok=%d fail=%d err=%d tps=%.2f "
                        + "p50=%.1fms p95=%.1fms p99=%.1fms elapsed=%.1fms%n",
                label, n, ok, fail, err, tps,
                pct(sorted, 0.50) / 1_000_000.0, pct(sorted, 0.95) / 1_000_000.0,
                pct(sorted, 0.99) / 1_000_000.0, elapsedUs / 1000.0);

        // 中文 message 逐条列出来（百分号编码，见文件头）。断言"400 全是已抢完、
        // 没有一条是'你已经抢过了'"就靠这几行 —— 用 code 计数是分辨不出来的
        msgs.forEach((k, v) -> System.out.println("MSG " + label + " " + k + " " + v));

        System.out.println("RESULT {\"label\":\"" + label + "\",\"n\":" + n
                + ",\"code200\":" + ok
                + ",\"code400\":" + count(codes, "code400")
                + ",\"err\":" + err
                + ",\"tps\":" + String.format("%.2f", tps)
                + ",\"p50_us\":" + pct(sorted, 0.50) / 1_000L
                + ",\"p95_us\":" + pct(sorted, 0.95) / 1_000L
                + ",\"p99_us\":" + pct(sorted, 0.99) / 1_000L
                + ",\"elapsed_us\":" + elapsedUs + "}");
    }

    private static void bump(Map<String, LongAdder> m, String k) {
        m.computeIfAbsent(k, x -> new LongAdder()).increment();
    }

    private static int count(Map<String, LongAdder> m, String k) {
        LongAdder a = m.get(k);
        return a == null ? 0 : (int) a.sum();
    }

    private static String grab(String body, Pattern p) {
        if (body == null) return "NA";
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : "NA";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 第 p 分位（p=0.95 → P95）。空数组返回 0，别让调用方拿到 -1 这种"看着像数据"的东西 */
    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(i, sorted.length - 1))];
    }
}
