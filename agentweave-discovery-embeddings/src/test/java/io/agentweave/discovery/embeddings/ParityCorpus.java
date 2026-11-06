package io.agentweave.discovery.embeddings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Texts for the parity tests: a fixed sample of tricky strings, and every JSON string (keys and
 * values) in the discovery eval datasets when they have been downloaded.
 */
final class ParityCorpus {

    static final String DATA_PROPERTY = "agentweave.parity.data";

    private static volatile List<String> evalTexts;

    private ParityCorpus() {}

    static List<String> tricky() {
        List<String> texts = new ArrayList<>(List.of(
                "Héllo Wörld", "Crème brûlée à la carte", "naïve façade coöperate", "ÅNGSTRÖM unit",
                "İstanbul ıi I", "straße STRASSE ẞ", "ﬁnance ﬂow ligatures", "Ⅻ ① ½ ²", "ΣΑΣ σας ς Ελληνικά",
                "Русский текст: поиск инструментов", "עברית עם ניקוד: שָׁלוֹם", "العربية مع تشكيل: مَرْحَبًا",
                "हिन्दी पाठ", "日本語のテキスト カタカナ ﾊﾝｶｸ", "中文搜索工具，获取天气。", "한국어 텍스트 검색",
                "𠀀𪜀 CJK extension B", "豈 更 compatibility ideographs", "Tiếng Việt có dấu", "ภาษาไทย", "ąęółśżź",
                "emoji 🙂👍🏽👨‍👩‍👧 🇺🇸 ❤️ 🤩🥹 🧀", "camelCaseToolName getHTTPResponseCode XMLHttpRequest",
                "snake_case_tool_name __dunder__", "kebab-case-name", "fs.read_file", "github.create_pull_request",
                "https://example.com/path/to?q=1&x=y#frag", "user.name+tag@example.co.uk", "C:\\Program Files\\x",
                "$100.00 €5 £3 ¥4 ₹10", "50% off!!!", "...?!?!", "—–-‒― dashes", "“smart” ‘single’ «guillemets» „low“",
                "a\u0000b\u0001c\u007Fd\u0080e\u009Ff", "tab\there\nnewline\r\nCRLF\u000Bvt\u000Cff",
                "\u00A0nbsp\u2003em\u3000ideographic\u2028line\u2029para\u0085nel\u1680ogham",
                "zero\u200Bwidth\u200Djoiner\uFEFFbom\u2060wj", "soft\u00ADhyphen", "\uFFFDreplacement",
                "\uE000private\uF8FFuse", "a\u0301 e\u0300 combining", "lone\uD800surrogate\uDC00s",
                "x".repeat(100), "y".repeat(101), "supercalifragilisticexpialidocious antidisestablishmentarianism",
                "pneumonoultramicroscopicsilicovolcanoconiosis", "[CLS] [SEP] [MASK] [PAD] [UNK] [cls] [MASK x[SEP]y",
                "<b>html</b> &amp; &lt;tag&gt;", "{\"json\": [1, 2.5e-3, true, null], \"k\": {\"n\": \"v\"}}",
                "SELECT * FROM t WHERE x = 'y';", "0x1F600 1,000,000 3.14159 12345678901234567890",
                "don't won't it's I'm we've they're", "I do not know", "rock 'n' roll", "a . b , c ? d !",
                "##prefix ## #hash", "ⓐⓑⓒ ＡＢＣ fullwidth ｆｏｏ", "Ǆ ǅ ǆ titlecase", "\u0130\u0131",
                "ᏣᎳᎩ Cherokee", "𝐁𝐨𝐥𝐝 math 𝔣𝔯𝔞𝔨", "ⅰⅱⅲ", "Ω Å K ohm angstrom kelvin", "‘’‚‛“”„‟",
                "\u166D\u1734 \u2E43\u2E52 \u0890 newer categories", "x\u0007bell"));
        texts.add(String.join(" ", Collections.nCopies(300, "Search the catalog for tools that read files.")));
        texts.add(String.join(" ", Collections.nCopies(120, "antidisestablishmentarianism")));
        texts.add("word ".repeat(509) + "tail");
        texts.add("word ".repeat(510) + "tail");
        texts.add("word ".repeat(600));
        return texts;
    }

    /** The eval dataset texts, or empty if the datasets are not present. */
    static Optional<List<String>> evalTexts() {
        Path dir = Path.of(System.getProperty(DATA_PROPERTY, "evals/tool-discovery/build/data"));
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        List<String> texts = evalTexts;
        if (texts == null) {
            texts = load(dir);
            evalTexts = texts;
        }
        return texts.isEmpty() ? Optional.empty() : Optional.of(texts);
    }

    /** A reproducible random sample. */
    static List<String> sample(List<String> texts, int size, long seed) {
        List<String> copy = new ArrayList<>(texts);
        Collections.shuffle(copy, new Random(seed));
        return copy.subList(0, Math.min(size, copy.size()));
    }

    private static List<String> load(Path dir) {
        Set<String> texts = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json") || f.toString().endsWith(".jsonl"))
                    .sorted().toList()) {
                jsonStrings(Files.readString(file), texts);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        texts.removeIf(String::isBlank);
        return List.copyOf(texts);
    }

    /** Adds every JSON string literal (keys and values) in {@code json}, unescaped. */
    static void jsonStrings(String json, Set<String> out) {
        StringBuilder current = null;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (current == null) {
                if (c == '"') {
                    current = new StringBuilder();
                }
            } else if (c == '\\') {
                char e = json.charAt(++i);
                switch (e) {
                    case 'n' -> current.append('\n');
                    case 't' -> current.append('\t');
                    case 'r' -> current.append('\r');
                    case 'b' -> current.append('\b');
                    case 'f' -> current.append('\f');
                    case 'u' -> {
                        current.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> current.append(e);
                }
            } else if (c == '"') {
                out.add(current.toString());
                current = null;
            } else {
                current.append(c);
            }
        }
    }
}
